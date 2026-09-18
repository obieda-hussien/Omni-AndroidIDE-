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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        private val PACKAGE_ID_REGEX =
            Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
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
    private val activeGradleJobId = AtomicReference<String?>(null)

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
        capability("ide.get_file_info", "File metadata, line count, dirty state and SHA-256 revision.", CapabilityExecutionMode.ASYNC),
        capability("ide.read_file", "Read a bounded project file snapshot with a SHA-256 revision.", CapabilityExecutionMode.ASYNC),
        capability("ide.read_lines", "Read an exact 1-based line range without transporting the whole file.", CapabilityExecutionMode.ASYNC),
        capability("ide.write_file", "Revision-safe whole-file replacement. Refuses dirty editor buffers and oversized Binder payloads.", CapabilityExecutionMode.ASYNC, destructive = true),
        capability("ide.apply_line_patch", "Apply one or more revision-safe line hunks against original 1-based line numbers.", CapabilityExecutionMode.ASYNC, destructive = true),
        capability("ide.search_text", "Search project text by literal or regex query with optional file glob and bounded results.", CapabilityExecutionMode.ASYNC),
        capability("ide.list_files", "List project files while skipping generated/cache/VCS trees.", CapabilityExecutionMode.ASYNC),
        capability("ide.open_file", "Open a project file in the AndroidIDE editor.", CapabilityExecutionMode.ASYNC),
        capability("ide.refresh_project", "Reload externally changed clean buffers.", CapabilityExecutionMode.ASYNC),

        capability("ide.get_build_output", "Read/filter the latest AndroidIDE build output by tail line count.", CapabilityExecutionMode.IMMEDIATE),
        capability("ide.get_ide_logs", "Read/filter recent AndroidIDE process logs.", CapabilityExecutionMode.ASYNC),
        capability("ide.get_app_logs", "Read/filter recent app-under-test logs captured through AndroidIDE LogSender.", CapabilityExecutionMode.IMMEDIATE),
        capability("ide.clear_app_logs", "Clear Omni's bounded app-log mirror without affecting AndroidIDE's normal Logs UI.", CapabilityExecutionMode.IMMEDIATE),
        capability("ide.get_diagnostics", "Return bounded project-sync and latest build diagnostics.", CapabilityExecutionMode.IMMEDIATE),
        capability("ide.get_active_diagnostics", "Run the active editor's native language-server analysis and return structured diagnostics.", CapabilityExecutionMode.ASYNC),
        capability("ide.health", "Inspect project/tooling/build/Git/logging readiness and IDE-owned Gradle integration health.", CapabilityExecutionMode.ASYNC),

        capability("ide.git_status", "Read native JGit status, branch, conflicts, staged/unstaged/untracked files and local-ahead count.", CapabilityExecutionMode.ASYNC),
        capability("ide.git_diff", "Read a bounded JGit diff for one project file.", CapabilityExecutionMode.ASYNC),
        capability("ide.git_history", "Read recent Git commit history.", CapabilityExecutionMode.ASYNC),
        capability("ide.git_branches", "List local and remote Git branches.", CapabilityExecutionMode.ASYNC),
        capability("ide.git_stage", "Stage selected project paths with AndroidIDE JGit.", CapabilityExecutionMode.ASYNC, destructive = true),
        capability("ide.git_commit", "Create a local Git commit from the current index.", CapabilityExecutionMode.ASYNC, destructive = true),
        capability("ide.git_checkout", "Checkout or create a local/tracking branch.", CapabilityExecutionMode.ASYNC, destructive = true),
        capability("ide.git_pull", "Pull a remote using AndroidIDE's encrypted stored Git credentials when available.", CapabilityExecutionMode.ASYNC, destructive = true),
        capability("ide.git_push", "Push to a remote using AndroidIDE's encrypted stored Git credentials; credentials never cross OmniLink.", CapabilityExecutionMode.ASYNC, destructive = true),
        capability("ide.git_merge", "Merge a branch into the current branch using native JGit.", CapabilityExecutionMode.ASYNC, destructive = true),
        capability("ide.git_abort_merge", "Abort the current conflicted merge and restore HEAD.", CapabilityExecutionMode.ASYNC, destructive = true),

        capability("ide.sync_project", "Synchronize project through AndroidIDE's Tooling API.", CapabilityExecutionMode.JOB, streaming = true),
        capability("ide.start_build", "Execute Gradle build tasks through AndroidIDE's Tooling API.", CapabilityExecutionMode.JOB, streaming = true),
        capability("ide.start_tests", "Execute project tests through AndroidIDE's Tooling API.", CapabilityExecutionMode.JOB, streaming = true),
        capability("ide.start_lint", "Execute Android lint through AndroidIDE's Tooling API.", CapabilityExecutionMode.JOB, streaming = true),
        capability("ide.get_job", "Read the state/result of an IDE job.", CapabilityExecutionMode.IMMEDIATE),
        capability("ide.list_jobs", "List recent Omni-started IDE jobs and terminal/running states.", CapabilityExecutionMode.IMMEDIATE),
        capability("ide.cancel_job", "Cancel an Omni-started IDE job and the current Gradle build.", CapabilityExecutionMode.ASYNC, destructive = true),
        capability("ide.create_project", "Create a project with AndroidIDE's own template engine.", CapabilityExecutionMode.JOB, streaming = true, destructive = true),
        capability("ide.open_project", "Set and open a project in AndroidIDE.", CapabilityExecutionMode.ASYNC, destructive = true)
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
                "ide.get_file_info" -> success(
                    OmniIdeWorkspaceBridge.fileInfo(payload.requiredString("path"))
                )
                "ide.read_lines" -> success(
                    OmniIdeWorkspaceBridge.readLines(
                        path = payload.requiredString("path"),
                        startLine = payload.int("start_line") ?: 1,
                        endLine = payload.int("end_line") ?: (payload.int("start_line") ?: 1),
                        includeLineNumbers = payload.bool("include_line_numbers") ?: true
                    )
                )
                "ide.apply_line_patch" -> {
                    val hunks = payload["hunks"]?.jsonArray?.map { element ->
                        val hunk = element.jsonObject
                        OmniIdeWorkspaceBridge.LineHunk(
                            startLine = hunk.int("start_line")
                                ?: throw IllegalArgumentException("Each hunk needs start_line"),
                            endLine = hunk.int("end_line")
                                ?: throw IllegalArgumentException("Each hunk needs end_line"),
                            replacement = hunk.string("replacement").orEmpty()
                        )
                    }.orEmpty()
                    success(
                        OmniIdeWorkspaceBridge.applyLinePatch(
                            path = payload.requiredString("path"),
                            expectedRevision = payload.string("expected_revision"),
                            hunks = hunks
                        )
                    )
                }
                "ide.search_text" -> success(
                    OmniIdeWorkspaceBridge.searchText(
                        query = payload.requiredString("query"),
                        relativeDir = payload.string("path").orEmpty(),
                        regex = payload.bool("regex") ?: false,
                        caseSensitive = payload.bool("case_sensitive") ?: false,
                        fileGlob = payload.string("file_glob"),
                        maxResults = payload.int("limit") ?: 100
                    )
                )
                "ide.get_build_output" -> success(
                    OmniIdeWorkspaceBridge.buildOutput(
                        lines = payload.int("lines") ?: 250,
                        query = payload.string("query")
                    )
                )
                "ide.get_ide_logs" -> success(
                    OmniIdeWorkspaceBridge.ideLogs(
                        lines = payload.int("lines") ?: 250,
                        query = payload.string("query")
                    )
                )
                "ide.get_app_logs" -> success(
                    OmniIdeWorkspaceBridge.appLogs(
                        lines = payload.int("lines") ?: 250,
                        query = payload.string("query")
                    )
                )
                "ide.clear_app_logs" -> {
                    OmniIdeObservabilityBridge.clearAppLogs()
                    success(buildJsonObject { put("cleared", true) })
                }
                "ide.get_diagnostics" -> success(
                    OmniIdeWorkspaceBridge.diagnostics(payload.int("limit") ?: 120)
                )
                "ide.get_active_diagnostics" -> success(
                    OmniIdeWorkspaceBridge.activeLspDiagnostics(payload.int("limit") ?: 120)
                )
                "ide.health" -> success(OmniIdeWorkspaceBridge.health())
                "ide.git_status" -> success(OmniIdeWorkspaceBridge.gitStatus())
                "ide.git_diff" -> success(
                    OmniIdeWorkspaceBridge.gitDiff(payload.requiredString("path"))
                )
                "ide.git_history" -> success(
                    OmniIdeWorkspaceBridge.gitHistory(payload.int("limit") ?: 30)
                )
                "ide.git_branches" -> success(OmniIdeWorkspaceBridge.gitBranches())
                "ide.git_stage" -> success(
                    OmniIdeWorkspaceBridge.gitStage(payload.stringList("paths"))
                )
                "ide.git_commit" -> success(
                    OmniIdeWorkspaceBridge.gitCommit(
                        message = payload.requiredString("message"),
                        authorName = payload.string("author_name"),
                        authorEmail = payload.string("author_email")
                    )
                )
                "ide.git_checkout" -> success(
                    OmniIdeWorkspaceBridge.gitCheckout(
                        branch = payload.requiredString("branch"),
                        createNew = payload.bool("create_new") ?: false,
                        startPoint = payload.string("start_point")
                    )
                )
                "ide.git_pull" -> success(
                    OmniIdeWorkspaceBridge.gitPull(payload.string("remote").orEmpty())
                )
                "ide.git_push" -> success(
                    OmniIdeWorkspaceBridge.gitPush(payload.string("remote").orEmpty())
                )
                "ide.git_merge" -> success(
                    OmniIdeWorkspaceBridge.gitMerge(payload.requiredString("branch"))
                )
                "ide.git_abort_merge" -> success(OmniIdeWorkspaceBridge.gitAbortMerge())
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
                "ide.list_jobs" -> {
                    val limit = (payload.int("limit") ?: 40).coerceIn(1, 100)
                    val recent = jobRecords.values
                        .sortedByDescending { it.startedAt }
                        .take(limit)
                    success(buildJsonObject {
                        put("jobs", buildJsonArray { recent.forEach { add(it.toJson()) } })
                        put("count", recent.size)
                    })
                }
                "ide.cancel_job" -> {
                    val id = payload.requiredString("job_id")
                    val record = jobRecords[id]
                        ?: return failure("job_not_found", "Unknown IDE job: $id")
                    if (record.state != "QUEUED" && record.state != "RUNNING") {
                        return failure(
                            "job_not_running",
                            "IDE job $id is already ${record.state}"
                        )
                    }

                    val isActiveGradleJob = activeGradleJobId.get() == id
                    jobRecords.computeIfPresent(id) { _, current ->
                        current.copy(
                            state = "CANCELLED",
                            finishedAt = System.currentTimeMillis()
                        )
                    }
                    runningJobs.remove(id)?.cancel()
                    if (isActiveGradleJob) {
                        runCatching { buildController.cancelCurrentBuild() }
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
            put("tasks", buildJsonArray {
                tasks.forEach { task -> add(JsonPrimitive(task)) }
            })
            put("project_root", root.absolutePath)
        }, exclusiveGradle = true) { id ->
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
        }, exclusiveGradle = true) { id ->
            val result = buildController.syncProject(root, force)
            val ok = result is dev.mutwakil.androidide.tooling.api.messages.result.InitializeResult.Success
            finishJob(id, ok, result.toString(), if (ok) null else result.toString())
        }
    }

    private fun startCreateProjectJob(payload: JsonObject): ActionOutcome {
        val projectName = payload.requiredString("project_name").trim()
        require(projectName.length in 1..80) { "Project name must be 1..80 characters" }
        require(
            projectName != "." &&
                projectName != ".." &&
                '/' !in projectName &&
                '\\' !in projectName
        ) { "Project name must not contain path separators or traversal segments" }

        val fallbackSegment = projectName.lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .trim('_')
            .ifBlank { "app" }
            .let { if (it.first().isDigit()) "_$it" else it }
        val packageId = payload.string("package_id") ?: "com.example.$fallbackSegment"
        require(PACKAGE_ID_REGEX.matches(packageId)) {
            "Invalid package id '$packageId'"
        }
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
        require(templateName != "Compose Activity" || language == LanguageType.KOTLIN) {
            "Compose Activity requires Kotlin"
        }
        val minSdk = (payload.int("min_sdk") ?: 24).coerceIn(21, 36)
        val useKts = payload.bool("use_kts") ?: true
        val saveLocation = (payload.string("save_location")
            ?.let(::File)
            ?: Environment.PROJECTS_DIR).canonicalFile
        require(saveLocation.path.startsWith("/storage/")) {
            "Project save location must be shared storage"
        }
        val projectDir = File(saveLocation, projectName).canonicalFile
        require(projectDir.parentFile?.canonicalFile == saveLocation) {
            "Project path must remain directly inside the selected save location"
        }
        require(!projectDir.exists()) {
            "Project already exists: " + projectDir.absolutePath
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
        exclusiveGradle: Boolean = false,
        block: suspend (String) -> Unit
    ): ActionOutcome {
        val id = "ide-" + UUID.randomUUID().toString()
        if (exclusiveGradle && !activeGradleJobId.compareAndSet(null, id)) {
            return failure(
                "gradle_busy",
                "Another AndroidIDE Gradle/sync job is already active: " +
                    (activeGradleJobId.get() ?: "unknown")
            )
        }

        val record = IdeJobRecord(
            id = id,
            kind = kind,
            state = "QUEUED",
            startedAt = System.currentTimeMillis(),
            input = input.toString()
        )
        jobRecords[id] = record

        val job = scope.launch(start = CoroutineStart.LAZY) {
            jobRecords.computeIfPresent(id) { _, old -> old.copy(state = "RUNNING") }
            publishJob(id)
            try {
                block(id)
            } catch (cancelled: CancellationException) {
                jobRecords.computeIfPresent(id) { _, current ->
                    if (current.state == "CANCELLED") current
                    else current.copy(
                        state = "CANCELLED",
                        finishedAt = System.currentTimeMillis()
                    )
                }
                publishJob(id)
                throw cancelled
            } catch (error: Exception) {
                finishJob(
                    id = id,
                    success = false,
                    result = null,
                    error = error.message ?: error.javaClass.simpleName
                )
            } finally {
                runningJobs.remove(id)
                if (exclusiveGradle) {
                    activeGradleJobId.compareAndSet(id, null)
                }
            }
        }
        runningJobs[id] = job
        publishJob(id)
        job.start()

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
        val event = json.encodeToString(OmniEvent.serializer(), OmniEvent(name, payload))
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
        streaming: Boolean = false,
        destructive: Boolean = false,
        requiresConfirmation: Boolean = destructive
    ) = CapabilityDescriptor(
        name = name,
        description = description,
        destructive = destructive,
        requiresConfirmation = requiresConfirmation,
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
