package dev.mutwakil.androidide.omni

import android.os.Process
import dev.mutwakil.androidide.git.core.GitRepository
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import dev.mutwakil.androidide.utils.Environment
import dev.mutwakil.androidide.projects.builder.BuildService
import dev.mutwakil.androidide.lookup.Lookup
import dev.mutwakil.androidide.git.core.GitCredentialsManager
import dev.mutwakil.androidide.buildinfo.BuildInfo
import dev.mutwakil.androidide.app.IDEApplication
import dev.mutwakil.androidide.git.core.GitRepositoryManager
import dev.mutwakil.androidide.projects.ProjectManagerImpl
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Native project intelligence exposed to Omni through OmniLink.
 *
 * This deliberately uses AndroidIDE's own project/editor/Git/build/log infrastructure instead of
 * shell wrappers. Results are bounded so a single Binder transaction cannot grow without limit.
 */
object OmniIdeWorkspaceBridge {
    private const val MAX_FILE_BYTES_FOR_SEARCH = 2L * 1024L * 1024L
    private const val MAX_SEARCH_RESULTS = 250
    private const val MAX_TEXT_RESULT_CHARS = 220_000
    private const val MAX_GIT_DIFF_CHARS = 220_000
    private const val MAX_LOG_CHARS = 180_000

    private data class TextSnapshot(
        val file: File,
        val text: String,
        val dirty: Boolean
    )

    suspend fun readLines(
        path: String,
        startLine: Int,
        endLine: Int,
        includeLineNumbers: Boolean
    ): JsonObject {
        require(startLine >= 1) { "start_line must be >= 1" }
        require(endLine >= startLine) { "end_line must be >= start_line" }
        val snapshot = snapshot(path)
        val lines = snapshot.text.split('\n')
        val from = (startLine - 1).coerceAtMost(lines.size)
        val to = endLine.coerceAtMost(lines.size)
        val selected = if (from >= to) emptyList() else lines.subList(from, to)
        val rendered = if (includeLineNumbers) {
            selected.mapIndexed { index, line -> "${startLine + index}: ${line}" }
                .joinToString("\n")
        } else {
            selected.joinToString("\n")
        }
        return buildJsonObject {
            put("path", snapshot.file.absolutePath)
            put("relativePath", relative(snapshot.file))
            put("startLine", startLine)
            put("endLine", if (selected.isEmpty()) startLine - 1 else startLine + selected.size - 1)
            put("totalLines", lines.size)
            put("dirty", snapshot.dirty)
            put("revision", OmniIdeStateBridge.sha256(snapshot.text))
            put("content", rendered.take(MAX_TEXT_RESULT_CHARS))
            put("truncated", rendered.length > MAX_TEXT_RESULT_CHARS)
        }
    }

    suspend fun applyLinePatch(
        path: String,
        expectedRevision: String?,
        hunks: List<LineHunk>
    ): JsonObject {
        require(hunks.isNotEmpty()) { "At least one patch hunk is required" }
        val snapshot = snapshot(path)
        val actualRevision = OmniIdeStateBridge.sha256(snapshot.text)
        if (!expectedRevision.isNullOrBlank() && expectedRevision != actualRevision) {
            throw IllegalStateException(
                "revision_conflict: expected=$expectedRevision actual=$actualRevision"
            )
        }
        if (snapshot.dirty) {
            throw IllegalStateException(
                "Refusing to patch a dirty editor buffer. Save/reconcile the document first."
            )
        }

        val lines = snapshot.text.split('\n').toMutableList()
        val sorted = hunks.sortedByDescending { it.startLine }
        var previousStart = Int.MAX_VALUE

        for (hunk in sorted) {
            require(hunk.startLine >= 1) { "start_line must be >= 1" }
            require(hunk.endLine >= hunk.startLine - 1) {
                "end_line must be >= start_line - 1"
            }
            require(hunk.startLine <= lines.size + 1) {
                "start_line ${hunk.startLine} is beyond file length ${lines.size}"
            }
            require(hunk.endLine <= lines.size) {
                "end_line ${hunk.endLine} is beyond file length ${lines.size}"
            }
            require(hunk.endLine < previousStart) { "Patch hunks overlap" }
            previousStart = hunk.startLine

            val startIndex = hunk.startLine - 1
            val deleteCount = if (hunk.endLine < hunk.startLine) 0 else hunk.endLine - hunk.startLine + 1
            repeat(deleteCount) { lines.removeAt(startIndex) }

            val replacementLines = if (hunk.replacement.isEmpty()) {
                emptyList()
            } else {
                hunk.replacement.split('\n')
            }
            lines.addAll(startIndex, replacementLines)
        }

        val updated = lines.joinToString("\n")
        return OmniIdeStateBridge.writeFile(
            path = snapshot.file.absolutePath,
            content = updated,
            expectedRevision = actualRevision
        )
    }

