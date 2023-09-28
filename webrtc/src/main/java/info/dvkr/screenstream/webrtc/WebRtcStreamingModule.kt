package info.dvkr.screenstream.webrtc

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
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import info.dvkr.screenstream.webrtc.internal.WebRtcState
import info.dvkr.screenstream.webrtc.internal.WebRtcStreamingService
import info.dvkr.screenstream.webrtc.ui.WebRtcStreamingFragment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.koin.core.parameter.parametersOf
import org.koin.core.scope.Scope

@Single
@Named(WebRtcKoinQualifier)
public class WebRtcStreamingModule : StreamingModule {
    internal companion object {
        internal val Id: StreamingModule.Id = StreamingModule.Id("WEBRTC")
    }

    override val id: StreamingModule.Id = Id

    override val priority: Int = 20

    private val _streamingServiceIsActive: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val streamingServiceIsActive: StateFlow<Boolean>
        get() = _streamingServiceIsActive.asStateFlow()

    @MainThread
    @Throws(IllegalStateException::class)
    override fun getName(context: Context): String {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }
        return context.getString(R.string.webrtc_stream_fragment_mode_global)
    }

    @MainThread
    @Throws(IllegalStateException::class)
    override fun getContentDescription(context: Context): String {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }
        return context.getString(R.string.webrtc_stream_fragment_mode_global_description)
    }

    @MainThread
    @Throws(IllegalStateException::class)
    override fun showDescriptionDialog(context: Context, lifecycleOwner: LifecycleOwner) {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        MaterialDialog(context).show {
            lifecycleOwner(lifecycleOwner)
            icon(R.drawable.webrtc_ic_permission_dialog_24dp)
            title(R.string.webrtc_stream_fragment_mode_global)
            message(R.string.webrtc_stream_fragment_mode_global_details)
            positiveButton(android.R.string.ok)
        }
    }

    @MainThread
    override fun getFragmentClass(): Class<out Fragment> = WebRtcStreamingFragment::class.java

    private var _scope: Scope? = null

    internal val scope: Scope
        get() = requireNotNull(_scope)

    @MainThread
    @Throws(IllegalStateException::class)
    override fun createStreamingService(context: Context, mutableAppStateFlow: MutableStateFlow<StreamingModule.AppState>) {
        XLog.d(getLog("createStreamingService"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        if (_scope != null) destroyStreamingService()

        val newScope = WebRtcKoinScope().scope
        newScope.get<WebRtcStateFlowProvider> { parametersOf(mutableAppStateFlow, MutableStateFlow(WebRtcState())) }
        WebRtcService.startService(context, WebRtcEvent.Intentable.StartService.toIntent(context))
        _scope = newScope
    }

    @MainThread
    @Throws(IllegalStateException::class)
    override fun sendEvent(event: StreamingModule.AppEvent) {
        XLog.d(getLog("sendEvent", "Event $event"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        when (event) {
            is StreamingModule.AppEvent.StartStream ->
                scope.get<WebRtcStreamingService>().sendEvent(WebRtcStreamingService.InternalEvent.StartStream)

            is StreamingModule.AppEvent.StopStream ->
                scope.get<WebRtcStreamingService>().sendEvent(WebRtcEvent.Intentable.StopStream("User action: Button"))

            is StreamingModule.AppEvent.Exit -> {
                if (_scope != null) destroyStreamingService()
                event.callback()
            }

            is WebRtcEvent.CreateStreamingService -> if (_scope == null) {
                XLog.e(getLog("sendEvent", "Scope already destroyed. Stopping Service."))
                event.service.stopSelf()
                _streamingServiceIsActive.value = false
            } else {
                scope.get<WebRtcStreamingService> { parametersOf(scope, event.service) }.start()
                _streamingServiceIsActive.value = true
            }

            else -> scope.get<WebRtcStreamingService>().sendEvent(event as WebRtcEvent)
        }
    }

    @MainThread
    @Throws(IllegalStateException::class)
    override fun destroyStreamingService() {
        XLog.d(getLog("destroyStreamingService"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        if (_streamingServiceIsActive.value) scope.get<WebRtcStreamingService>().destroy()
        _streamingServiceIsActive.value = false
        _scope?.close()
        _scope = null

        XLog.d(getLog("destroyStreamingService", "Done"))
    }
}