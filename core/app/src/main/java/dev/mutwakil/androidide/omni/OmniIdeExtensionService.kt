package dev.mutwakil.androidide.omni

import android.content.Intent
import android.content.pm.PackageManager
import android.os.RemoteCallbackList
import android.util.Log
import com.omnilink.sdk.AccessController
import com.omnilink.sdk.AccessDecision
import com.omnilink.sdk.ActionError
import com.omnilink.sdk.ActionOutcome
import com.omnilink.sdk.ActionRequest
import com.omnilink.sdk.AuditLogger
import com.omnilink.sdk.CallerContext
import com.omnilink.sdk.CapabilityDescriptor
import com.omnilink.sdk.CapabilityExecutionMode
import com.omnilink.sdk.ExtensionService
import com.omnilink.sdk.IOmniEventCallback
import com.omnilink.sdk.OmniEvent
import com.omnilink.sdk.OmniLinkConstants
import com.omnilink.sdk.SecurityValidator
import dev.mutwakil.androidide.activities.editor.EditorActivityKt
import dev.mutwakil.androidide.preferences.internal.GeneralPreferences
import dev.mutwakil.androidide.project.manager.builder.LanguageType
import dev.mutwakil.androidide.projects.ProjectManagerImpl
import dev.mutwakil.androidide.templates.android.TemplateOptions
import dev.mutwakil.androidide.templates.android.TemplateRegistry
import dev.mutwakil.androidide.utils.Environment
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * AndroidIDE's native OmniLink capability endpoint.
 *
 * The agent never needs Accessibility to manipulate the IDE. Project state, source files, template
 * creation, Gradle builds/tests/lint, sync and editor refresh are exposed as typed capabilities.
 */
class OmniIdeExtensionService : ExtensionService() {

