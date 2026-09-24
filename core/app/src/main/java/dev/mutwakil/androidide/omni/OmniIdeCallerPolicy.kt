package dev.mutwakil.androidide.omni

/**
 * The IDE owns the final authorization decision. Peer-supplied manifests or claimed tiers
 * cannot upgrade their own authority: only the Android Binder UID/signature verifier plus
 * the official, distinct Workspace application ID determine the caller class.
 *
 * Unknown/third-party apps have no privileged Binder entry, even if they copy the SDK.
 */
internal object OmniIdeCallerPolicy {
    private const val BASE = "com.omnidev.workspace"
    private const val ADMIN = "com.omnidev.workspace.admin"
    private val status = setOf(
        "ide.health", "ide.get_project_context",
        "ide.get_diagnostics", "ide.get_active_diagnostics",
        "ide.get_build_output", "ide.get_ide_logs", "ide.get_app_logs",
        "ide.get_job", "ide.list_jobs"
    )
    private val read = status + setOf(
        "ide.get_active_document", "ide.get_file_info", "ide.read_lines",
        "ide.read_file", "ide.search_text", "ide.list_files",
        "ide.git_status", "ide.git_diff", "ide.git_history",
        "ide.git_branches", "ide.preview_line_patch"
    )
    private val build = setOf(
        "ide.sync_project", "ide.start_build", "ide.start_tests", "ide.start_lint"
    )

    fun allowed(packageName: String, sameSigner: Boolean, capability: String): Boolean {
        if (!sameSigner) return false
        // Event subscriptions are metadata-only IPC. The SDK calls this through
        // registerEventListener(), outside the ide.* action namespace. The extension
        // service separately authenticates the Binder caller before this policy check.
        if (capability == "register_event_listener") {
            return packageName in setOf(
                ADMIN, BASE, BASE + ".norm", BASE + ".pro", BASE + ".oem"
            )
        }
        if (!capability.startsWith("ide.")) return false
        return when (packageName) {
            ADMIN -> true
            BASE + ".pro", BASE + ".oem" -> capability in read || capability in build
            BASE + ".norm" -> capability in status || capability in setOf(
                "ide.read_lines", "ide.search_text", "ide.git_status"
            )
            BASE -> capability in setOf("ide.health", "ide.get_project_context")
            else -> false
        }
    }
}
