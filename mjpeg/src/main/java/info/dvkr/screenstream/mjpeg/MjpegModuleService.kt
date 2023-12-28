package info.dvkr.screenstream.mjpeg

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.AbstractModuleService
import info.dvkr.screenstream.mjpeg.internal.MjpegError
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

public class MjpegModuleService : AbstractModuleService() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, MjpegModuleService::class.java)

        internal fun startService(context: Context, intent: Intent) {
            XLog.d(getLog("MjpegModuleService.startService", "Run intent: ${intent.extras}"))
            val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
            XLog.i(getLog("MjpegModuleService.startService", "RunningAppProcessInfo.importance: $importance"))
            context.startService(intent)
        }
    }

    override val notificationIdForeground: Int = 100
    override val notificationIdError: Int = 110

    private val mjpegStreamingModule: MjpegStreamingModule by inject(named(MjpegKoinQualifier), LazyThreadSafetyMode.NONE)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("MjpegModuleService.onStartCommand: intent = null. Stop self, startId: $startId"))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "MjpegEvent.intent: ${intent.extras}"))
        val mjpegEvent = MjpegEvent.Intentable.fromIntent(intent) ?: run {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("MjpegModuleService.onStartCommand: MjpegEvent = null, startId: $startId, $intent"))
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "MjpegEvent: $mjpegEvent, startId: $startId"))

        if (streamingModulesManager.isActive(MjpegStreamingModule.Id)) {
            when (mjpegEvent) {
                is MjpegEvent.Intentable.StartService -> mjpegStreamingModule.onServiceStart(this)
                is MjpegEvent.Intentable.StopStream -> mjpegStreamingModule.sendEvent(mjpegEvent)
                MjpegEvent.Intentable.RecoverError -> mjpegStreamingModule.sendEvent(mjpegEvent)
            }
        } else {
            XLog.w(getLog("onStartCommand", "Not active module. Stop self, startId: $startId"))
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))
        runBlocking { streamingModulesManager.stopModule(MjpegStreamingModule.Id) }
        super.onDestroy()
    }

    @Throws(MjpegError.NotificationPermissionRequired::class, IllegalStateException::class)
    internal fun startForeground() {
        XLog.d(getLog("startForeground"))

        if (notificationHelper.notificationPermissionGranted(this).not()) throw MjpegError.NotificationPermissionRequired

        val stopIntent = MjpegEvent.Intentable.StopStream("MjpegModuleService. User action: Notification").toIntent(this)
        startForeground(stopIntent)
    }

    internal fun showErrorNotification(error: MjpegError) {
        if (error is MjpegError.AddressNotFoundException || error is MjpegError.AddressInUseException) {
            XLog.i(getLog("showErrorNotification", "${error.javaClass.simpleName} ${error.cause}"))
        } else {
            XLog.e(getLog("showErrorNotification"), error)
        }

        val message = error.toString(this)
        val recoverIntent = MjpegEvent.Intentable.RecoverError.toIntent(this)
        showErrorNotification(message, recoverIntent)
    }
}