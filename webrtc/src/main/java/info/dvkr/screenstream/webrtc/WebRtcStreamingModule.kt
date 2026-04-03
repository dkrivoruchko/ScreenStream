package info.dvkr.screenstream.webrtc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import info.dvkr.screenstream.webrtc.internal.WebRtcStreamingService
import info.dvkr.screenstream.webrtc.ui.WebRtcMainScreenUI
import info.dvkr.screenstream.webrtc.ui.WebRtcModuleSettings
import info.dvkr.screenstream.webrtc.ui.WebRtcState
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

public class WebRtcStreamingModule : StreamingModule {

    public companion object {
        public val Id: StreamingModule.Id = StreamingModule.Id("WEBRTC")
    }

    private val _streamingServiceState: MutableStateFlow<StreamingModule.State> = MutableStateFlow(StreamingModule.State.Initiated)
    private val _webRtcStateFlow: MutableStateFlow<WebRtcState> = MutableStateFlow(WebRtcState())
    private var startToken: String? = null
    private var streamingService: WebRtcStreamingService? = null

    override val id: StreamingModule.Id = Id
    override val priority: Int = 20
    override val moduleSettings: ModuleSettings = WebRtcModuleSettings

    override val isRunning: Flow<Boolean>
        get() = _streamingServiceState.map { it is StreamingModule.State.Running }

    override val isStreaming: Flow<Boolean>
        get() = _webRtcStateFlow.map { it.isStreaming }

    override val hasActiveConsumer: Flow<Boolean>
        get() = _webRtcStateFlow
            .map { it.clients.isNotEmpty() }
            .distinctUntilChanged()

    override val nameResource: Int = R.string.webrtc_stream_mode
    override val descriptionResource: Int = R.string.webrtc_stream_mode_description
    override val detailsResource: Int = R.string.webrtc_stream_mode_details

    @Composable
    override fun StreamUIContent(modifier: Modifier): Unit =
        WebRtcMainScreenUI(
            webRtcStateFlow = _webRtcStateFlow.asStateFlow(),
            sendEvent = ::sendEvent,
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
                try {
                    WebRtcModuleService.startService(context, WebRtcEvent.Intentable.StartService(startToken!!).toIntent(context))
                } catch (t: Throwable) {
                    startToken = null
                    _streamingServiceState.value = StreamingModule.State.Initiated
                    throw t
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
        when (val state = _streamingServiceState.value) {
            StreamingModule.State.PendingStart -> {
                if (token != startToken) {
                    XLog.w(getLog("onServiceStart", "Invalid token. Ignoring."))
                    return
                }
                startToken = null
                val scope = WebRtcKoinScope().scope
                try {
                    val createdStreamingService = scope.get<WebRtcStreamingService> { parametersOf(service, _webRtcStateFlow) }
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
                _streamingServiceState.value = StreamingModule.State.Initiated
            }

            is StreamingModule.State.Running -> {
                _streamingServiceState.value = StreamingModule.State.PendingStop
                _webRtcStateFlow.value = WebRtcState()
                val activeStreamingService = streamingService
                try {
                    withContext(NonCancellable) {
                        if (activeStreamingService != null) activeStreamingService.destroyService()
                        else XLog.w(getLog("stopModule", "Running state without WebRtcStreamingService"))
                    }
                } finally {
                    streamingService = null
                    _webRtcStateFlow.value = WebRtcState()
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
        XLog.d(getLog("stopStream", "reason $reason"))
        sendEvent(WebRtcEvent.Intentable.StopStream(reason))
    }

    @MainThread
    internal fun startProjection(intent: Intent) {
        XLog.d(getLog("startProjection", "intent=$intent"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            is StreamingModule.State.Running -> {
                val activeStreamingService = streamingService
                if (activeStreamingService != null) {
                    val foregroundStartError = activeStreamingService.tryStartProjectionForeground()
                    activeStreamingService.sendEvent(WebRtcEvent.StartProjection(intent = intent, foregroundStartProcessed = true, foregroundStartError))
                } else XLog.w(getLog("startProjection", "Running state without WebRtcStreamingService"))
            }

            else -> XLog.i(getLog("startProjection", "Ignoring stale intent in state $state"))
        }
    }

    @MainThread
    internal fun sendEvent(event: WebRtcEvent) {
        XLog.d(getLog("sendEvent", "Event $event"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            is StreamingModule.State.Running -> {
                val activeStreamingService = streamingService
                if (activeStreamingService != null) activeStreamingService.sendEvent(event)
                else XLog.w(
                    getLog("sendEvent", "Running state without WebRtcStreamingService for event $event"),
                    RuntimeException("Unexpected state: $state for event $event")
                )
            }
            else -> when (event) {
                is WebRtcEvent.Intentable.StopStream,
                is WebRtcEvent.StartProjection,
                is WebRtcEvent.CastPermissionsDenied,
                is WebRtcEvent.GetNewStreamId,
                is WebRtcEvent.CreateNewPassword,
                is WebRtcStreamingService.InternalEvent.StartStream ->
                    XLog.i(getLog("sendEvent", "Ignoring stale event in state $state => $event"))

                else -> XLog.w(
                    getLog("sendEvent", "Unexpected state: $state for event $event"),
                    RuntimeException("Unexpected state: $state for event $event")
                )
            }
        }
    }
}
