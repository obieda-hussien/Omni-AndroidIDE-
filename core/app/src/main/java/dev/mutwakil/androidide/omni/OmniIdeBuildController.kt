package dev.mutwakil.androidide.omni

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dev.mutwakil.androidide.lookup.Lookup
import dev.mutwakil.androidide.projects.ProjectManagerImpl
import dev.mutwakil.androidide.projects.builder.BuildService
import dev.mutwakil.androidide.services.builder.GradleBuildService
import dev.mutwakil.androidide.services.builder.GradleServiceBinder
import dev.mutwakil.androidide.services.builder.gradleDistributionParams
import dev.mutwakil.androidide.tooling.api.messages.AndroidInitializationParams
import dev.mutwakil.androidide.tooling.api.messages.InitializeProjectParams
import dev.mutwakil.androidide.tooling.api.messages.result.InitializeResult
import dev.mutwakil.androidide.tooling.api.messages.result.TaskExecutionResult
import dev.mutwakil.androidide.tooling.api.messages.result.isSuccessful
import dev.mutwakil.androidide.tooling.api.sync.ProjectSyncHelper
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Headless-safe access to AndroidIDE's real Gradle Tooling API service.
 * No Runtime.exec/gradlew fallback is used.
 */
class OmniIdeBuildController(private val context: Context) {
    private var connection: ServiceConnection? = null
    private var boundService: GradleBuildService? = null
    private var initializedProject: String? = null

    suspend fun ensureService(): GradleBuildService {
        val existing = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
            as? GradleBuildService
        if (existing != null) {
            boundService = existing
            ensureToolingServer(existing)
            return existing
        }

        boundService?.let {
            ensureToolingServer(it)
            return it
        }

        val deferred = CompletableDeferred<GradleBuildService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = (binder as? GradleServiceBinder)?.service
                if (service != null) {
                    boundService = service
                    Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, service)
                    if (!deferred.isCompleted) deferred.complete(service)
                } else if (!deferred.isCompleted) {
                    deferred.completeExceptionally(
                        IllegalStateException("GradleBuildService returned a null binder")
                    )
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
        connection = conn

        val didBind = withContext(Dispatchers.Main.immediate) {
            context.bindService(
                Intent(context, GradleBuildService::class.java),
                conn,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        }
        check(didBind) { "Unable to bind AndroidIDE GradleBuildService" }

        val service = withTimeout(20_000) { deferred.await() }
        ensureToolingServer(service)
        return service
    }

    suspend fun syncProject(
        root: File = OmniIdeStateBridge.projectRoot(),
        force: Boolean = false
    ): InitializeResult {
        val service = ensureService()
        val manager = ProjectManagerImpl.getInstance()
        manager.projectPath = root.absolutePath

        val needsSync = force ||
            initializedProject != root.canonicalPath ||
            manager.isGradleSyncNeeded(root)

        val result = service.initializeProject(
            InitializeProjectParams(
                directory = root.absolutePath,
                gradleDistribution = gradleDistributionParams,
                androidParams = AndroidInitializationParams.DEFAULT,
                needsGradleSync = needsSync
            )
        ).await()

        if (result is InitializeResult.Success) {
            val cached = ProjectSyncHelper.readGradleBuild(result.cacheFile)
            if (cached.isSuccess) {
                manager.setup(cached.getOrThrow())
                manager.notifyProjectUpdate()
            }
            initializedProject = root.canonicalPath
        }
        return result
    }

    suspend fun executeTasks(
        root: File,
        tasks: List<String>,
        forceSync: Boolean = false
    ): TaskExecutionResult {
        require(tasks.isNotEmpty()) { "At least one Gradle task is required" }
        val service = ensureService()
        val init = syncProject(root, forceSync)
        check(init.isSuccessful) { "Project initialization/sync failed: $init" }
        return service.executeTasks(tasks).await()
    }

    suspend fun cancelCurrentBuild(): String {
        val service = ensureService()
        return service.cancelCurrentBuild().await().toString()
    }

    fun release() {
        val conn = connection ?: return
        runCatching { context.unbindService(conn) }
        connection = null
        boundService = null
    }

    private suspend fun ensureToolingServer(service: GradleBuildService) {
        if (service.isToolingServerStarted()) return
        withContext(Dispatchers.Main.immediate) {
            service.startToolingServer(null)
        }
        withTimeout(30_000) {
            while (!service.isToolingServerStarted()) delay(100)
        }
    }
}
