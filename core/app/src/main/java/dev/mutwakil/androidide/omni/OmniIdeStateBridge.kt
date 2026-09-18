package dev.mutwakil.androidide.omni

import dev.mutwakil.androidide.activities.editor.EditorHandlerActivity
import dev.mutwakil.androidide.lookup.Lookup
import dev.mutwakil.androidide.projects.ProjectManagerImpl
import dev.mutwakil.androidide.projects.builder.BuildService
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Process-local bridge from headless Omni IPC into AndroidIDE's live editor state.
 *
 * A WeakReference avoids retaining the editor Activity. File mutations are revision checked and never
 * overwrite a dirty editor buffer. External edits are then reloaded through AndroidIDE's own refresh path.
 */
object OmniIdeStateBridge {
    private const val MAX_BUILD_OUTPUT_CHARS = 180_000
    private const val MAX_CONTEXT_BUILD_OUTPUT_CHARS = 40_000
    private const val MAX_ACTIVE_DOCUMENT_CHARS = 120_000
    private const val MAX_FILE_CONTENT_CHARS = 220_000
    private const val MAX_WRITE_CONTENT_CHARS = 220_000
    private const val MAX_LIST_FILES = 1_500
    private const val MAX_LIST_PATH_CHARS = 120_000

    private val outputLock = Any()
    private val buildOutput = StringBuilder()
    private val outputListeners = CopyOnWriteArraySet<(String) -> Unit>()

    @Volatile
    private var editorRef = WeakReference<EditorHandlerActivity>(null)

    fun attach(activity: EditorHandlerActivity) {
        editorRef = WeakReference(activity)
    }

    fun detach(activity: EditorHandlerActivity) {
        if (editorRef.get() === activity) editorRef.clear()
    }

    fun activeActivity(): EditorHandlerActivity? = editorRef.get()

    fun appendBuildOutput(chunk: String) {
        if (chunk.isEmpty()) return
        synchronized(outputLock) {
            buildOutput.append(chunk)
            if (buildOutput.length > MAX_BUILD_OUTPUT_CHARS) {
                buildOutput.delete(0, buildOutput.length - MAX_BUILD_OUTPUT_CHARS)
            }
        }
        outputListeners.forEach { listener -> runCatching { listener(chunk) } }
    }

    fun buildOutputSnapshot(maxChars: Int = 80_000): String = synchronized(outputLock) {
        val start = (buildOutput.length - maxChars.coerceAtLeast(1)).coerceAtLeast(0)
        buildOutput.substring(start)
    }

    fun addBuildOutputListener(listener: (String) -> Unit) {
        outputListeners += listener
    }

    fun removeBuildOutputListener(listener: (String) -> Unit) {
        outputListeners -= listener
    }

    suspend fun collectContext(): JsonObject {
        val manager = ProjectManagerImpl.getInstance()
        val projectRoot = manager.projectDirPath
        val active = activeDocument()
        val service = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)

