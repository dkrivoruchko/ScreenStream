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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

public class RtspStreamingModule : StreamingModule {

    public companion object {
        public val Id: StreamingModule.Id = StreamingModule.Id("RTSP")
    }

    private val _streamingServiceState: MutableStateFlow<StreamingModule.State> = MutableStateFlow(StreamingModule.State.Initiated)
    private val _rtspStateFlow: MutableStateFlow<RtspState> = MutableStateFlow(RtspState())
    private var startToken: String? = null

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
                    RtspModuleService.startService(context, RtspEvent.Intentable.StartService(startToken!!).toIntent(context))
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
        XLog.d(getLog("onServiceStart", "Service: $service"))

        when (val state = _streamingServiceState.value) {
            StreamingModule.State.PendingStart -> {
                if (token != startToken) {
                    XLog.w(getLog("onServiceStart", "Invalid token. Ignoring."))
                    return
                }
                startToken = null
                val scope = RtspKoinScope().scope
                _streamingServiceState.value = StreamingModule.State.Running(scope)
                scope.get<RtspStreamingService> { parametersOf(service, _rtspStateFlow) }.start()
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
                _streamingServiceState.value = StreamingModule.State.Initiated
            }

            is StreamingModule.State.Running -> {
                _streamingServiceState.value = StreamingModule.State.PendingStop
                withContext(NonCancellable) { state.scope.get<RtspStreamingService>().destroyService() }
                _rtspStateFlow.value = RtspState()
                startToken = null
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
