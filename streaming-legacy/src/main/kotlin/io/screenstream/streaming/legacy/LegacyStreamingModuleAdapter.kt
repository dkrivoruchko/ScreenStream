package io.screenstream.streaming.legacy

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import com.elvishew.xlog.XLog
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Creates controllers that bridge the common lifecycle to an unchanged legacy backend.
 * This descriptor retains one backend lease until stop, startup dispatch and exact Service detach finish.
 * Media, permissions and UI stay owned by the legacy module.
 */
public abstract class LegacyStreamingModuleAdapter(
    private val applicationContext: Context,
    private val legacyModule: Lazy<StreamingModuleLegacy>,
) : StreamingModule {
    private val lock: Any = Any()
    private var lease: LegacyController? = null

    /** Metadata used only by the existing legacy LAN permission UI. */
    public open val requiresLocalNetworkPermission: Boolean get() = false

    protected abstract fun startLegacyStream(module: StreamingModuleLegacy)

    /** Create an inert controller only inside the exact Service after the manager admits its startup. */
    public fun createController(runtime: StreamingModule.Controller.Runtime, service: Service): LegacyController =
        synchronized(lock) {
            check(lease == null) { "Legacy module is already launched" }
            LegacyController(runtime, service).also { lease = it }
        }

    /** Forward legacy permission recovery only to the currently admitted instance. */
    public fun recoverError(instanceId: StreamingModule.InstanceId) {
        val controller = synchronized(lock) { lease?.takeIf { it.instanceId == instanceId && it.isCurrent() } }
        controller?.backend?.recoverError()
    }

    /**
     * Owns one legacy launch, its observers and an independent backend-stop task.
     * Cleanup includes exact Service destruction after backend stop. The lease closes only after startup and stop finish.
     */
    public inner class LegacyController internal constructor(
        private val runtime: StreamingModule.Controller.Runtime,
        private val service: Service,
    ) : StreamingModule.Controller {
        override val instanceId: StreamingModule.InstanceId = runtime.instanceId
        private val workJob = SupervisorJob()
        private val workScope = CoroutineScope(workJob + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, failure ->
            XLog.e("Legacy observer failed", failure)
            runtime.reportFailed()
        })
        private val startupDispatchCompleted = CompletableDeferred<Unit>()
        private val cleanupTask: Deferred<Boolean> = CoroutineScope(Dispatchers.Main.immediate).async(start = CoroutineStart.LAZY) {
            finishCleanup()
        }
        private var started = false
        private val serviceDestroyed = CompletableDeferred<Unit>()
        @Volatile
        private var closing = false
        internal var backend: StreamingModuleLegacy? = null
        private var running = false
        private var streaming = false
        private var hasActiveConsumer = false
        private var attempt: StreamingModule.CaptureAttemptId? = null

        public fun isCurrent(): Boolean = !closing && runtime.isCurrent()

        /** Copy an ordinary legacy command for this launch, preserving its old token and extras. */
        public fun prepareCommand(intent: Intent): Intent = addressLegacyCommand(intent).also {
            if (it.action == StreamingModuleManager.ACTION_START_MODULE) it.action = null
        }

        /** Admit only ordinary legacy commands addressed to this original launch. */
        public fun acceptsCommand(intent: Intent?): Boolean = isCurrent() &&
            intent?.data == addressLegacyCommand(Intent()).data

        private fun addressLegacyCommand(intent: Intent): Intent = Intent(intent)
            .setData(Uri.Builder().scheme("screenstream").authority("legacy-command")
                .appendPath(instanceId.moduleId.value).appendPath(instanceId.uuid.toString()).build())

        override fun startModule() {
            synchronized(lock) {
                if (started || !isCurrent()) return
                started = true
            }
            try {
                val module = legacyModule.value.also { backend = it }
                if (!isCurrent()) return
                workScope.launch {
                    combine(module.isRunning, module.isStreaming, module.hasActiveConsumer) { running, streaming, hasActiveConsumer ->
                        Triple(running, streaming, hasActiveConsumer)
                    }.collect { (isRunning, isStreaming, hasConsumer) ->
                        running = isRunning
                        streaming = isStreaming
                        hasActiveConsumer = hasConsumer
                        if (streaming && attempt == null) attempt = StreamingModule.CaptureAttemptId(instanceId)
                        if (!streaming) attempt = null
                    }
                }
                module.startModule(object : ContextWrapper(applicationContext) {
                    override fun startService(service: Intent): ComponentName? =
                        if (isCurrent()) applicationContext.startService(prepareCommand(service)) else null
                })
                workScope.launch {
                    while (isCurrent()) {
                        if (running) runtime.reportRunning(StreamingModule.Status(streaming, hasActiveConsumer, attempt), SystemClock.uptimeMillis())
                        delay(1.seconds)
                    }
                }
            } finally {
                synchronized(lock) {
                    startupDispatchCompleted.complete(Unit)
                }
            }
        }

        override fun requestStreamStart() {
            if (isCurrent()) backend?.let { startLegacyStream(it) }
        }

        override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId) {
            if (isCurrent() && this.attempt == attempt) backend?.stopStream("Coordinator Stop")
        }

        /** Close admission and start the one owned stop task; safe before start and on repeated calls. */
        override fun requestModuleShutdown() {
            synchronized(lock) {
                if (!closing) {
                    closing = true
                    if (!started) {
                        startupDispatchCompleted.complete(Unit)
                    }
                }
            }
            workJob.cancel()
            cleanupTask.start()
        }

        private suspend fun finishCleanup(): Boolean {
            startupDispatchCompleted.await()
            val backendStopped = runCatching {
                withContext(Dispatchers.Main.immediate) { backend?.stopModule() }
            }
                .onFailure { XLog.e("Legacy module stop failed", it) }.isSuccess
            workJob.join()
            val serviceStopped = runCatching {
                withContext(Dispatchers.Main.immediate) {
                    if (!serviceDestroyed.isCompleted) service.stopSelf()
                }
            }
                .onFailure { XLog.e("Legacy Service stop failed", it) }.isSuccess
            serviceDestroyed.await()
            synchronized(lock) { if (lease === this) lease = null }
            return backendStopped && serviceStopped
        }

        /**
         * After shutdown, wait for backend stop, observers and the original Service destruction; false means stop failed.
         * Destruction never waits for this task. Cancelling a caller cancels only its wait, never this task.
         */
        override suspend fun awaitCleanup(): Boolean = cleanupTask.await()

        /** Record original Service loss and start cleanup without waiting for backend stop or this task. */
        override fun onServiceDestroyed() {
            synchronized(lock) {
                serviceDestroyed.complete(Unit)
            }
            try {
                runtime.reportFailed()
            } finally {
                requestModuleShutdown()
            }
        }

        @Composable
        override fun Content(window: WindowSizeClass, modifier: Modifier) {
            if (isCurrent()) backend?.StreamUIContent(window, modifier)
        }
    }
}
