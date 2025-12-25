package info.dvkr.screenstream.rtsp

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
import info.dvkr.screenstream.rtsp.internal.RtspEvent
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import info.dvkr.screenstream.rtsp.ui.RtspMainScreenUI
import info.dvkr.screenstream.rtsp.ui.RtspModuleSettings
import info.dvkr.screenstream.rtsp.ui.RtspState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf

public class RtspStreamingModule : StreamingModule {

    public companion object {
        public val Id: StreamingModule.Id = StreamingModule.Id("RTSP")
    }

    private val _streamingServiceState: MutableStateFlow<StreamingModule.State> = MutableStateFlow(StreamingModule.State.Initiated)
    private val _rtspStateFlow: MutableStateFlow<RtspState> = MutableStateFlow(RtspState())

    override val id: StreamingModule.Id = Id
    override val priority: Int = 10
    override val moduleSettings: ModuleSettings = RtspModuleSettings

    override val isRunning: Flow<Boolean>
        get() = _streamingServiceState.map { it is StreamingModule.State.Running }

    override val isStreaming: Flow<Boolean>
        get() = _rtspStateFlow.map { it.isStreaming }

    override val nameResource: Int = R.string.rtsp_stream_mode
    override val descriptionResource: Int = R.string.rtsp_stream_mode_description
    override val detailsResource: Int = R.string.rtsp_stream_mode_details

    @Composable
    override fun StreamUIContent(modifier: Modifier): Unit =
        RtspMainScreenUI(
            rtspStateFlow = _rtspStateFlow.asStateFlow(),
            sendEvent = ::sendEvent,
            modifier = modifier
        )

    @MainThread
    override fun startModule(context: Context) {
        XLog.d(getLog("startModule"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            StreamingModule.State.Initiated -> {
                RtspModuleService.startService(context, RtspEvent.Intentable.StartService.toIntent(context))
                _streamingServiceState.value = StreamingModule.State.PendingStart
            }

            else -> throw RuntimeException("Unexpected state: $state")
        }
    }

    @MainThread
    internal fun onServiceStart(service: Service) {
        XLog.d(getLog("onServiceStart", "Service: $service"))

        when (val state = _streamingServiceState.value) {
            StreamingModule.State.PendingStart -> {
                val scope = RtspKoinScope().scope
                _streamingServiceState.value = StreamingModule.State.Running(scope)
                scope.get<RtspStreamingService> { parametersOf(service, _rtspStateFlow) }.start()
            }

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
                withContext(NonCancellable) { state.scope.get<RtspStreamingService>().destroyService() }
                _rtspStateFlow.value = RtspState()
                state.scope.close()
                _streamingServiceState.value = StreamingModule.State.Initiated
            }

            StreamingModule.State.PendingStop -> XLog.d(getLog("stopModule", "Already stopping (PendingStop). Ignoring"))
        }

        XLog.d(getLog("stopModule", "Done"))
    }

    @MainThread
    override fun stopStream(reason: String) {
        XLog.d(getLog("stopStream", "reason: $reason"))
        sendEvent(RtspEvent.Intentable.StopStream(reason))
    }

    @MainThread
    internal fun sendEvent(event: RtspEvent) {
        XLog.d(getLog("sendEvent", "Event $event"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            is StreamingModule.State.Running -> state.scope.get<RtspStreamingService>().sendEvent(event)
            else -> XLog.w(
                getLog("sendEvent", "Unexpected state: $state for event $event"),
                RuntimeException("Unexpected state: $state for event $event")
            )
        }
    }
}