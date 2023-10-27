package info.dvkr.screenstream.mjpeg

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.RestrictTo
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.NotificationsManager
import info.dvkr.screenstream.common.StreamingModulesManager
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject

@RestrictTo(RestrictTo.Scope.LIBRARY)
public class MjpegService : Service() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, MjpegService::class.java)

        @Throws(IllegalStateException::class)
        internal fun startService(context: Context, intent: Intent) {
            XLog.d(getLog("MjpegService.startService", "Run intent: ${intent.extras}"))
            val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
            if (importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                XLog.i(getLog("MjpegService.startService", "RunningAppProcessInfo.importance: $importance"))
            }
            context.startService(intent)
        }
        //ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        //ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private val streamingModulesManager: StreamingModulesManager by inject(mode = LazyThreadSafetyMode.NONE)
    private val notificationsManager: NotificationsManager by inject(mode = LazyThreadSafetyMode.NONE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        XLog.d(getLog("onCreate"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mjpegEvent = MjpegEvent.Intentable.fromIntent(intent) ?: run {
            XLog.e(getLog("onStartCommand", "intent = null"), IllegalArgumentException("MjpegService.onStartCommand: intent = null"))
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "MjpegEvent: $mjpegEvent"))

        val success = when (mjpegEvent) {
            is MjpegEvent.Intentable.StartService -> streamingModulesManager.sendEvent(MjpegEvent.CreateStreamingService(this))
            is MjpegEvent.Intentable.StopStream -> streamingModulesManager.sendEvent(mjpegEvent)
            MjpegEvent.Intentable.RecoverError -> streamingModulesManager.sendEvent(mjpegEvent)
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
        streamingModulesManager.deactivate(MjpegStreamingModule.Id)
        super.onDestroy()
    }
}