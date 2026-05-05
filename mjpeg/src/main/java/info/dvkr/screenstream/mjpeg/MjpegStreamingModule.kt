package info.dvkr.screenstream.mjpeg

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.common.module.isStreamingModuleStartBlocked
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import info.dvkr.screenstream.mjpeg.internal.MjpegStreamingService
import info.dvkr.screenstream.mjpeg.ui.MjpegMainScreenUI
import info.dvkr.screenstream.mjpeg.ui.MjpegState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

public class MjpegStreamingModule : StreamingModule {

    public companion object {
        public val Id: StreamingModule.Id = StreamingModule.Id("MJPEG")
    }

    private val _streamingServiceState: MutableStateFlow<StreamingModule.State> = MutableStateFlow(StreamingModule.State.Initiated)
    private val _mjpegStateFlow: MutableStateFlow<MjpegState> = MutableStateFlow(MjpegState())
    private var startToken: String? = null
    private var streamingService: MjpegStreamingService? = null

    override val id: StreamingModule.Id = Id
    override val priority: Int = 30

    override val isRunning: Flow<Boolean>
        get() = _streamingServiceState.map { it is StreamingModule.State.Running }

    override val isStreaming: Flow<Boolean>
        get() = _mjpegStateFlow.map { it.isStreaming }

    override val hasActiveConsumer: Flow<Boolean>
        get() = _mjpegStateFlow.map { state ->
            state.clients.any { client ->
                client.state == MjpegState.Client.State.CONNECTED || client.state == MjpegState.Client.State.SLOW_CONNECTION
            }
        }.distinctUntilChanged()

    override val nameResource: Int = R.string.mjpeg_stream_mode
    override val descriptionResource: Int = R.string.mjpeg_stream_mode_description
    override val detailsResource: Int = R.string.mjpeg_stream_mode_details

    @Composable
    override fun StreamUIContent(
        windowWidthSizeClass: StreamingModule.WindowWidthSizeClass,
        modifier: Modifier
    ): Unit =
        MjpegMainScreenUI(
            mjpegStateFlow = _mjpegStateFlow.asStateFlow(),
            sendEvent = ::sendEvent,
            onProjectionGranted = ::startProjection,
            windowWidthSizeClass = windowWidthSizeClass,
            modifier = modifier
        )

