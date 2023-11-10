package info.dvkr.screenstream.mjpeg

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.AbstractService
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.internal.MjpegError
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

public class MjpegService : AbstractService() {

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

    override val notificationIdForeground: Int = 100
    override val notificationIdError: Int = 110

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
            XLog.w(getLog("onStartCommand", "No active module. Stop self"))
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))
        streamingModulesManager.deactivate(MjpegStreamingModule.Id)
        super.onDestroy()
    }

    @Throws(MjpegError.NotificationPermissionRequired::class, IllegalStateException::class)
    internal fun startForeground() {
        XLog.d(getLog("startForeground"))

        if (notificationHelper.notificationPermissionGranted(this).not()) throw MjpegError.NotificationPermissionRequired

        val stopIntent = MjpegEvent.Intentable.StopStream("MjpegService. User action: Notification").toIntent(this)
        startForeground(stopIntent)
    }

    internal fun showErrorNotification(error: MjpegError) {
        XLog.d(getLog("showErrorNotification", "${error.javaClass.simpleName} ${error.cause?.stackTrace}"))
        XLog.e(getLog("showErrorNotification"), error) //TODO Wait for prod logs

        val message = error.toString(this)
        val recoverIntent = MjpegEvent.Intentable.RecoverError.toIntent(this)
        showErrorNotification(message, recoverIntent)
    }
}