    suspend fun searchText(
        query: String,
        relativeDir: String,
        regex: Boolean,
        caseSensitive: Boolean,
        fileGlob: String?,
        maxResults: Int
    ): JsonObject {
        require(query.isNotEmpty()) { "query is required" }
        val root = OmniIdeStateBridge.resolveProjectPath(relativeDir.ifBlank { "." })
        require(root.isDirectory) { "Search root is not a directory: $relativeDir" }
        val limit = maxResults.coerceIn(1, MAX_SEARCH_RESULTS)
        val flags = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val matcher = if (regex) Regex(query, flags) else null
        val globRegex = fileGlob?.takeIf { it.isNotBlank() }?.let(::globToRegex)

        val results = withContext(Dispatchers.IO) {
            val out = ArrayList<JsonObject>(minOf(limit, 64))
            val iterator = root.walkTopDown()
                .onEnter { dir -> dir.name !in SKIPPED_DIRS }
                .filter { file ->
                    file.isFile &&
                        file.length() <= MAX_FILE_BYTES_FOR_SEARCH &&
                        !isLikelyBinary(file) &&
                        (globRegex == null || globRegex.matches(file.name))
                }
                .iterator()

            while (iterator.hasNext() && out.size < limit) {
                val file = iterator.next()
                val lines = runCatching { file.readLines() }.getOrElse { continue }
                for ((index, line) in lines.withIndex()) {
                    val ranges = if (matcher != null) {
                        matcher.findAll(line).map { it.range }.toList()
                    } else {
                        literalRanges(line, query, caseSensitive)
                    }
                    for (range in ranges) {
                        out += buildJsonObject {
                            put("path", relative(file))
                            put("line", index + 1)
                            put("column", range.first + 1)
                            put("preview", line.take(1_200))
                        }
                        if (out.size >= limit) break
                    }
                    if (out.size >= limit) break
                }
            }
            out
        }

        return buildJsonObject {
            put("query", query)
            put("root", relative(root))
            put("count", results.size)
            put("limit", limit)
            put("results", buildJsonArray { results.forEach(::add) })
            put("truncated", results.size >= limit)
        }
    }

    suspend fun fileInfo(path: String): JsonObject {
        val snapshot = snapshot(path)
        val lines = snapshot.text.count { it == '\n' } + 1
        return buildJsonObject {
            put("path", snapshot.file.absolutePath)
            put("relativePath", relative(snapshot.file))
            put("exists", snapshot.file.exists())
            put("sizeBytes", snapshot.file.length())
            put("lastModified", snapshot.file.lastModified())
            put("lineCount", lines)
            put("dirty", snapshot.dirty)
            put("revision", OmniIdeStateBridge.sha256(snapshot.text))
        }
    }

    fun buildOutput(lines: Int, query: String?): JsonObject {
        val raw = OmniIdeStateBridge.buildOutputSnapshot(MAX_LOG_CHARS)
        val filtered = filterTail(raw, lines, query)
        return buildJsonObject {
            put("content", filtered)
            put("requestedLines", lines)
            put("query", query.orEmpty())
            put("availableChars", raw.length)
            put("buildInProgress",
                dev.mutwakil.androidide.lookup.Lookup.getDefault()
                    .lookup(dev.mutwakil.androidide.projects.builder.BuildService.KEY_BUILD_SERVICE)
                    ?.isBuildInProgress == true
            )
        }
    }