    @OptIn(ExperimentalUuidApi::class)
    @MainThread
    override fun startModule(context: Context) {
        XLog.d(getLog("startModule"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            StreamingModule.State.Initiated -> {
                startToken = Uuid.random().toString()
                _streamingServiceState.value = StreamingModule.State.PendingStart
                val intent = MjpegEvent.Intentable.StartService(startToken!!).toIntent(context)
                try {
                    MjpegModuleService.startService(context, intent)
                } catch (error: Throwable) {
                    startToken = null
                    _streamingServiceState.value = StreamingModule.State.Initiated
                    if (error.isStreamingModuleStartBlocked()) {
                        val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
                        throw StreamingModule.StartBlockedException(id, importance, error)
                    }
                    throw error
                }
            }

            StreamingModule.State.PendingStart ->
                XLog.i(getLog("startModule", "Already starting (PendingStart). Ignoring."))

            is StreamingModule.State.Running ->
                XLog.w(getLog("startModule", "Already running. Ignoring."), RuntimeException("Unexpected state: $state"))

            StreamingModule.State.PendingStop ->
                XLog.w(getLog("startModule", "Stopping (PendingStop). Ignoring."), RuntimeException("Unexpected state: $state"))
        }
    }

    @MainThread
    internal fun onServiceStart(service: Service, token: String) {
        XLog.d(getLog("onServiceStart", "Service: $service"))

        when (val state = _streamingServiceState.value) {
            StreamingModule.State.PendingStart -> {
                if (token != startToken) {
                    XLog.w(getLog("onServiceStart", "Invalid token. Ignoring."))
                    return
                }
                startToken = null
                val scope = MjpegKoinScope().scope
                try {
                    val createdStreamingService = scope.get<MjpegStreamingService> { parametersOf(service, _mjpegStateFlow) }
                    streamingService = createdStreamingService
                    _streamingServiceState.value = StreamingModule.State.Running(scope)
                    createdStreamingService.start()
                } catch (t: Throwable) {
                    streamingService = null
                    scope.close()
                    _streamingServiceState.value = StreamingModule.State.Initiated
                    throw t
                }
            }

            StreamingModule.State.Initiated ->
                XLog.w(getLog("onServiceStart", "Unexpected Initiated state. Ignoring."), RuntimeException("Unexpected state: $state"))

            is StreamingModule.State.Running ->
                XLog.w(getLog("onServiceStart", "Already running. Ignoring."), RuntimeException("Unexpected state: $state"))

            StreamingModule.State.PendingStop ->
                XLog.w(getLog("onServiceStart", "Stopping (PendingStop). Ignoring."), RuntimeException("Unexpected state: $state"))
        }
    }

    @MainThread
    override suspend fun stopModule() {
        XLog.d(getLog("stopModule"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            StreamingModule.State.Initiated -> XLog.d(getLog("stopModule", "Already stopped (Initiated). Ignoring"))

            StreamingModule.State.PendingStart -> {
                XLog.d(getLog("stopModule", "Not started (PendingStart)"))
                startToken = null
                streamingService = null
                _mjpegStateFlow.value = MjpegState()
                _streamingServiceState.value = StreamingModule.State.Initiated
            }

            is StreamingModule.State.Running -> {
                _streamingServiceState.value = StreamingModule.State.PendingStop
                _mjpegStateFlow.value = MjpegState()
                val activeStreamingService = streamingService
                try {
                    withContext(NonCancellable) {
                        if (activeStreamingService != null) activeStreamingService.destroyService()
                        else XLog.w(getLog("stopModule", "Running state without MjpegStreamingService"))
                    }
                } finally {
                    streamingService = null
                    _mjpegStateFlow.value = MjpegState()
                    startToken = null
                    state.scope.close()
                    _streamingServiceState.value = StreamingModule.State.Initiated
                }
            }

            StreamingModule.State.PendingStop -> XLog.d(getLog("stopModule", "Already stopping (PendingStop). Ignoring"))
        }

        XLog.d(getLog("stopModule", "Done"))
    }

    override fun stopStream(reason: String) {
        XLog.d(getLog("stopStream", "reason: $reason"))
        sendEvent(MjpegEvent.Intentable.StopStream(reason))
    }

    @MainThread
    internal fun startProjection(startAttemptId: String, intent: Intent) {
        XLog.d(getLog("startProjection", "startAttemptId=$startAttemptId, intent=$intent"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            is StreamingModule.State.Running -> {
                val activeStreamingService = streamingService
                if (activeStreamingService != null) {
                    val foregroundStartError = activeStreamingService.tryStartProjectionForeground()
                    activeStreamingService.sendEvent(MjpegEvent.StartProjection(startAttemptId, intent, foregroundStartProcessed = true, foregroundStartError))
                } else XLog.w(getLog("startProjection", "Running state without MjpegStreamingService"))
            }

            else -> XLog.i(getLog("startProjection", "Ignoring stale intent in state $state"))
        }
    }

    @MainThread
    internal fun sendEvent(event: MjpegEvent) {
        XLog.d(getLog("sendEvent", "Event $event"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            is StreamingModule.State.Running -> {
                val activeStreamingService = streamingService
                if (activeStreamingService != null) activeStreamingService.sendEvent(event)
                else XLog.w(
                    getLog("sendEvent", "Running state without MjpegStreamingService for event $event"),
                    RuntimeException("Unexpected state: $state for event $event")
                )
            }
            else -> when (event) {
                is MjpegEvent.CastPermissionsDenied,
                is MjpegEvent.StartProjection,
                is MjpegEvent.Intentable.StopStream,
                is MjpegStreamingService.InternalEvent.StartStream ->
                    XLog.i(getLog("sendEvent", "Ignoring stale event $event in state $state"))

                else -> XLog.w(
                    getLog("sendEvent", "Unexpected state: $state for event $event"),
                    RuntimeException("Unexpected state: $state for event $event")
                )
            }
        }
    }
}
