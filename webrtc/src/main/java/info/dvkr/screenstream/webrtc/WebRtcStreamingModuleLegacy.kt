package info.dvkr.screenstream.webrtc

import io.screenstream.streaming.module.StreamingModule
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import io.screenstream.streaming.legacy.StreamingModuleLegacy
import io.screenstream.streaming.legacy.isStreamingModuleStartBlocked
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import info.dvkr.screenstream.webrtc.internal.WebRtcStreamingService
import info.dvkr.screenstream.webrtc.ui.WebRtcMainScreenUI
import info.dvkr.screenstream.webrtc.ui.WebRtcState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Singleton(binds = [StreamingModuleLegacy::class])
@Named("WebRtcStreamingModule")
public class WebRtcStreamingModuleLegacy : StreamingModuleLegacy {

    public companion object {
        public val Id: StreamingModule.Id = StreamingModule.Id("WEBRTC")
    }

    private val _streamingServiceState: MutableStateFlow<StreamingModuleLegacy.State> = MutableStateFlow(StreamingModuleLegacy.State.Initiated)
    private val _webRtcStateFlow: MutableStateFlow<WebRtcState> = MutableStateFlow(WebRtcState())
    private var startToken: String? = null
    private var streamingService: WebRtcStreamingService? = null

    override val id: StreamingModule.Id = Id
    override val priority: Int = 20

    override val isRunning: Flow<Boolean>
        get() = _streamingServiceState.map { it is StreamingModuleLegacy.State.Running }

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
    override fun StreamUIContent(
        windowSizeClass: WindowSizeClass,
        modifier: Modifier
    ): Unit =
        WebRtcMainScreenUI(
            webRtcStateFlow = _webRtcStateFlow.asStateFlow(),
            sendEvent = ::sendEvent,
            onProjectionGranted = ::startProjection,
            modifier = modifier
        )

    @MainThread
    override fun startModule(context: Context) {
        XLog.d(getLog("startModule"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            StreamingModuleLegacy.State.Initiated -> {
                startToken = Uuid.random().toString()
                _streamingServiceState.value = StreamingModuleLegacy.State.PendingStart
                val intent = WebRtcEvent.Intentable.StartService(startToken!!).toIntent(context)
                try {
                    WebRtcModuleService.startService(context, intent)
                } catch (error: Throwable) {
                    startToken = null
                    _streamingServiceState.value = StreamingModuleLegacy.State.Initiated
                    if (error.isStreamingModuleStartBlocked()) {
                        val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
                        throw StreamingModuleLegacy.StartBlockedException(id, importance, error)
                    }
                    throw error
                }
            }

            StreamingModuleLegacy.State.PendingStart ->
                XLog.i(getLog("startModule", "Already starting (PendingStart). Ignoring."))

            is StreamingModuleLegacy.State.Running ->
                XLog.w(getLog("startModule", "Already running. Ignoring."), RuntimeException("Unexpected state: $state"))

            StreamingModuleLegacy.State.PendingStop ->
                XLog.w(getLog("startModule", "Stopping (PendingStop). Ignoring."), RuntimeException("Unexpected state: $state"))
        }
    }

    @MainThread
    internal fun onServiceStart(service: WebRtcModuleService, token: String) {
        when (val state = _streamingServiceState.value) {
            StreamingModuleLegacy.State.PendingStart -> {
                if (token != startToken) {
                    XLog.w(getLog("onServiceStart", "Invalid token. Ignoring."))
                    return
                }
                startToken = null
                val scope = WebRtcKoinScope().scope
                try {
                    val createdStreamingService = scope.get<WebRtcStreamingService> { parametersOf(service, _webRtcStateFlow) }
                    streamingService = createdStreamingService
                    _streamingServiceState.value = StreamingModuleLegacy.State.Running(scope)
                    createdStreamingService.start()
                } catch (t: Throwable) {
                    streamingService = null
                    scope.close()
                    _streamingServiceState.value = StreamingModuleLegacy.State.Initiated
                    throw t
                }
            }

            StreamingModuleLegacy.State.Initiated ->
                XLog.w(getLog("onServiceStart", "Unexpected Initiated state. Ignoring."), RuntimeException("Unexpected state: $state"))

            is StreamingModuleLegacy.State.Running ->
                XLog.w(getLog("onServiceStart", "Already running. Ignoring."), RuntimeException("Unexpected state: $state"))

            StreamingModuleLegacy.State.PendingStop ->
                XLog.w(getLog("onServiceStart", "Stopping (PendingStop). Ignoring."), RuntimeException("Unexpected state: $state"))
        }
    }

    @MainThread
    override suspend fun stopModule() {
        XLog.d(getLog("stopModule"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            StreamingModuleLegacy.State.Initiated -> XLog.d(getLog("stopModule", "Already stopped (Initiated). Ignoring"))

            StreamingModuleLegacy.State.PendingStart -> {
                XLog.d(getLog("stopModule", "Not started (PendingStart)"))
                startToken = null
                streamingService = null
                _streamingServiceState.value = StreamingModuleLegacy.State.Initiated
            }

            is StreamingModuleLegacy.State.Running -> {
                _streamingServiceState.value = StreamingModuleLegacy.State.PendingStop
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
                    _streamingServiceState.value = StreamingModuleLegacy.State.Initiated
                }
            }

            StreamingModuleLegacy.State.PendingStop -> XLog.d(getLog("stopModule", "Already stopping (PendingStop). Ignoring"))
        }

        XLog.d(getLog("stopModule", "Done"))
    }

    override fun stopStream(reason: String) {
        XLog.d(getLog("stopStream", "reason $reason"))
        sendEvent(WebRtcEvent.Intentable.StopStream(reason))
    }

    @MainThread
    internal fun startProjection(startAttemptId: String, intent: Intent) {
        XLog.d(getLog("startProjection", "startAttemptId=$startAttemptId, intent=$intent"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (val state = _streamingServiceState.value) {
            is StreamingModuleLegacy.State.Running -> {
                val activeStreamingService = streamingService
                if (activeStreamingService != null) {
                    if (activeStreamingService.prepareStartProjectionForeground(startAttemptId)) {
                        val foregroundStartError = activeStreamingService.tryStartProjectionForeground()
                        activeStreamingService.sendEvent(WebRtcEvent.StartProjection(startAttemptId, intent, foregroundStartProcessed = true, foregroundStartError))
                    }
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
            is StreamingModuleLegacy.State.Running -> {
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