    suspend fun ideLogs(lines: Int, query: String?): JsonObject = withContext(Dispatchers.IO) {
        val maxLines = lines.coerceIn(1, 2_000)
        val command = listOf(
            "logcat",
            "--pid=${Process.myPid()}",
            "-d",
            "-v",
            "threadtime",
            "-t",
            maxLines.toString()
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
        val exited = runCatching { process.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!exited) process.destroy()
        val filtered = filterTail(text.takeLast(MAX_LOG_CHARS), maxLines, query)
        buildJsonObject {
            put("pid", Process.myPid())
            put("content", filtered)
            put("query", query.orEmpty())
        }
    }

    fun appLogs(lines: Int, query: String?): JsonObject {
        val raw = OmniIdeObservabilityBridge.appLogsSnapshot(MAX_LOG_CHARS)
        return buildJsonObject {
            put("content", filterTail(raw, lines, query))
            put("query", query.orEmpty())
            put("availableChars", raw.length)
            put("source", "AndroidIDE LogSender")
        }
    }

    fun diagnostics(limit: Int): JsonObject {
        val max = limit.coerceIn(1, 250)
        val buildLines = OmniIdeStateBridge.buildOutputSnapshot(160_000)
            .lineSequence()
            .filter { line ->
                val lower = line.lowercase()
                lower.contains(" error") ||
                    lower.startsWith("e:") ||
                    lower.contains("warning") ||
                    lower.contains("exception") ||
                    lower.contains("failed")
            }
            .toList()
            .takeLast(max)

        val syncIssues = ProjectManagerImpl.getInstance().projectSyncIssues
            .take(max)
            .map { it.toString() }

        return buildJsonObject {
            put("syncIssues", buildJsonArray {
                syncIssues.forEach { add(JsonPrimitive(it.take(2_000))) }
            })
            put("buildDiagnostics", buildJsonArray {
                buildLines.forEach { add(JsonPrimitive(it.take(2_000))) }
            })
            put("count", syncIssues.size + buildLines.size)
        }
    }

    suspend fun gitStatus(): JsonObject = withRepository { repo ->
        val status = repo.getStatus()
        buildJsonObject {
            put("isClean", status.isClean)
            put("hasConflicts", status.hasConflicts)
            put("isMerging", status.isMerging)
            put("branch", repo.getCurrentBranch()?.name.orEmpty())
            put("localCommitsAhead", repo.getLocalCommitsCount())
            put("staged", changes(status.staged))
            put("unstaged", changes(status.unstaged))
            put("untracked", changes(status.untracked))
            put("conflicted", changes(status.conflicted))
        }
    }

    suspend fun gitDiff(path: String): JsonObject = withRepository { repo ->
        val file = OmniIdeStateBridge.resolveProjectPath(path)
        val diff = repo.getDiff(file)
        buildJsonObject {
            put("path", relative(file))
            put("diff", diff.take(MAX_GIT_DIFF_CHARS))
            put("truncated", diff.length > MAX_GIT_DIFF_CHARS)
        }
    }

    suspend fun gitHistory(limit: Int): JsonObject = withRepository { repo ->
        val commits = repo.getHistory(limit.coerceIn(1, 100))
        buildJsonObject {
            put("commits", buildJsonArray {
                commits.forEach { commit ->
                    add(buildJsonObject {
                        put("hash", commit.hash)
                        put("shortHash", commit.shortHash)
                        put("authorName", commit.authorName)
                        put("authorEmail", commit.authorEmail)
                        put("message", commit.message.take(2_000))
                        put("timestamp", commit.timestamp)
                        put("pushed", commit.hasBeenPushed)
                    })
                }
            })
        }
    }

    suspend fun gitBranches(): JsonObject = withRepository { repo ->
        buildJsonObject {
            put("branches", buildJsonArray {
                repo.getBranches().forEach { branch ->
                    add(buildJsonObject {
                        put("name", branch.name)
                        put("fullName", branch.fullName)
                        put("current", branch.isCurrent)
                        put("remote", branch.isRemote)
                        branch.remoteName?.let { put("remoteName", it) }
                    })
                }
            })
        }
    }

    suspend fun gitStage(paths: List<String>): JsonObject = withRepository { repo ->
        require(paths.isNotEmpty()) { "paths is required" }
        val files = paths.map(OmniIdeStateBridge::resolveProjectPath)
        repo.stageFiles(files)
        buildJsonObject {
            put("staged", buildJsonArray {
                files.forEach { add(JsonPrimitive(relative(it))) }
            })
        }
    }

    suspend fun gitCommit(message: String, authorName: String?, authorEmail: String?): JsonObject =
        withRepository { repo ->
            require(message.isNotBlank()) { "commit message is required" }
            val commit = repo.commit(message, authorName, authorEmail)
            buildJsonObject {
                put("created", commit != null)
                commit?.let {
                    put("hash", it.hash)
                    put("shortHash", it.shortHash)
                    put("message", it.message)
                }
            }
        }

    suspend fun gitCheckout(branch: String, createNew: Boolean, startPoint: String?): JsonObject =
        withRepository { repo ->
            require(branch.isNotBlank()) { "branch is required" }
            val checkedOut = repo.checkout(branch, createNew, startPoint)
            buildJsonObject { put("branch", checkedOut) }
        }

    suspend fun gitPull(remote: String): JsonObject = withRepository { repo ->
        val result = repo.pull(remote.ifBlank { "origin" }, storedCredentialsOrNull())
        buildJsonObject {
            put("successful", result.isSuccessful)
            put("result", result.toString().take(8_000))
        }
    }

    suspend fun gitPush(remote: String): JsonObject = withRepository { repo ->
        val credentials = storedCredentialsOrNull()
            ?: throw IllegalStateException(
                "No Git credentials are stored in AndroidIDE. Configure Git credentials first."
            )
        val results = repo.push(remote.ifBlank { "origin" }, credentials).toList()
        buildJsonObject {
            put("remote", remote.ifBlank { "origin" })
            put("results", buildJsonArray {
                results.forEach { result -> add(JsonPrimitive(result.toString().take(8_000))) }
            })
            put("count", results.size)
        }
    }

    suspend fun gitMerge(branch: String): JsonObject = withRepository { repo ->
        require(branch.isNotBlank()) { "branch is required" }
        val result = repo.merge(branch)
        buildJsonObject {
            put("branch", branch)
            put("status", result.mergeStatus?.toString().orEmpty())
            put("result", result.toString().take(12_000))
        }
    }

    suspend fun gitAbortMerge(): JsonObject = withRepository { repo ->
        repo.abortMerge()
        buildJsonObject { put("aborted", true) }
    }

    suspend fun health(): JsonObject {
        val root = runCatching { OmniIdeStateBridge.projectRoot() }.getOrNull()
        val service = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
        val git = if (root != null) GitRepositoryManager.isRepository(root) else false
        val active = runCatching { OmniIdeStateBridge.activeDocument() }.getOrNull()
        return buildJsonObject {
            put("ideVersion", BuildInfo.VERSION_NAME_SIMPLE)
            put("projectOpen", root != null)
            root?.let { put("projectRoot", it.absolutePath) }
            put("activeDocumentOpen", active != null)
            put("toolingServiceAvailable", service != null)
            put("toolingServerStarted", service?.isToolingServerStarted() == true)
            put("buildInProgress", service?.isBuildInProgress == true)
            put("gitRepository", git)
            put("appLogBufferChars", OmniIdeObservabilityBridge.appLogsSnapshot(MAX_LOG_CHARS).length)
            put("initScriptExists", Environment.INIT_SCRIPT?.isFile == true)
            put("gradlePluginExists", Environment.ANDROIDIDE_GRADLE_PLUGIN_JAR?.isFile == true)
        }
    }

    suspend fun activeLspDiagnostics(limit: Int): JsonObject {
        val max = limit.coerceIn(1, 250)
        val triple = withContext(Dispatchers.Main.immediate) {
            val view = OmniIdeStateBridge.activeActivity()?.getCurrentEditor()
                ?: return@withContext null
            val editor = view.editor ?: return@withContext null
            val file = editor.file ?: return@withContext null
            val server = editor.languageServer ?: return@withContext null
            Triple(file, server, view.isModified)
        }
        if (triple == null) {
            return buildJsonObject {
                put("available", false)
                put("diagnostics", buildJsonArray {})
            }
        }
        val (file, server, dirty) = triple
        val result = runCatching { server.analyze(file.toPath()) }.getOrElse {
            return buildJsonObject {
                put("available", false)
                put("path", relative(file))
                put("error", it.message ?: it.javaClass.simpleName)
                put("diagnostics", buildJsonArray {})
            }
        }
        val items = result.diagnostics.take(max)
        return buildJsonObject {
            put("available", true)
            put("path", relative(file))
            put("dirty", dirty)
            put("count", items.size)
            put("diagnostics", buildJsonArray {
                items.forEach { item ->
                    add(buildJsonObject {
                        put("severity", item.severity.name)
                        put("message", item.message.take(2_000))
                        put("code", item.code.take(300))
                        put("source", item.source.take(300))
                        put("startLine", item.range.start.line + 1)
                        put("startColumn", item.range.start.column + 1)
                        put("endLine", item.range.end.line + 1)
                        put("endColumn", item.range.end.column + 1)
                    })
                }
            })
            put("truncated", result.diagnostics.size > max)
        }
    }

    data class LineHunk(
        val startLine: Int,
        val endLine: Int,
        val replacement: String
    )

    private suspend fun snapshot(path: String): TextSnapshot {
        val file = OmniIdeStateBridge.resolveProjectPath(path)
        val active = withContext(Dispatchers.Main.immediate) {
            val view = OmniIdeStateBridge.activeActivity()?.getCurrentEditor()
            val editor = view?.editor
            val editorFile = editor?.file
            if (editorFile != null &&
                runCatching { editorFile.canonicalFile == file.canonicalFile }.getOrDefault(false)
            ) {
                TextSnapshot(file, editor.text?.toString().orEmpty(), view.isModified)
            } else {
                null
            }
        }
        return active ?: withContext(Dispatchers.IO) {
            require(file.exists() && file.isFile) { "File not found: $path" }
            TextSnapshot(file, file.readText(), false)
        }
    }

    private fun storedCredentialsOrNull(): UsernamePasswordCredentialsProvider? {
        val manager = GitCredentialsManager(IDEApplication.instance)
        val username = manager.getUsername()
        val token = manager.getToken()
        if (username.isNullOrBlank() || token.isNullOrBlank()) return null
        return UsernamePasswordCredentialsProvider(username, token)
    }

    private suspend fun <T> withRepository(block: suspend (GitRepository) -> T): T {
        val root = OmniIdeStateBridge.projectRoot()
        val repo = GitRepositoryManager.openRepository(root)
            ?: throw IllegalStateException("Current AndroidIDE project is not a Git repository")
        return try {
            block(repo)
        } finally {
            repo.close()
        }
    }

    private fun changes(items: List<dev.mutwakil.androidide.git.core.models.FileChange>) =
        buildJsonArray {
            items.forEach { item ->
                add(buildJsonObject {
                    put("path", item.path)
                    put("type", item.type.name)
                    item.oldPath?.let { put("oldPath", it) }
                })
            }
        }

    private fun filterTail(raw: String, lines: Int, query: String?): String {
        val maxLines = lines.coerceIn(1, 2_000)
        val selected = raw.lineSequence()
            .filter { query.isNullOrBlank() || it.contains(query, ignoreCase = true) }
            .toList()
            .takeLast(maxLines)
            .joinToString("\n")
        return selected.takeLast(MAX_LOG_CHARS)
    }

    private fun literalRanges(line: String, query: String, caseSensitive: Boolean): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var from = 0
        while (from <= line.length - query.length) {
            val index = line.indexOf(query, from, ignoreCase = !caseSensitive)
            if (index < 0) break
            out += index until (index + query.length)
            from = index + maxOf(1, query.length)
        }
        return out
    }