    companion object {
        private const val TAG = "OmniIdeExtension"
        private const val MIN_PROTOCOL = 3
        private const val MAX_PROTOCOL = 3
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buildController by lazy { OmniIdeBuildController(applicationContext) }
    private val listeners = RemoteCallbackList<IOmniEventCallback>()
    private val jobRecords = ConcurrentHashMap<String, IdeJobRecord>()
    private val runningJobs = ConcurrentHashMap<String, Job>()

    private val buildOutputListener: (String) -> Unit = { chunk ->
        publish(
            "ide.job.output",
            buildJsonObject {
                put("chunk", chunk.take(16_000))
                put("timestamp", System.currentTimeMillis())
            }
        )
    }

    override val minSupportedVersion: Int = MIN_PROTOCOL
    override val maxSupportedVersion: Int = MAX_PROTOCOL

    override val securityValidator: SecurityValidator = object : SecurityValidator {
        override fun isCallerAuthorized(context: android.content.Context, caller: CallerContext): Boolean {
            return context.packageManager.checkSignatures(
                context.packageName,
                caller.callingPackage
            ) == PackageManager.SIGNATURE_MATCH
        }
    }

    override val accessController: AccessController = object : AccessController {
        override fun decide(caller: CallerContext, request: ActionRequest): AccessDecision =
            AccessDecision.ALLOW
    }

    override val auditLogger: AuditLogger = object : AuditLogger {
        override fun log(caller: CallerContext, request: ActionRequest, result: ActionOutcome) {
            Log.i(
                TAG,
                "caller=" + caller.callingPackage +
                    " action=" + request.name +
                    " outcome=" + result.javaClass.simpleName
            )
        }
    }

    override val capabilities: List<CapabilityDescriptor> = listOf(
        capability("ide.get_project_context", "Project root, modules, sync issues, active document and build tail.", CapabilityExecutionMode.IMMEDIATE),
        capability("ide.get_active_document", "Current editor file, content, dirty state and revision.", CapabilityExecutionMode.IMMEDIATE),
        capability("ide.read_file", "Read a project file with a SHA-256 revision.", CapabilityExecutionMode.ASYNC),
        capability("ide.write_file", "Revision-safe project file replacement. Refuses dirty editor buffers.", CapabilityExecutionMode.ASYNC),
        capability("ide.list_files", "List project files while skipping build/.gradle/.git trees.", CapabilityExecutionMode.ASYNC),
        capability("ide.open_file", "Open a project file in the AndroidIDE editor.", CapabilityExecutionMode.ASYNC),
        capability("ide.refresh_project", "Reload externally changed clean buffers.", CapabilityExecutionMode.ASYNC),
        capability("ide.sync_project", "Synchronize project through AndroidIDE's Tooling API.", CapabilityExecutionMode.JOB, streaming = true),
        capability("ide.start_build", "Execute Gradle build tasks through AndroidIDE's Tooling API.", CapabilityExecutionMode.JOB, streaming = true),
        capability("ide.start_tests", "Execute project tests through AndroidIDE's Tooling API.", CapabilityExecutionMode.JOB, streaming = true),
        capability("ide.start_lint", "Execute Android lint through AndroidIDE's Tooling API.", CapabilityExecutionMode.JOB, streaming = true),
        capability("ide.get_job", "Read the state/result of an IDE job.", CapabilityExecutionMode.IMMEDIATE),
        capability("ide.cancel_job", "Cancel an Omni-started IDE job and the current Gradle build.", CapabilityExecutionMode.ASYNC),
        capability("ide.create_project", "Create a project with AndroidIDE's own template engine.", CapabilityExecutionMode.JOB, streaming = true),
        capability("ide.open_project", "Set and open a project in AndroidIDE.", CapabilityExecutionMode.ASYNC)
    )

    override fun onCreate() {
        super.onCreate()
        OmniIdeStateBridge.addBuildOutputListener(buildOutputListener)
    }

    override fun onDestroy() {
        OmniIdeStateBridge.removeBuildOutputListener(buildOutputListener)
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        listeners.kill()
        buildController.release()
        scope.cancel()
        super.onDestroy()
    }

    override suspend fun onAction(caller: CallerContext, request: ActionRequest): ActionOutcome {
        val payload = request.payload as? JsonObject ?: buildJsonObject {}
        return try {
            when (request.name) {
                "ide.get_project_context" -> success(OmniIdeStateBridge.collectContext())
                "ide.get_active_document" -> success(
                    OmniIdeStateBridge.activeDocument() ?: buildJsonObject {
                        put("open", false)
                    }
                )
                "ide.read_file" -> success(
                    OmniIdeStateBridge.readFile(payload.requiredString("path"))
                )
                "ide.write_file" -> success(
                    OmniIdeStateBridge.writeFile(
                        path = payload.requiredString("path"),
                        content = payload.requiredString("content"),
                        expectedRevision = payload.string("expected_revision")
                    )
                )
                "ide.list_files" -> success(
                    OmniIdeStateBridge.listFiles(
                        relativeDir = payload.string("path").orEmpty(),
                        limit = payload.int("limit") ?: 500
                    )
                )
                "ide.open_file" -> {
                    OmniIdeStateBridge.openFile(payload.requiredString("path"))
                    success(buildJsonObject { put("ok", true) })
                }
                "ide.refresh_project" -> {
                    OmniIdeStateBridge.refreshOpenEditors()
                    success(buildJsonObject { put("ok", true) })
                }
                "ide.sync_project" -> startSyncJob(force = payload.bool("force") ?: true)
                "ide.start_build" -> startGradleJob(
                    kind = "build",
                    tasks = payload.stringList("tasks").ifEmpty { listOf("assembleDebug") },
                    forceSync = payload.bool("force_sync") ?: false
                )
                "ide.start_tests" -> startGradleJob(
                    kind = "test",
                    tasks = payload.stringList("tasks").ifEmpty { listOf("test") },
                    forceSync = payload.bool("force_sync") ?: false
                )
                "ide.start_lint" -> startGradleJob(
                    kind = "lint",
                    tasks = payload.stringList("tasks").ifEmpty { listOf("lint") },
                    forceSync = payload.bool("force_sync") ?: false
                )
                "ide.get_job" -> {
                    val id = payload.requiredString("job_id")
                    val record = jobRecords[id]
                        ?: return failure("job_not_found", "Unknown IDE job: $id")
                    success(record.toJson())
                }
                "ide.cancel_job" -> {
                    val id = payload.requiredString("job_id")
                    runningJobs.remove(id)?.cancel()
                    runCatching { buildController.cancelCurrentBuild() }
                    jobRecords.computeIfPresent(id) { _, record ->
                        record.copy(
                            state = "CANCELLED",
                            finishedAt = System.currentTimeMillis()
                        )
                    }
                    publishJob(id)
                    success(buildJsonObject { put("cancelled", true); put("job_id", id) })
                }
                "ide.create_project" -> startCreateProjectJob(payload)
                "ide.open_project" -> {
                    openProject(payload.requiredString("path"))
                    success(buildJsonObject { put("ok", true) })
                }
                else -> failure("unknown_action", "Unsupported AndroidIDE action: " + request.name)
            }
        } catch (error: Exception) {
            failure("ide_error", error.message ?: error.javaClass.simpleName)
        }
    }

    override fun onRegisterEventListener(callback: IOmniEventCallback): Boolean =
        listeners.register(callback)

    override fun onUnregisterEventListener(callback: IOmniEventCallback) {
        listeners.unregister(callback)
    }

    private fun startGradleJob(
        kind: String,
        tasks: List<String>,
        forceSync: Boolean
    ): ActionOutcome {
        val root = OmniIdeStateBridge.projectRoot()
        return startJob(kind, buildJsonObject {
            put("tasks", buildJsonArray { tasks.forEach(::add) })
            put("project_root", root.absolutePath)
        }) { id ->
            val result = buildController.executeTasks(root, tasks, forceSync)
            val success = result.isSuccessful
            val outputTail = OmniIdeStateBridge.buildOutputSnapshot(100_000)
            val details = buildJsonObject {
                put("task_result", result.toString())
                put("build_output_tail", outputTail)
            }.toString()
            finishJob(
                id = id,
                success = success,
                result = details,
                error = if (success) null else outputTail.ifBlank { result.toString() }
            )
        }
    }

    private fun startSyncJob(force: Boolean): ActionOutcome {
        val root = OmniIdeStateBridge.projectRoot()
        return startJob("sync", buildJsonObject {
            put("project_root", root.absolutePath)
            put("force", force)
        }) { id ->
            val result = buildController.syncProject(root, force)
            val ok = result is dev.mutwakil.androidide.tooling.api.messages.result.InitializeResult.Success
            finishJob(id, ok, result.toString(), if (ok) null else result.toString())
        }
    }

    private fun startCreateProjectJob(payload: JsonObject): ActionOutcome {
        val projectName = payload.requiredString("project_name")
        val packageId = payload.string("package_id")
            ?: "com.example." + projectName.lowercase().replace(Regex("[^a-z0-9_]"), "")
        val requestedTemplate = payload.string("template") ?: "Compose Activity"
        val templateName = when (requestedTemplate.lowercase()) {
            "compose", "compose activity", "jetpack compose" -> "Compose Activity"
            "empty", "empty activity", "views" -> "Empty Activity"
            else -> requestedTemplate
        }
        val language = when ((payload.string("language") ?: "kotlin").lowercase()) {
            "java" -> LanguageType.JAVA
            else -> LanguageType.KOTLIN
        }
        val minSdk = (payload.int("min_sdk") ?: 24).coerceIn(21, 36)
        val useKts = payload.bool("use_kts") ?: true
        val saveLocation = payload.string("save_location")
            ?.let(::File)
            ?: Environment.PROJECTS_DIR
        val projectDir = File(saveLocation, projectName)

        require(!projectDir.exists()) {
            "Project already exists: " + projectDir.absolutePath
        }
        require(saveLocation.canonicalPath.startsWith("/storage/")) {
            "Project save location must be shared storage"
        }

        val template = TemplateRegistry.getTemplateByName(templateName)
            ?: throw IllegalArgumentException(
                "Unknown template '$templateName'. Available: " +
                    TemplateRegistry.getAllTemplates().joinToString { it.displayName }
            )

        return startJob("create_project", buildJsonObject {
            put("project_name", projectName)
            put("package_id", packageId)
            put("template", templateName)
            put("project_path", projectDir.absolutePath)
        }) { id ->
            template.create(
                applicationContext,
                null,
                TemplateOptions(
                    projectName = projectName,
                    packageId = packageId,
                    languageType = language,
                    minSdk = minSdk,
                    useKts = useKts,
                    saveLocation = saveLocation
                )
            )
            val ok = projectDir.exists() && File(projectDir, "settings.gradle.kts").let {
                it.exists() || File(projectDir, "settings.gradle").exists()
            }
            if (ok) {
                ProjectManagerImpl.getInstance().projectPath = projectDir.absolutePath
            }
            finishJob(
                id,
                ok,
                buildJsonObject {
                    put("project_path", projectDir.absolutePath)
                    put("created", ok)
                }.toString(),
                if (ok) null else "Template finished without a valid Gradle project"
            )
        }
    }

    private fun startJob(
        kind: String,
        input: JsonObject,
        block: suspend (String) -> Unit
    ): ActionOutcome {
        val id = "ide-" + UUID.randomUUID().toString()
        val record = IdeJobRecord(
            id = id,
            kind = kind,
            state = "QUEUED",
            startedAt = System.currentTimeMillis(),
            input = input.toString()
        )
        jobRecords[id] = record

        val job = scope.launch {
            jobRecords.computeIfPresent(id) { _, old -> old.copy(state = "RUNNING") }
            publishJob(id)
            try {
                block(id)
            } catch (error: Exception) {
                finishJob(
                    id = id,
                    success = false,
                    result = null,
                    error = error.message ?: error.javaClass.simpleName
                )
            } finally {
                runningJobs.remove(id)
            }
        }
        runningJobs[id] = job
        publishJob(id)

        return success(buildJsonObject {
            put("job_id", id)
            put("state", "QUEUED")
            put("kind", kind)
        })
    }

    private fun finishJob(
        id: String,
        success: Boolean,
        result: String?,
        error: String?
    ) {
        jobRecords.computeIfPresent(id) { _, old ->
            old.copy(
                state = if (success) "SUCCEEDED" else "FAILED",
                finishedAt = System.currentTimeMillis(),
                result = result?.take(120_000),
                error = error?.take(16_000)
            )
        }
        publishJob(id)
    }

    private fun publishJob(id: String) {
        val record = jobRecords[id] ?: return
        publish("ide.job.changed", record.toJson())
    }

    private fun publish(name: String, payload: JsonObject) {
        val event = json.encodeToString(OmniEvent(name, payload))
        val count = listeners.beginBroadcast()
        try {
            for (index in 0 until count) {
                runCatching { listeners.getBroadcastItem(index).onEvent(event) }
            }
        } finally {
            listeners.finishBroadcast()
        }
    }

    private fun openProject(path: String) {
        val root = File(path).canonicalFile
        require(root.exists() && root.isDirectory) { "Project directory not found: $path" }
        ProjectManagerImpl.getInstance().projectPath = root.absolutePath
        GeneralPreferences.lastOpenedProject = root.absolutePath
        startActivity(
            Intent(this, EditorActivityKt::class.java).apply {
                putExtra("PROJECT_PATH", root.absolutePath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    private fun capability(
        name: String,
        description: String,
        mode: CapabilityExecutionMode,
        streaming: Boolean = false
    ) = CapabilityDescriptor(
        name = name,
        description = description,
        executionMode = mode,
        supportsStreaming = streaming
    )

    private fun success(data: kotlinx.serialization.json.JsonElement): ActionOutcome =
        ActionOutcome.Success(data)

    private fun failure(code: String, message: String): ActionOutcome =
        ActionOutcome.Failure(ActionError(code, message))

    private fun JsonObject.string(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.requiredString(name: String): String =
        string(name)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing '$name'")

    private fun JsonObject.int(name: String): Int? =
        get(name)?.jsonPrimitive?.intOrNull

    private fun JsonObject.bool(name: String): Boolean? =
        get(name)?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.stringList(name: String): List<String> =
        runCatching {
            get(name)?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        }.getOrDefault(emptyList())

    private data class IdeJobRecord(
        val id: String,
        val kind: String,
        val state: String,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val input: String = "{}",
        val result: String? = null,
        val error: String? = null
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("job_id", id)
            put("kind", kind)
            put("state", state)
            put("started_at", startedAt)
            finishedAt?.let { put("finished_at", it) }
            put("input", input)
            result?.let { put("result", it) }
            error?.let { put("error", it) }
        }
    }
}
