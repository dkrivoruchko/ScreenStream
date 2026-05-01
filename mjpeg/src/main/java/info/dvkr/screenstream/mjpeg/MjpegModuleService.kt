package info.dvkr.screenstream.mjpeg

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModuleService
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import info.dvkr.screenstream.mjpeg.ui.MjpegError
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

public class MjpegModuleService : StreamingModuleService() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, MjpegModuleService::class.java).addIntentId()

        internal fun startService(context: Context, intent: Intent) {
            XLog.d(getLog("MjpegModuleService.startService", "Run intent: ${intent.extras}"))
            val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
            XLog.i(getLog("MjpegModuleService.startService", "RunningAppProcessInfo.importance: $importance"))
            context.startService(intent)
        }

        internal fun dispatchProjectionIntent(context: Context, startAttemptId: String, permissionIntent: Intent) {
            val intent = MjpegEvent.Intentable.StartProjection(startAttemptId, permissionIntent).toIntent(context)
            XLog.d(getLog("MjpegModuleService.dispatchProjectionIntent", "Run intent: ${intent.extras}"))
            val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
            XLog.i(getLog("MjpegModuleService.dispatchProjectionIntent", "RunningAppProcessInfo.importance: $importance"))
            XLog.i(getLog("MjpegModuleService.dispatchProjectionIntent", "SP_TRACE route=service_cached_permission stage=service_command startAttemptId=$startAttemptId importance=$importance"))
            runCatching {
                context.startService(intent)
            }.onFailure {
                XLog.e(
                    getLog(
                        "MjpegModuleService.dispatchProjectionIntent",
                        "SP_TRACE route=service_cached_permission stage=service_command_failed startAttemptId=$startAttemptId importance=$importance"
                    ),
                    it
                )
            }.getOrThrow()
        }
    }

    override val notificationIdForeground: Int = 100
    override val notificationIdError: Int = 110

    private val mjpegStreamingModule: MjpegStreamingModule by inject(MjpegKoinQualifier, LazyThreadSafetyMode.NONE)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("MjpegModuleService.onStartCommand: intent = null. Stop self, startId: $startId"))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "MjpegModuleService.INTENT_ID: ${intent.getStringExtra(INTENT_ID)}"))

        val mjpegEvent = MjpegEvent.Intentable.fromIntent(intent) ?: run {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("MjpegModuleService.onStartCommand: MjpegEvent = null, startId: $startId"))
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "MjpegEvent: $mjpegEvent, startId: $startId"))

        val shouldDedupe = mjpegEvent is MjpegEvent.Intentable.StartService
        if (shouldDedupe && isDuplicateIntent(intent)) {
            XLog.i(getLog("onStartCommand", "Duplicate intent for $mjpegEvent. Ignoring. startId: $startId"))
            return START_NOT_STICKY
        }

        if ((flags and START_FLAG_REDELIVERY) != 0) {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("MjpegModuleService.onStartCommand: redelivered intent, MjpegEvent: $mjpegEvent, startId: $startId, $intent"))
            return START_NOT_STICKY
        }

        if (streamingModuleManager.isActive(MjpegStreamingModule.Id)) {
            when (mjpegEvent) {
                is MjpegEvent.Intentable.StartService -> mjpegStreamingModule.onServiceStart(this, mjpegEvent.token)
                is MjpegEvent.Intentable.StartProjection -> {
                    XLog.i(getLog("onStartCommand", "SP_TRACE route=service_cached_permission stage=service_dispatch event=StartProjection startAttemptId=${mjpegEvent.startAttemptId} startId=$startId"))
                    mjpegStreamingModule.startProjection(mjpegEvent.startAttemptId, mjpegEvent.intent)
                }
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
        runBlocking { streamingModuleManager.stopModule(MjpegStreamingModule.Id) }
        super.onDestroy()
    }

    @Throws(IllegalStateException::class)
    internal fun startForeground(fgsType: Int) {
        XLog.d(
            getLog(
                "startForeground",
                "fgsType=$fgsType notificationPermissionGranted=${notificationHelper.notificationPermissionGranted(this)} " +
                        "foregroundNotificationsEnabled=${notificationHelper.foregroundNotificationsEnabled()}"
            )
        )

        startForeground(
            MjpegEvent.Intentable.StopStream("MjpegModuleService. User action: Notification").toIntent(this),
            fgsType
        )
    }

    internal fun showErrorNotification(error: MjpegError) {
        if (error is MjpegError.NotificationPermissionRequired) return

        if (error is MjpegError.AddressNotFoundException || error is MjpegError.AddressInUseException) {
            XLog.i(getLog("showErrorNotification", "${error.javaClass.simpleName} ${error.cause}"))
        } else {
            XLog.e(getLog("showErrorNotification"), error)
        }

        showErrorNotification(
            message = error.toString(this),
            recoverIntent = MjpegEvent.Intentable.RecoverError.toIntent(this)
        )
    }
}
