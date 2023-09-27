package info.dvkr.screenstream.webrtc

import android.app.Service
import android.app.ServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.RestrictTo
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.StreamingModulesManager
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import kotlin.jvm.Throws

@RestrictTo(RestrictTo.Scope.LIBRARY)
public class WebRtcService : Service() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, WebRtcService::class.java)

        @Throws(ServiceStartNotAllowedException::class)
        internal fun startService(context: Context, intent: Intent) {
            XLog.d(getLog("WebRtcService.startService", "Run intent: ${intent.extras}"))
            context.startService(intent)
        }
    }

    private val streamingModulesManager: StreamingModulesManager by inject(mode = LazyThreadSafetyMode.NONE)
    private val webRtcStreamingModule: WebRtcStreamingModule by inject(named(WebRtcKoinQualifier), LazyThreadSafetyMode.NONE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        XLog.d(getLog("onCreate"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val webRtcEvent = WebRtcEvent.Intentable.fromIntent(intent) ?: run {
            XLog.e(getLog("onStartCommand", "intent = null"), IllegalArgumentException("WebRtcService.onStartCommand: intent = null"))
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "WebRtcEvent: $webRtcEvent"))


        when (webRtcEvent) {
            is WebRtcEvent.Intentable.StartService -> webRtcStreamingModule.sendEvent(WebRtcEvent.CreateStreamingService(this))
            is WebRtcEvent.Intentable.StopStream -> webRtcStreamingModule.sendEvent(webRtcEvent)
            WebRtcEvent.Intentable.RecoverError -> webRtcStreamingModule.sendEvent(webRtcEvent)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))
        streamingModulesManager.deactivate(WebRtcStreamingModule.Id)
        super.onDestroy()
    }
}