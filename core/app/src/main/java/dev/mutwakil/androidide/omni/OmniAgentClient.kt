package dev.mutwakil.androidide.omni

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.omnilink.sdk.AgentTaskEvent
import com.omnilink.sdk.AgentGatewayManifest
import com.omnilink.sdk.AgentTaskSnapshot
import com.omnilink.sdk.AgentTaskEventPage
import com.omnilink.sdk.AgentConversationSnapshot
import com.omnilink.sdk.AgentConversationReadQuery
import com.omnilink.sdk.AgentConversationQuery
import com.omnilink.sdk.AgentConversationList
import com.omnilink.sdk.AgentTaskRequest
import com.omnilink.sdk.IAgentGatewayService
import com.omnilink.sdk.IOmniAgentCallback
import com.omnilink.sdk.OmniLinkConstants
import com.omnilink.sdk.trusted.TrustedServiceResolver
import com.omnilink.sdk.trusted.TrustedServicePolicy
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    @Volatile
    private var negotiation: GatewayNegotiation? = null
    private val connectMutex = Mutex()

    private data class GatewayNegotiation(
        val protocolVersion: Int,
        val manifest: AgentGatewayManifest
    )

    suspend fun gatewayManifest(): String =
        json.encodeToString(AgentGatewayManifest.serializer(), negotiate().manifest)

    suspend fun supportsCanonicalHistory(): Boolean {
        val negotiated = negotiate()
        return negotiated.protocolVersion >= 4 && negotiated.manifest.supportsHistoryRead
    }

    suspend fun supportsEventReplay(): Boolean {
        val negotiated = negotiate()
        return negotiated.protocolVersion >= 4 && negotiated.manifest.supportsEventReplay
    }

    suspend fun listConversations(
        query: AgentConversationQuery = AgentConversationQuery()
    ): AgentConversationList {
        val negotiated = negotiate()
        check(negotiated.protocolVersion >= 4 && negotiated.manifest.supportsHistoryRead) {
            "Connected Workspace does not support canonical history yet. Update Omni Dev Workspace to OmniLink v1.2+."
        }
        val raw = connect().listAgentConversations(
            negotiated.protocolVersion,
            json.encodeToString(AgentConversationQuery.serializer(), query)
        )
        gatewayError(raw)?.let { throw IllegalStateException(it) }
        return json.decodeFromString(AgentConversationList.serializer(), raw)
    }

    suspend fun getConversation(
        conversationId: String,
        query: AgentConversationReadQuery = AgentConversationReadQuery()
    ): AgentConversationSnapshot {
        val negotiated = negotiate()
        check(negotiated.protocolVersion >= 4 && negotiated.manifest.supportsHistoryRead) {
            "Connected Workspace does not support canonical history yet. Update Omni Dev Workspace to OmniLink v1.2+."
        }
        val raw = connect().getAgentConversation(
            negotiated.protocolVersion,
            conversationId,
            json.encodeToString(AgentConversationReadQuery.serializer(), query)
        )
        gatewayError(raw)?.let { throw IllegalStateException(it) }
        return json.decodeFromString(AgentConversationSnapshot.serializer(), raw)
    }

    suspend fun taskSnapshot(taskId: String): AgentTaskSnapshot {
        val raw = connect().getTaskSnapshot(
            negotiate().protocolVersion,
            taskId
        )
        return json.decodeFromString(AgentTaskSnapshot.serializer(), raw)
    }

    suspend fun replayTaskEvents(
        taskId: String,
        afterSequence: Long,
        limit: Int = 50
    ): AgentTaskEventPage {
        val negotiated = negotiate()
        check(negotiated.protocolVersion >= 4 && negotiated.manifest.supportsEventReplay) {
            "Connected Workspace does not support live event replay yet. Update Omni Dev Workspace to OmniLink v1.2+."
        }
        val raw = connect().getTaskEvents(
            negotiated.protocolVersion,
            taskId,
            afterSequence.coerceAtLeast(0L),
            limit.coerceIn(1, 50)
        )
        gatewayError(raw)?.let { throw IllegalStateException(it) }
        return json.decodeFromString(AgentTaskEventPage.serializer(), raw)
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

        var taskBinder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null
        try {
            val negotiated = negotiate()
            val service = connect()
            val binder = service.asBinder()
            val recipient = IBinder.DeathRecipient {
                remote = null
                negotiation = null
                close(IllegalStateException("Omni Agent Gateway binder died during the live task"))
            }
            binder.linkToDeath(recipient, 0)
            taskBinder = binder
            deathRecipient = recipient

            service.startAgentTask(
                negotiated.protocolVersion,
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

        awaitClose {
            val binder = taskBinder
            val recipient = deathRecipient
            if (binder != null && recipient != null) {
                runCatching { binder.unlinkToDeath(recipient, 0) }
            }
        }
    }

    suspend fun cancel(taskId: String) {
        connect().cancelAgentTask(taskId)
    }

    fun disconnect() {
        val conn = connection ?: return
        runCatching { appContext.unbindService(conn) }
        connection = null
        remote = null
        negotiation = null
    }

    private suspend fun negotiate(): GatewayNegotiation {
        negotiation?.let { return it }
        val service = connect()
        val raw = service.getGatewayManifest(OmniLinkConstants.CURRENT_PROTOCOL_VERSION)
        val manifest = json.decodeFromString(AgentGatewayManifest.serializer(), raw)
        val preferred = minOf(
            OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
            manifest.maxSupportedVersion
        )
        check(preferred >= manifest.minSupportedVersion) {
            "No compatible OmniLink Agent Gateway protocol. AndroidIDE=" +
                OmniLinkConstants.CURRENT_PROTOCOL_VERSION +
                ", Workspace=" + manifest.minSupportedVersion + ".." +
                manifest.maxSupportedVersion
        }
        return GatewayNegotiation(preferred, manifest).also { negotiation = it }
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
                        negotiation = null
                    }

                    override fun onBindingDied(name: ComponentName?) {
                        remote = null
                        connection = null
                        negotiation = null
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Omni Agent Gateway binding died")
                            )
                        }
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        remote = null
                        connection = null
                        negotiation = null
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

    private fun gatewayError(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains("\"error\"")) return null
        return runCatching {
            val element = json.parseToJsonElement(trimmed)
            element.jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun discoverGateway(): ComponentName? {
        // Package/action alone is never a trust signal. The v2 resolver verifies the
        // real service permission and final APK signer before producing an explicit component.
        val allowedPackages = setOf(
            "com.omnidev.workspace",
            "com.omnidev.workspace.norm",
            "com.omnidev.workspace.pro",
            "com.omnidev.workspace.oem",
            "com.omnidev.workspace.admin"
        )
        return TrustedServiceResolver(appContext).query(
            TrustedServicePolicy(
                action = OmniLinkConstants.ACTION_AGENT_GATEWAY_BIND,
                requiredPermission = OmniLinkConstants.PERMISSION_BIND_AGENT,
                allowedPackages = allowedPackages
            )
        ).verified.firstOrNull()?.component
    }
}

class OmniConversationStore(context: Context) {
    data class ConversationSummary(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val status: String? = null
    )

    data class RunCursor(
        val taskId: String,
        val lastSequence: Long
    )

    companion object {
        private const val MAX_TRANSCRIPT_CHARS = 80_000
        private const val MAX_CONSOLE_CHARS = 80_000
        private const val MAX_PROJECT_HISTORY = 40
        private const val HISTORY_SEPARATOR = "|"
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        "omni_androidide_conversations",
        Context.MODE_PRIVATE
    )

    fun current(projectRoot: String): String {
        val key = projectKey(projectRoot)
        val existing = prefs.getString(key, null)
        if (!existing.isNullOrBlank()) {
            ensureInHistory(projectRoot, existing)
            return existing
        }
        return newConversation(projectRoot)
    }

    fun newConversation(projectRoot: String): String {
        val id = "androidide-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(projectKey(projectRoot), id)
            .remove(titledKey(id))
            .putString(titleKey(id), "New chat")
            .putLong(updatedKey(id), now)
            .putString(transcriptKey(id), "")
            .putString(consoleKey(id), "")
            .apply()
        updateHistory(projectRoot, listOf(id) + historyIds(projectRoot))
        return id
    }

    fun list(projectRoot: String): List<ConversationSummary> =
        historyIds(projectRoot)
            .map { id ->
                ConversationSummary(
                    id = id,
                    title = title(id),
                    updatedAt = prefs.getLong(updatedKey(id), 0L)
                )
            }
            .sortedByDescending { it.updatedAt }

    fun select(projectRoot: String, conversationId: String) {
        prefs.edit().putString(projectKey(projectRoot), conversationId).apply()
        touch(projectRoot, conversationId)
    }

    fun needsTitle(conversationId: String): Boolean =
        !prefs.getBoolean(titledKey(conversationId), false)

    fun markTitled(conversationId: String) {
        prefs.edit().putBoolean(titledKey(conversationId), true).apply()
    }

    fun setTitle(projectRoot: String, conversationId: String, title: String) {
        val clean = title.trim().replace(Regex("\\s+"), " ").take(72)
            .ifBlank { "AndroidIDE conversation" }
        prefs.edit()
            .putString(titleKey(conversationId), clean)
            .putBoolean(titledKey(conversationId), true)
            .putLong(updatedKey(conversationId), System.currentTimeMillis())
            .apply()
        touch(projectRoot, conversationId)
    }

    fun title(conversationId: String): String =
        prefs.getString(titleKey(conversationId), null)
            ?.takeIf { it.isNotBlank() }
            ?: "AndroidIDE conversation"

    fun transcript(conversationId: String): String =
        prefs.getString(transcriptKey(conversationId), "").orEmpty()

    fun console(conversationId: String): String =
        prefs.getString(consoleKey(conversationId), "").orEmpty()

    fun runCursor(conversationId: String): RunCursor? {
        val taskId = prefs.getString(activeTaskKey(conversationId), null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return RunCursor(
            taskId = taskId,
            lastSequence = prefs.getLong(lastSequenceKey(conversationId), 0L)
        )
    }

    fun saveRunCursor(
        conversationId: String,
        taskId: String,
        lastSequence: Long
    ) {
        prefs.edit()
            .putString(activeTaskKey(conversationId), taskId)
            .putLong(lastSequenceKey(conversationId), lastSequence.coerceAtLeast(0L))
            .apply()
    }

    fun clearRunCursor(conversationId: String) {
        prefs.edit()
            .remove(activeTaskKey(conversationId))
            .remove(lastSequenceKey(conversationId))
            .apply()
    }

    fun saveConsole(projectRoot: String, conversationId: String, text: String) {
        val bounded = text.takeLast(MAX_CONSOLE_CHARS)
        prefs.edit()
            .putString(consoleKey(conversationId), bounded)
            .putLong(updatedKey(conversationId), System.currentTimeMillis())
            .apply()
        touch(projectRoot, conversationId)
    }

    fun saveTranscript(projectRoot: String, conversationId: String, text: String) {
        val bounded = if (text.length <= MAX_TRANSCRIPT_CHARS) {
            text
        } else {
            "… [older local transcript trimmed]\n" +
                text.takeLast(MAX_TRANSCRIPT_CHARS - 36)
        }
        prefs.edit()
            .putString(transcriptKey(conversationId), bounded)
            .putLong(updatedKey(conversationId), System.currentTimeMillis())
            .apply()
        touch(projectRoot, conversationId)
    }

    fun remove(projectRoot: String, conversationId: String) {
        val remaining = historyIds(projectRoot).filterNot { it == conversationId }
        val current = prefs.getString(projectKey(projectRoot), null)
        val editor = prefs.edit()
            .remove(titleKey(conversationId))
            .remove(titledKey(conversationId))
            .remove(updatedKey(conversationId))
            .remove(transcriptKey(conversationId))
            .remove(consoleKey(conversationId))
            .remove(activeTaskKey(conversationId))
            .remove(lastSequenceKey(conversationId))

        if (current == conversationId) {
            editor.remove(projectKey(projectRoot))
        }
        editor.apply()
        updateHistory(projectRoot, remaining)
    }

    private fun touch(projectRoot: String, conversationId: String) {
        val ordered = listOf(conversationId) +
            historyIds(projectRoot).filterNot { it == conversationId }
        prefs.edit()
            .putLong(updatedKey(conversationId), System.currentTimeMillis())
            .apply()
        updateHistory(projectRoot, ordered)
    }

    private fun ensureInHistory(projectRoot: String, conversationId: String) {
        if (conversationId !in historyIds(projectRoot)) {
            updateHistory(projectRoot, listOf(conversationId) + historyIds(projectRoot))
        }
    }

    private fun updateHistory(projectRoot: String, ids: List<String>) {
        val normalized = ids
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_PROJECT_HISTORY)
            .toList()
        prefs.edit()
            .putString(historyKey(projectRoot), normalized.joinToString(HISTORY_SEPARATOR))
            .apply()
    }

    private fun historyIds(projectRoot: String): List<String> =
        prefs.getString(historyKey(projectRoot), "").orEmpty()
            .split(HISTORY_SEPARATOR)
            .filter { it.isNotBlank() }

    private fun projectHash(projectRoot: String): String =
        OmniIdeStateBridge.sha256(projectRoot).take(24)

    private fun projectKey(projectRoot: String): String =
        "current_" + projectHash(projectRoot)

    private fun historyKey(projectRoot: String): String =
        "history_" + projectHash(projectRoot)

    private fun titleKey(conversationId: String): String = "title_$conversationId"
    private fun titledKey(conversationId: String): String = "titled_$conversationId"
    private fun updatedKey(conversationId: String): String = "updated_$conversationId"
    private fun transcriptKey(conversationId: String): String = "transcript_$conversationId"
    private fun consoleKey(conversationId: String): String = "console_$conversationId"
    private fun activeTaskKey(conversationId: String): String = "active_task_$conversationId"
    private fun lastSequenceKey(conversationId: String): String = "last_sequence_$conversationId"
}
