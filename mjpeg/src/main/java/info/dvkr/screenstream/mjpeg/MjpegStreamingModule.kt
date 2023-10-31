package info.dvkr.screenstream.mjpeg

import android.content.Context
import android.os.Looper
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.StreamingModule
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import info.dvkr.screenstream.mjpeg.internal.MjpegState
import info.dvkr.screenstream.mjpeg.internal.MjpegStreamingService
import info.dvkr.screenstream.mjpeg.ui.MjpegStreamingFragment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.koin.core.parameter.parametersOf
import org.koin.core.scope.Scope

@Single
@Named(MjpegKoinQualifier)
public class MjpegStreamingModule : StreamingModule {

    internal companion object {
        internal val Id: StreamingModule.Id = StreamingModule.Id("MJPEG")
    }

    override val id: StreamingModule.Id = Id

    override val priority: Int = 10

    private val _streamingServiceIsActive: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val streamingServiceIsActive: StateFlow<Boolean>
        get() = _streamingServiceIsActive.asStateFlow()

    private val _mjpegStateFlow: MutableStateFlow<MjpegState?> = MutableStateFlow(null)
    internal val mjpegStateFlow: StateFlow<MjpegState?>
        get() = _mjpegStateFlow.asStateFlow()

    @MainThread
    @Throws(IllegalStateException::class)
    override fun getName(context: Context): String {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }
        return context.getString(R.string.mjpeg_stream_fragment_mode_local)
    }

    @MainThread
    @Throws(IllegalStateException::class)
    override fun getContentDescription(context: Context): String {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }
        return context.getString(R.string.mjpeg_stream_fragment_mode_local_description)
    }

    @MainThread
    @Throws(IllegalStateException::class)
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
        get() = requireNotNull(_scope).get()

    private var _scope: Scope? = null

    @MainThread
    @Throws(IllegalStateException::class)
    override fun createStreamingService(context: Context) {
        XLog.d(getLog("createStreamingService"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        if (_scope != null) {
            XLog.e(getLog("createStreamingService", "Scope exists"), IllegalStateException("Scope exists"))
            destroyStreamingService()
        }
        _scope = MjpegKoinScope().scope
        _mjpegStateFlow.value = MjpegState()
        MjpegService.startService(context, MjpegEvent.Intentable.StartService.toIntent(context))
    }

    @MainThread
    @Throws(IllegalStateException::class)
    override fun sendEvent(event: StreamingModule.AppEvent) {
        XLog.d(getLog("sendEvent", "Event $event"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (event) {
            is StreamingModule.AppEvent.StartStream ->
                requireNotNull(_scope).get<MjpegStreamingService>().sendEvent(MjpegStreamingService.InternalEvent.StartStream)

            is StreamingModule.AppEvent.StopStream ->
                requireNotNull(_scope).get<MjpegStreamingService>().sendEvent(MjpegEvent.Intentable.StopStream("User action: Button"))

            is StreamingModule.AppEvent.Exit -> {
                if (_scope != null) destroyStreamingService()
                event.callback()
            }

            is MjpegEvent.CreateStreamingService -> if (_scope == null) {
                XLog.e(getLog("sendEvent", "Scope already destroyed. Stopping Service"), IllegalStateException("Scope already destroyed. Stopping Service"))
                event.service.stopSelf()
                _streamingServiceIsActive.value = false
            } else if (streamingServiceIsActive.value) {
                XLog.e(getLog("sendEvent", "Service already started. Ignoring"), IllegalStateException("Service already started. Ignoring"))
            } else {
                requireNotNull(_scope).get<MjpegStreamingService> { parametersOf(event.service, _mjpegStateFlow) }.start()
                _streamingServiceIsActive.value = true
            }

            else -> requireNotNull(_scope).get<MjpegStreamingService>().sendEvent(event as MjpegEvent)
        }
    }

    @MainThread
    @Throws(IllegalStateException::class)
    override fun destroyStreamingService() {
        XLog.d(getLog("destroyStreamingService"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        if (_streamingServiceIsActive.value) requireNotNull(_scope).get<MjpegStreamingService>().destroy()
        _streamingServiceIsActive.value = false
        _mjpegStateFlow.value = null
        _scope?.close()
        _scope = null

        XLog.d(getLog("destroyStreamingService", "Done"))
    }
}