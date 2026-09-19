package dev.mutwakil.androidide.omni

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.omnilink.sdk.ActionRequest
import com.omnilink.sdk.IExtensionService
import com.omnilink.sdk.IOmniResultCallback
import com.omnilink.sdk.OmniLinkConstants
import com.omnilink.sdk.trusted.TrustedServicePolicy
import com.omnilink.sdk.trusted.TrustedServiceResolver
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * AndroidIDE-side shared-memory adapter. The Workspace owns its Room DB; the IDE exchanges only
 * bounded, versioned records, and never reads/writes the other app's database files.
 */
class OmniWorkspaceMemoryClient(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    suspend fun publishProjectSnapshot(): Boolean {
        val snapshot = OmniIdeStateBridge.collectContext()
        val root = snapshot["projectRoot"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: return false
        val active = snapshot["activeDocument"] as? JsonObject
        val content = buildJsonObject {
            put("projectRoot", root)
            put("projectInitialized", snapshot["projectInitialized"] ?: kotlinx.serialization.json.JsonNull)
            put("buildInProgress", snapshot["buildInProgress"] ?: kotlinx.serialization.json.JsonNull)
            put("toolingServerStarted", snapshot["toolingServerStarted"] ?: kotlinx.serialization.json.JsonNull)
            put("activeFile", active?.get("relativePath") ?: kotlinx.serialization.json.JsonNull)
            put("activeRevision", active?.get("revision") ?: kotlinx.serialization.json.JsonNull)
            put("activeDirty", active?.get("dirty") ?: kotlinx.serialization.json.JsonNull)
            put(
                "buildOutputTail",
                snapshot["buildOutputTail"]?.jsonPrimitive?.contentOrNull.orEmpty().takeLast(2000)
            )
        }
        val timestamp = System.currentTimeMillis()
        val record = buildJsonObject {
            put("recordId", "project-" + sha256(root).take(24))
            put("kind", "active_project")
            put("content", content)
            put("revision", timestamp)
            put("updatedAt", timestamp)
        }
        val result = invoke("workspace.context.publish", record)
        val parsed = runCatching { org.json.JSONObject(result) }.getOrNull()
            ?: return false
        return parsed.optJSONObject("data")?.optBoolean("accepted") == true
    }

    suspend fun search(query: String, limit: Int = 8): String =
        invoke(
            "workspace.memory.search",
            buildJsonObject {
                put("query", query.take(256))
                put("limit", limit.coerceIn(1, 8))
            }
        )

    suspend fun changesSince(epochMillis: Long, limit: Int = 8): String =
        invoke(
            "workspace.memory.delta",
            buildJsonObject {
                put("sinceEpochMs", epochMillis.coerceAtLeast(0L))
                put("limit", limit.coerceIn(1, 8))
            }
        )

    private suspend fun invoke(capability: String, payload: JsonObject): String =
        withContext(Dispatchers.IO) {
            withTimeout(15_000) {
                val verified = TrustedServiceResolver(appContext).query(
                    TrustedServicePolicy(
                        action = OmniLinkConstants.ACTION_EXTENSION_BIND,
                        requiredPermission = OmniLinkConstants.PERMISSION_BIND_EXTENSION,
                        allowedPackages = setOf(
                            "com.omnidev.workspace",
                            "com.omnidev.workspace.admin",
                            "com.omnidev.workspace.pro",
                            "com.omnidev.workspace.norm",
                            "com.omnidev.workspace.oem"
                        )
                    )
                ).verified.firstOrNull()
                    ?: error("Workspace shared-memory service is not available/verified")
                val connection = bind(verified.component)
                try {
                    val request = ActionRequest(
                        name = capability,
                        payload = payload,
                        requestId = UUID.randomUUID().toString(),
                        idempotencyKey = UUID.randomUUID().toString()
                    )
                    val reply = CompletableDeferred<String>()
                    val callback = object : IOmniResultCallback.Stub() {
                        override fun onResult(resultJson: String) {
                            reply.complete(resultJson)
                        }
                    }
                    connection.first.executeActionAsync(
                        OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
                        json.encodeToString(ActionRequest.serializer(), request),
                        callback
                    )
                    withTimeout(10_000) { reply.await() }
                } finally {
                    runCatching { appContext.unbindService(connection.second) }
                }
            }
        }

    private suspend fun bind(
        component: ComponentName
    ): Pair<IExtensionService, ServiceConnection> =
        suspendCancellableCoroutine { continuation ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (name != component || binder == null) {
                        if (continuation.isActive) continuation.resumeWithException(
                            SecurityException("Workspace service changed during binding")
                        )
                        return
                    }
                    val service = IExtensionService.Stub.asInterface(binder)
                    if (continuation.isActive) continuation.resume(service to this)
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
            val intent = Intent(OmniLinkConstants.ACTION_EXTENSION_BIND)
                .setComponent(component)
                .setPackage(component.packageName)
            val bound = runCatching {
                appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound && continuation.isActive) {
                continuation.resumeWithException(
                    IllegalStateException("Workspace shared-memory bind failed")
                )
            }
            continuation.invokeOnCancellation {
                if (bound) runCatching { appContext.unbindService(connection) }
            }
        }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
