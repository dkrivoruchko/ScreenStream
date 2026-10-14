package info.dvkr.screenstream.vnc

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModuleService
import info.dvkr.screenstream.vnc.internal.VncEvent
import info.dvkr.screenstream.vnc.ui.VncError
import info.dvkr.screenstream.vnc.ui.isStartupPolicyError
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

public class VncModuleService : StreamingModuleService() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, VncModuleService::class.java).addIntentId()

        internal fun startService(context: Context, intent: Intent) {
            XLog.d(getLog("VncModuleService.startService", "Run intent: ${intent.extras}"))
            val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
            XLog.i(getLog("VncModuleService.startService", "RunningAppProcessInfo.importance: $importance"))
            context.startService(intent)
        }

        internal fun dispatchProjectionIntent(context: Context, startAttemptId: String, permissionIntent: Intent) {
            val intent = VncEvent.Intentable.StartProjection(startAttemptId, permissionIntent).toIntent(context)
            XLog.d(getLog("VncModuleService.dispatchProjectionIntent", "Run intent: ${intent.extras}"))
            val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
            XLog.i(getLog("VncModuleService.dispatchProjectionIntent", "RunningAppProcessInfo.importance: $importance"))
            XLog.i(getLog("VncModuleService.dispatchProjectionIntent", "SP_TRACE route=service_cached_permission stage=service_command startAttemptId=$startAttemptId importance=$importance"))
            context.startService(intent)
        }
    }

    override val notificationIdForeground: Int = 400
    override val notificationIdError: Int = 410

    private val vncStreamingModule: VncStreamingModule by inject(VncKoinQualifier, LazyThreadSafetyMode.NONE)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("VncModuleService.onStartCommand: intent = null. Stop self, startId: $startId"))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "VncModuleService.INTENT_ID: ${intent.getStringExtra(INTENT_ID)}"))

        val vncEvent = VncEvent.Intentable.fromIntent(intent) ?: run {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("VncModuleService.onStartCommand: VncEvent = null, startId: $startId"))
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "VncEvent: $vncEvent, startId: $startId"))

        val shouldDedupe = vncEvent is VncEvent.Intentable.StartService
        if (shouldDedupe && isDuplicateIntent(intent)) {
            XLog.i(getLog("onStartCommand", "Duplicate intent for $vncEvent. Ignoring. startId: $startId"))
            return START_NOT_STICKY
        }

        if ((flags and START_FLAG_REDELIVERY) != 0) {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("VncModuleService.onStartCommand: redelivered intent, VncEvent: $vncEvent, startId: $startId, $intent"))
            return START_NOT_STICKY
        }

        if (streamingModuleManager.isActive(VncStreamingModule.Id)) {
            when (vncEvent) {
                is VncEvent.Intentable.StartService -> vncStreamingModule.onServiceStart(this, vncEvent.token)
                is VncEvent.Intentable.StartProjection -> {
                    XLog.i(getLog("onStartCommand", "SP_TRACE route=service_cached_permission stage=service_dispatch event=StartProjection startAttemptId=${vncEvent.startAttemptId} startId=$startId"))
                    vncStreamingModule.startProjection(vncEvent.startAttemptId, vncEvent.intent)
                }
                is VncEvent.Intentable.StopStream -> vncStreamingModule.sendEvent(vncEvent)
                VncEvent.Intentable.RecoverError -> vncStreamingModule.sendEvent(vncEvent)
            }
        } else {
            XLog.w(getLog("onStartCommand", "Not active module. Stop self, startId: $startId"))
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))
        runBlocking { streamingModuleManager.stopModule(VncStreamingModule.Id) }
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
            VncEvent.Intentable.StopStream("VncModuleService. User action: Notification").toIntent(this),
            fgsType
        )
    }

    internal fun showErrorNotification(error: VncError) {
        if (error is VncError.NotificationPermissionRequired || error is VncError.LocalNetworkPermissionRequired) return

        val startupPolicyError = error.isStartupPolicyError()
        if (error is VncError.AddressNotFoundException || error is VncError.AddressInUseException || startupPolicyError) {
            XLog.i(getLog("showErrorNotification", "${error.javaClass.simpleName} ${error.cause}"))
        } else {
            XLog.e(getLog("showErrorNotification"), error)
        }

        showErrorNotification(
            message = error.toString(this),
            recoverIntent = if (startupPolicyError) null else VncEvent.Intentable.RecoverError.toIntent(this)
        )
    }
}
