package dev.mutwakil.androidide.git.core

import android.content.Context

/**
 * High-level Git operations for app/agent callers without leaking JGit implementation types.
 * Credentials remain encrypted in AndroidIDE and are converted to JGit providers only inside git-core.
 */
object GitAutomation {

    data class OperationResult(
        val success: Boolean,
        val status: String,
        val details: List<String> = emptyList()
    )

    suspend fun pull(
        repository: GitRepository,
        context: Context,
        remote: String = "origin"
    ): OperationResult {
        val result = repository.pull(
            remote = remote.ifBlank { "origin" },
            credentialsProvider = credentialsOrNull(context)
        )
        return OperationResult(
            success = result.isSuccessful,
            status = if (result.isSuccessful) "SUCCESS" else "FAILED",
            details = listOf(result.toString())
        )
    }

    suspend fun push(
        repository: GitRepository,
        context: Context,
        remote: String = "origin"
    ): OperationResult {
        val credentials = credentialsOrNull(context)
            ?: throw IllegalStateException(
                "No Git credentials are stored in AndroidIDE. Configure Git credentials first."
            )
        val results = repository.push(
            remote = remote.ifBlank { "origin" },
            credentialsProvider = credentials
        ).toList()

        val details = results.map { it.toString() }
        val success = results.all { result ->
            result.remoteUpdates.all { update ->
                when (update.status.name) {
                    "OK", "UP_TO_DATE", "NON_EXISTING" -> true
                    else -> false
                }
            }
        }
        return OperationResult(
            success = success,
            status = if (success) "SUCCESS" else "FAILED",
            details = details
        )
    }

    suspend fun merge(
        repository: GitRepository,
        branch: String
    ): OperationResult {
        require(branch.isNotBlank()) { "branch is required" }
        val result = repository.merge(branch)
        val status = result.mergeStatus?.toString().orEmpty()
        return OperationResult(
            success = result.mergeStatus?.isSuccessful == true,
            status = status,
            details = listOf(result.toString())
        )
    }

    suspend fun abortMerge(repository: GitRepository): OperationResult {
        repository.abortMerge()
        return OperationResult(
            success = true,
            status = "ABORTED"
        )
    }

    private fun credentialsOrNull(context: Context): org.eclipse.jgit.transport.CredentialsProvider? {
        val manager = GitCredentialsManager(context.applicationContext)
        val username = manager.getUsername()
        val token = manager.getToken()
        if (username.isNullOrBlank() || token.isNullOrBlank()) return null
        return org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(username, token)
    }
}
