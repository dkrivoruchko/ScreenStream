package info.dvkr.screenstream.mjpeg

import android.app.Service
import android.content.Context
import android.os.Looper
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.AppEvent
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import info.dvkr.screenstream.mjpeg.internal.MjpegState
import info.dvkr.screenstream.mjpeg.internal.MjpegStreamingService
import info.dvkr.screenstream.mjpeg.ui.MjpegStreamingFragment
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.koin.core.parameter.parametersOf

@Single
@Named(MjpegKoinQualifier)
public class MjpegStreamingModule : StreamingModule {

    public companion object {
        public val Id: StreamingModule.Id = StreamingModule.Id("MJPEG")
    }

    override val id: StreamingModule.Id = Id

    override val priority: Int = 20

    private val _streamingServiceState: MutableStateFlow<StreamingModule.State> = MutableStateFlow(StreamingModule.State.Initiated)
    override val isRunning: Flow<Boolean>
        get() = _streamingServiceState.asStateFlow().map { it is StreamingModule.State.Running }

    private val _mjpegStateFlow: MutableStateFlow<MjpegState?> = MutableStateFlow(null)
    internal val mjpegStateFlow: StateFlow<MjpegState?>
        get() = _mjpegStateFlow.asStateFlow()

    init {
        _streamingServiceState
            .onEach { XLog.i(getLog("init", "State: $it")) }
            .launchIn(GlobalScope)
    }

    @MainThread
    override fun getName(context: Context): String {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }
        return context.getString(R.string.mjpeg_stream_fragment_mode_local)
    }

    @MainThread
    override fun getContentDescription(context: Context): String {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }
        return context.getString(R.string.mjpeg_stream_fragment_mode_local_description)
    }

    @MainThread
    override fun showDescriptionDialog(context: Context, lifecycleOwner: LifecycleOwner) {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        MaterialDialog(context).show {
            lifecycleOwner(lifecycleOwner)
            icon(R.drawable.mjpeg_ic_permission_dialog_24dp)
            title(R.string.mjpeg_stream_fragment_mode_local)
            message(R.string.mjpeg_stream_fragment_mode_local_details)
            positiveButton(android.R.string.ok)
        }
    }

    @MainThread
    override fun getFragmentClass(): Class<out Fragment> = MjpegStreamingFragment::class.java

    internal val mjpegSettings: MjpegSettings
        get() = when (val state = _streamingServiceState.value) {
            is StreamingModule.State.Running -> state.scope.get<MjpegSettings>()
            else -> throw RuntimeException("Unexpected state: $state")
        }

    @MainThread
    override fun startModule(context: Context) {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        XLog.d(getLog("startModule"))

        when (val state = _streamingServiceState.value) {
            StreamingModule.State.Initiated -> {
                MjpegModuleService.startService(context, MjpegEvent.Intentable.StartService.toIntent(context))
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
                val scope = MjpegKoinScope().scope
                _streamingServiceState.value = StreamingModule.State.Running(scope)
                scope.get<MjpegStreamingService> { parametersOf(service, _mjpegStateFlow) }.start()
            }

            else -> throw RuntimeException("Unexpected state: $state")
        }
    }

    @MainThread
    override fun sendEvent(event: AppEvent) {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }
        XLog.d(getLog("sendEvent", "Event $event"))

        when (event) {
            is AppEvent.StartStream -> sendEvent(MjpegStreamingService.InternalEvent.StartStream)
            is AppEvent.StopStream -> sendEvent(MjpegEvent.Intentable.StopStream("User action: Button"))
        }
    }

    @MainThread
    internal fun sendEvent(event: MjpegEvent) {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }
        XLog.d(getLog("sendEvent", "Event $event"))

        when (val state = _streamingServiceState.value) {
            is StreamingModule.State.Running -> state.scope.get<MjpegStreamingService>().sendEvent(event)
            else -> throw RuntimeException("Unexpected state: $state")
        }
    }

    @MainThread
    override suspend fun stopModule() {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }
        XLog.d(getLog("stopModule"))

        when (val state = _streamingServiceState.value) {
            StreamingModule.State.Initiated -> XLog.d(getLog("stopModule", "Already stopped (Initiated). Ignoring"))

            StreamingModule.State.PendingStart -> {
                XLog.d(getLog("stopModule", "Not started (PendingStart)"))
                _streamingServiceState.value = StreamingModule.State.Initiated
            }

            is StreamingModule.State.Running -> {
                _streamingServiceState.value = StreamingModule.State.PendingStop
                withContext(NonCancellable) { state.scope.get<MjpegStreamingService>().destroyService() }
                _mjpegStateFlow.value = null
                state.scope.close()
                _streamingServiceState.value = StreamingModule.State.Initiated
            }

            StreamingModule.State.PendingStop -> throw RuntimeException("Unexpected state: $state")
        }

        XLog.d(getLog("stopModule", "Done"))
    }
}