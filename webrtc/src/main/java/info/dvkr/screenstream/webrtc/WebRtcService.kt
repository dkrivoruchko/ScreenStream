package info.dvkr.screenstream.webrtc

import android.app.ActivityManager
import android.app.Service
import android.app.ServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.RestrictTo
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.NotificationsManager
import info.dvkr.screenstream.common.StreamingModulesManager
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject

@RestrictTo(RestrictTo.Scope.LIBRARY)
public class WebRtcService : Service() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, WebRtcService::class.java)

        @Throws(ServiceStartNotAllowedException::class)
        internal fun startService(context: Context, intent: Intent) {
            XLog.d(getLog("WebRtcService.startService", "Run intent: ${intent.extras}"))
            val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
            if (importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                XLog.i(getLog("MjpegService.startService", "RunningAppProcessInfo.importance: $importance"))
            }
            context.startService(intent)
        }
    }

    private val streamingModulesManager: StreamingModulesManager by inject(mode = LazyThreadSafetyMode.NONE)
    private val notificationsManager: NotificationsManager by inject(mode = LazyThreadSafetyMode.NONE)

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


        val success = when (webRtcEvent) {
            is WebRtcEvent.Intentable.StartService -> streamingModulesManager.sendEvent(WebRtcEvent.CreateStreamingService(this))
            is WebRtcEvent.Intentable.StopStream -> streamingModulesManager.sendEvent(webRtcEvent)
            WebRtcEvent.Intentable.RecoverError -> streamingModulesManager.sendEvent(webRtcEvent)
        }

        if (success.not()) { // No active module
            XLog.w(getLog("onStartCommand", "No active module"))
            notificationsManager.hideForegroundNotification(this)
            notificationsManager.hideErrorNotification()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))
        streamingModulesManager.deactivate(WebRtcStreamingModule.Id)
        super.onDestroy()
    }
}