        return buildJsonObject {
            put("projectRoot", projectRoot)
            put("projectInitialized", manager.workspace != null)
            put("buildInProgress", service?.isBuildInProgress == true)
            put("toolingServerStarted", service?.isToolingServerStarted() == true)
            put("buildOutputTail", buildOutputSnapshot(MAX_CONTEXT_BUILD_OUTPUT_CHARS))
            put("syncIssues", buildJsonArray {
                manager.projectSyncIssues.take(100).forEach { issue ->
                    add(JsonPrimitive(issue.toString()))
                }
            })
            put("modules", buildJsonArray {
                manager.workspace?.subProjects?.take(100)?.forEach { module ->
                    add(buildJsonObject {
                        put("name", module.name)
                        put("path", module.path)
                        val relativeModuleDir = module.path
                            .trim(':')
                            .replace(':', File.separatorChar)
                        val moduleDir = if (relativeModuleDir.isBlank()) {
                            File(projectRoot)
                        } else {
                            File(projectRoot, relativeModuleDir)
                        }
                        put("projectDir", moduleDir.absolutePath)
                    })
                }
            })
            active?.let { put("activeDocument", it) }
        }
    }

    suspend fun activeDocument(): JsonObject? = withContext(Dispatchers.Main.immediate) {
        val view = activeActivity()?.getCurrentEditor() ?: return@withContext null
        val editor = view.editor ?: return@withContext null
        val file = editor.file ?: return@withContext null
        val content = editor.text?.toString().orEmpty()
        buildJsonObject {
            put("path", file.absolutePath)
            put("relativePath", relativeToProject(file))
            put("dirty", view.isModified)
            put("revision", sha256(content))
            put("content", content.take(MAX_ACTIVE_DOCUMENT_CHARS))
            put("contentTruncated", content.length > MAX_ACTIVE_DOCUMENT_CHARS)
        }
    }

    private suspend fun openDocument(file: File): JsonObject? =
        withContext(Dispatchers.Main.immediate) {
            val view = activeActivity()?.getEditorForFile(file) ?: return@withContext null
            val editor = view.editor ?: return@withContext null
            val editorFile = editor.file ?: return@withContext null
            val content = editor.text?.toString().orEmpty()
            buildJsonObject {
                put("path", editorFile.absolutePath)
                put("relativePath", relativeToProject(editorFile))
                put("dirty", view.isModified)
                put("revision", sha256(content))
                put("content", content.take(MAX_ACTIVE_DOCUMENT_CHARS))
                put("contentTruncated", content.length > MAX_ACTIVE_DOCUMENT_CHARS)
                put("totalChars", content.length)
            }
        }

    suspend fun readFile(path: String): JsonObject {
        val file = resolveProjectPath(path)
        openDocument(file)?.let { return it }

        val content = withContext(Dispatchers.IO) { file.readText() }
        return buildJsonObject {
            put("path", file.absolutePath)
            put("relativePath", relativeToProject(file))
            put("dirty", false)
            put("revision", sha256(content))
            put("content", content.take(MAX_FILE_CONTENT_CHARS))
            put("contentTruncated", content.length > MAX_FILE_CONTENT_CHARS)
            put("totalChars", content.length)
        }
    }

    suspend fun writeFile(
        path: String,
        content: String,
        expectedRevision: String?
    ): JsonObject {
        require(content.length <= MAX_WRITE_CONTENT_CHARS) {
            "File replacement is too large for safe Binder transport (" +
                content.length + " chars; max " + MAX_WRITE_CONTENT_CHARS +
                "). Use targeted edits or split the change into smaller operations."
        }

        val file = resolveProjectPath(path)
        val open = openDocument(file)
        val openDirty = open?.get("dirty")?.toString()?.toBooleanStrictOrNull() == true

        if (openDirty) {
            throw IllegalStateException(
                "Refusing to overwrite a dirty editor buffer. Save/reconcile the document first."
            )
        }

        val before = if (open != null) {
            open["revision"]?.toString()?.trim('"').orEmpty()
        } else if (file.exists()) {
            withContext(Dispatchers.IO) { sha256(file.readText()) }
        } else {
            sha256("")
        }

        if (!expectedRevision.isNullOrBlank() && expectedRevision != before) {
            throw IllegalStateException(
                "revision_conflict: expected=$expectedRevision actual=$before"
            )
        }

        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }

        withContext(Dispatchers.Main.immediate) {
            activeActivity()?.checkForExternalFileChanges(force = true)
        }

        return buildJsonObject {
            put("ok", true)
            put("path", file.absolutePath)
            put("relativePath", relativeToProject(file))
            put("previousRevision", before)
            put("revision", sha256(content))
        }
    }

    suspend fun listFiles(relativeDir: String = "", limit: Int = 500): JsonObject {
        val root = resolveProjectPath(relativeDir.ifBlank { "." })
        if (!root.isDirectory) throw IllegalArgumentException("Not a directory: $relativeDir")
        val capped = limit.coerceIn(1, MAX_LIST_FILES)
        val project = projectRoot()

        val (paths, truncatedByBudget) = withContext(Dispatchers.IO) {
            val result = ArrayList<String>(minOf(capped, 256))
            var pathChars = 0
            var budgetExceeded = false
            val iterator = root.walkTopDown()
                .onEnter { dir -> dir.name !in setOf(".gradle", "build", ".git") }
                .filter { it.isFile }
                .iterator()

            while (iterator.hasNext() && result.size < capped) {
                val relative = iterator.next().relativeTo(project).invariantSeparatorsPath
                if (pathChars + relative.length > MAX_LIST_PATH_CHARS) {
                    budgetExceeded = true
                    break
                }
                result += relative
                pathChars += relative.length
            }
            result to budgetExceeded
        }
        return buildJsonObject {
            put("root", root.absolutePath)
            put("count", paths.size)
            put("files", buildJsonArray {
                paths.forEach { path -> add(JsonPrimitive(path)) }
            })
            put("truncated", truncatedByBudget || paths.size >= capped)
        }
    }

    suspend fun openFile(path: String) {
        val file = resolveProjectPath(path)
        withContext(Dispatchers.Main.immediate) {
            val activity = activeActivity()
                ?: throw IllegalStateException("No active AndroidIDE editor")
            activity.openFile(file, null)
        }
    }

    suspend fun requestSync() {
        withContext(Dispatchers.Main.immediate) {
            val activity = activeActivity()
                ?: throw IllegalStateException("No active AndroidIDE editor")
            activity.initializeProject(forceSync = true)
        }
    }

    suspend fun refreshOpenEditors() {
        withContext(Dispatchers.Main.immediate) {
            activeActivity()?.checkForExternalFileChanges(force = true)
        }
    }

    fun resolveProjectPath(path: String): File {
        val root = projectRoot().canonicalFile
        val candidate = if (File(path).isAbsolute) File(path) else File(root, path)
        val canonical = candidate.canonicalFile
        val allowed = canonical == root || canonical.path.startsWith(root.path + File.separator)
        require(allowed) { "Path escapes current project root: $path" }
        return canonical
    }

    fun projectRoot(): File {
        val path = ProjectManagerImpl.getInstance().projectDirPath
        require(path.isNotBlank()) { "No AndroidIDE project is open" }
        val root = File(path)
        require(root.exists() && root.isDirectory) { "Project directory is unavailable: $path" }
        return root
    }

    private fun relativeToProject(file: File): String =
        runCatching { file.relativeTo(projectRoot()).invariantSeparatorsPath }
            .getOrDefault(file.name)

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
