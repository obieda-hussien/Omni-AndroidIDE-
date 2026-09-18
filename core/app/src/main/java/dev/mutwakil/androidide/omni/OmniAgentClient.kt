package dev.mutwakil.androidide.omni

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.omnilink.sdk.AgentTaskEvent
import com.omnilink.sdk.AgentTaskRequest
import com.omnilink.sdk.IAgentGatewayService
import com.omnilink.sdk.IOmniAgentCallback
import com.omnilink.sdk.OmniLinkConstants
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * AndroidIDE -> Workspace connection. Workspace remains the only model/tool/MCP runtime.
 */
class OmniAgentClient(private val context: Context) {
    companion object {
        private const val MAX_GATEWAY_REQUEST_CHARS = 300_000
    }

    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
    }

    @Volatile
    private var remote: IAgentGatewayService? = null
    @Volatile
    private var connection: ServiceConnection? = null
    private val connectMutex = Mutex()

    suspend fun gatewayManifest(): String {
        val service = connect()
        return service.getGatewayManifest(OmniLinkConstants.CURRENT_PROTOCOL_VERSION)
    }

    fun runTask(request: AgentTaskRequest): Flow<AgentTaskEvent> = callbackFlow {
        val callback = object : IOmniAgentCallback.Stub() {
            override fun onEvent(eventJson: String) {
                val event = runCatching {
                    json.decodeFromString(AgentTaskEvent.serializer(), eventJson)
                }.getOrElse { error ->
                    AgentTaskEvent.Error(
                        taskId = request.taskId,
                        sequence = 0,
                        timestamp = System.currentTimeMillis(),
                        code = "decode_error",
                        message = error.message ?: "Could not decode Omni event"
                    )
                }
                trySend(event)
                if (
                    event is AgentTaskEvent.FinalAnswer ||
                    event is AgentTaskEvent.Error ||
                    event is AgentTaskEvent.Cancelled
                ) {
                    close()
                }
            }
        }

        val requestJson = json.encodeToString(AgentTaskRequest.serializer(), request)
        if (requestJson.length > MAX_GATEWAY_REQUEST_CHARS) {
            trySend(
                AgentTaskEvent.Error(
                    taskId = request.taskId,
                    sequence = 0,
                    timestamp = System.currentTimeMillis(),
                    code = "request_too_large",
                    message = "AndroidIDE context is too large for safe Binder transport. " +
                        "Narrow the active context or retry after reducing build output."
                )
            )
            close()
            return@callbackFlow
        }

        try {
            connect().startAgentTask(
                OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
                requestJson,
                callback
            )
        } catch (error: Exception) {
            trySend(
                AgentTaskEvent.Error(
                    taskId = request.taskId,
                    sequence = 0,
                    timestamp = System.currentTimeMillis(),
                    code = "connection_error",
                    message = error.message ?: "Unable to connect to Omni"
                )
            )
            close()
        }

        awaitClose { }
    }

    suspend fun cancel(taskId: String) {
        connect().cancelAgentTask(taskId)
    }

    fun disconnect() {
        val conn = connection ?: return
        runCatching { appContext.unbindService(conn) }
        connection = null
        remote = null
    }

    private suspend fun connect(): IAgentGatewayService = withTimeout(10_000) {
        connectMutex.withLock {
            remote?.let { return@withLock it }

            // A disconnected binding remains registered with Android. Explicit reconnect attempts
            // should replace that stale binding rather than stacking another ServiceConnection.
            connection?.let { stale ->
                runCatching { appContext.unbindService(stale) }
                connection = null
            }

            val component = discoverGateway()
                ?: throw IllegalStateException(
                    "Omni Dev Workspace is not installed, not signed with the shared Omni key, " +
                        "or its Agent Gateway is unavailable."
                )

            suspendCancellableCoroutine { continuation ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val service = IAgentGatewayService.Stub.asInterface(binder)
                        if (service == null) {
                            runCatching { appContext.unbindService(this) }
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException("Omni Agent Gateway returned a null binder")
                                )
                            }
                            return
                        }
                        remote = service
                        connection = this
                        if (continuation.isActive) continuation.resume(service)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        remote = null
                    }

                    override fun onBindingDied(name: ComponentName?) {
                        remote = null
                        connection = null
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Omni Agent Gateway binding died")
                            )
                        }
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        remote = null
                        connection = null
                        runCatching { appContext.unbindService(this) }
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Omni Agent Gateway returned a null binding")
                            )
                        }
                    }
                }

                val intent = Intent(OmniLinkConstants.ACTION_AGENT_GATEWAY_BIND).apply {
                    this.component = component
                    setPackage(component.packageName)
                }
                val bound = runCatching {
                    appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                }.getOrDefault(false)

                if (!bound && continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("Android refused the Omni Agent Gateway binding")
                    )
                }

                continuation.invokeOnCancellation {
                    if (remote == null) runCatching { appContext.unbindService(conn) }
                }
            }
        }
    }

    private fun discoverGateway(): ComponentName? {
        val pm = appContext.packageManager
        val intent = Intent(OmniLinkConstants.ACTION_AGENT_GATEWAY_BIND)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }

        return matches.asSequence()
            .mapNotNull { it.serviceInfo }
            .filter { it.exported }
            .filter {
                pm.checkSignatures(appContext.packageName, it.packageName) ==
                    PackageManager.SIGNATURE_MATCH
            }
            .map { ComponentName(it.packageName, it.name) }
            .firstOrNull()
    }
}

class OmniConversationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "omni_androidide_conversations",
        Context.MODE_PRIVATE
    )

    fun current(projectRoot: String): String {
        val key = projectKey(projectRoot)
        return prefs.getString(key, null) ?: newConversation(projectRoot)
    }

    fun newConversation(projectRoot: String): String {
        val id = "androidide-" + UUID.randomUUID()
        prefs.edit()
            .putString(projectKey(projectRoot), id)
            .remove(titleKey(id))
            .apply()
        return id
    }

    fun needsTitle(conversationId: String): Boolean =
        !prefs.getBoolean(titleKey(conversationId), false)

    fun markTitled(conversationId: String) {
        prefs.edit().putBoolean(titleKey(conversationId), true).apply()
    }

    private fun projectKey(projectRoot: String): String =
        "current_" + OmniIdeStateBridge.sha256(projectRoot).take(24)

    private fun titleKey(conversationId: String): String = "titled_$conversationId"
}
