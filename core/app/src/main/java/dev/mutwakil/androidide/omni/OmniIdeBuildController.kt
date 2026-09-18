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
import dev.mutwakil.androidide.tooling.api.messages.result.BuildInfo
import dev.mutwakil.androidide.tooling.api.messages.result.InitializeResult
import dev.mutwakil.androidide.tooling.api.messages.result.TaskExecutionResult
import dev.mutwakil.androidide.tooling.events.ProgressEvent
import dev.mutwakil.androidide.tooling.api.messages.result.isSuccessful
import dev.mutwakil.androidide.tooling.api.sync.ProjectSyncHelper
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var ownsHeadlessListener = false
    private val operationMutex = Mutex()
    private val operationMutex = Mutex()

    suspend fun ensureService(): GradleBuildService {
        val existing = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
            as? GradleBuildService
        if (existing != null) {
            boundService = existing
            ensureToolingServer(existing)
            configureOutputListener(existing)
            return existing
        }

        boundService?.let {
            ensureToolingServer(it)
            configureOutputListener(it)
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
        configureOutputListener(service)
        return service
    }

    suspend fun syncProject(
        root: File = OmniIdeStateBridge.projectRoot(),
        force: Boolean = false
    ): InitializeResult = operationMutex.withLock {
        syncProjectLocked(root, force)
    }

    private suspend fun syncProjectLocked(
        root: File,
        force: Boolean
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
    ): TaskExecutionResult = operationMutex.withLock {
        require(tasks.isNotEmpty()) { "At least one Gradle task is required" }
        val service = ensureService()
        val init = syncProjectLocked(root, forceSync)
        check(init.isSuccessful) { "Project initialization/sync failed: $init" }
        service.executeTasks(tasks).await()
    }

    suspend fun cancelCurrentBuild(): String {
        val service = ensureService()
        return service.cancelCurrentBuild().await().toString()
    }

    fun release() {
        // Do not clear GradleBuildService.eventListener here. An Editor Activity may have replaced
        // our headless listener after this controller started; clearing the shared slot would then
        // detach the editor's real build listener. A future editor/headless owner simply replaces it.
        ownsHeadlessListener = false
        connection?.let { conn -> runCatching { context.unbindService(conn) } }
        connection = null
        boundService = null
    }

    private fun configureOutputListener(service: GradleBuildService) {
        // When an editor Activity is alive, its EditorBuildEventListener owns this slot and mirrors
        // output through ProjectHandlerActivity.appendBuildOutput(). Headless agent runs need their
        // own listener so Omni still receives compiler/Gradle diagnostics while AndroidIDE is hidden.
        if (OmniIdeStateBridge.activeActivity() != null) {
            ownsHeadlessListener = false
            return
        }
        service.setEventListener(object : GradleBuildService.EventListener {
            override fun prepareBuild(buildInfo: BuildInfo) = Unit
            override fun onBuildSuccessful(tasks: List<String?>) {
                OmniIdeStateBridge.appendBuildOutput(
                    "\n[Omni] BUILD SUCCESSFUL: " + tasks.filterNotNull().joinToString() + "\n"
                )
            }
            override fun onProgressEvent(event: ProgressEvent) = Unit
            override fun onBuildFailed(tasks: List<String?>) {
                OmniIdeStateBridge.appendBuildOutput(
                    "\n[Omni] BUILD FAILED: " + tasks.filterNotNull().joinToString() + "\n"
                )
            }
            override fun onOutput(line: String?) {
                line?.let { OmniIdeStateBridge.appendBuildOutput(it + "\n") }
            }
        })
        ownsHeadlessListener = true
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
