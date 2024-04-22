package info.dvkr.screenstream.webrtc

import android.app.Service
import android.content.Context
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.koin.core.parameter.parametersOf

@Single
@Named(WebRtcKoinQualifier)
public class WebRtcStreamingModule : StreamingModule {

    public companion object {
        public val Id: StreamingModule.Id = StreamingModule.Id("WEBRTC")
    }

    override val id: StreamingModule.Id = Id

    override val priority: Int = 10

    override val moduleSettings: ModuleSettings = WebRtcModuleSettings

    private val _streamingServiceState: MutableStateFlow<StreamingModule.State> = MutableStateFlow(StreamingModule.State.Initiated)

    override val isRunning: Flow<Boolean>
        get() = _streamingServiceState.map { it is StreamingModule.State.Running }

    override val isStreaming: Flow<Boolean>
        get() = _webRtcStateFlow.map { it.isStreaming }

    private val _webRtcStateFlow: MutableStateFlow<WebRtcState> = MutableStateFlow(WebRtcState())

    init {
        _streamingServiceState //TODO
            .onEach { XLog.i(getLog("init", "State: $it")) }
            .launchIn(GlobalScope)
    }

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

    @MainThread
    override fun startModule(context: Context) {
        XLog.d(getLog("startModule"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            StreamingModule.State.Initiated -> {
                WebRtcModuleService.startService(context, WebRtcEvent.Intentable.StartService.toIntent(context))
                _streamingServiceState.value = StreamingModule.State.PendingStart
            }

            else -> throw RuntimeException("Unexpected state: $state")
        }
    }

    @MainThread
    internal fun onServiceStart(service: Service) {
        when (val state = _streamingServiceState.value) {
            StreamingModule.State.PendingStart -> {
                val scope = WebRtcKoinScope().scope
                _streamingServiceState.value = StreamingModule.State.Running(scope)
                scope.get<WebRtcStreamingService> { parametersOf(service, _webRtcStateFlow) }.start()
            }

            else -> throw RuntimeException("Unexpected state: $state")
        }
    }

    @MainThread
    internal fun sendEvent(event: WebRtcEvent) {
        XLog.d(getLog("sendEvent", "Event $event"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            is StreamingModule.State.Running -> state.scope.get<WebRtcStreamingService>().sendEvent(event)
            else -> throw RuntimeException("Unexpected state: $state")
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
                _streamingServiceState.value = StreamingModule.State.Initiated
            }

            is StreamingModule.State.Running -> {
                _streamingServiceState.value = StreamingModule.State.PendingStop
                withContext(NonCancellable) { state.scope.get<WebRtcStreamingService>().destroyService() }
                _webRtcStateFlow.value = WebRtcState()
                state.scope.close()
                _streamingServiceState.value = StreamingModule.State.Initiated
            }

            StreamingModule.State.PendingStop -> throw RuntimeException("Unexpected state: $state")
        }

        XLog.d(getLog("stopModule", "Done"))
    }
}