    private fun globToRegex(glob: String): Regex {
        val body = buildString {
            glob.forEach { ch ->
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                        append('\\').append(ch)
                    }
                    else -> append(ch)
                }
            }
        }
        return Regex("^$body$", RegexOption.IGNORE_CASE)
    }

    private fun isLikelyBinary(file: File): Boolean {
        val lower = file.name.lowercase()
        if (BINARY_EXTENSIONS.any(lower::endsWith)) return true
        return runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(4_096)
                val read = input.read(buffer)
                read > 0 && buffer.take(read).any { it == 0.toByte() }
            }
        }.getOrDefault(true)
    }

    private fun relative(file: File): String =
        runCatching { file.relativeTo(OmniIdeStateBridge.projectRoot()).invariantSeparatorsPath }
            .getOrDefault(file.name)

    private val SKIPPED_DIRS = setOf(".git", ".gradle", "build", ".idea", "node_modules")
    private val BINARY_EXTENSIONS = setOf(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".jar", ".aar",
        ".class", ".dex", ".so", ".keystore", ".jks", ".mp3", ".mp4", ".wav", ".ttf", ".otf"
    )
}

/** Process-local, bounded copy of app-under-test logs received through AndroidIDE LogSender. */
object OmniIdeObservabilityBridge {
    private const val MAX_APP_LOG_CHARS = 220_000
    private val lock = Any()
    private val appLogs = StringBuilder()
    private val observerEnabled = AtomicBoolean(false)

    fun setObserverEnabled(enabled: Boolean) {
        observerEnabled.set(enabled)
    }

    fun isObserverEnabled(): Boolean = observerEnabled.get()

    fun appendAppLog(line: String) {
        if (line.isBlank()) return
        synchronized(lock) {
            appLogs.append(line).append('\n')
            if (appLogs.length > MAX_APP_LOG_CHARS) {
                appLogs.delete(0, appLogs.length - MAX_APP_LOG_CHARS)
            }
        }
    }

    fun appLogsSnapshot(maxChars: Int): String = synchronized(lock) {
        val max = maxChars.coerceIn(1, MAX_APP_LOG_CHARS)
        appLogs.substring((appLogs.length - max).coerceAtLeast(0))
    }

    fun clearAppLogs() = synchronized(lock) { appLogs.setLength(0) }
}
