package info.dvkr.screenstream.webrtc

import android.app.ActivityManager
import android.app.ServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModuleService
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import info.dvkr.screenstream.webrtc.ui.WebRtcError
import info.dvkr.screenstream.webrtc.ui.isExpectedEnvironmentIssue
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import java.net.ConnectException
import java.net.UnknownHostException

public class WebRtcModuleService : StreamingModuleService() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, WebRtcModuleService::class.java).addIntentId()

        @Throws(ServiceStartNotAllowedException::class)
        internal fun startService(context: Context, intent: Intent) {
            XLog.d(getLog("WebRtcModuleService.startService", "Run intent: ${intent.extras}"))
            val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
            XLog.i(getLog("WebRtcModuleService.startService", "RunningAppProcessInfo.importance: $importance"))
            context.startService(intent)
        }

        @Throws(ServiceStartNotAllowedException::class)
        internal fun dispatchProjectionIntent(context: Context, startAttemptId: String, permissionIntent: Intent) {
            val intent = WebRtcEvent.Intentable.StartProjection(startAttemptId, permissionIntent).toIntent(context)
            XLog.d(getLog("WebRtcModuleService.dispatchProjectionIntent", "Run intent: ${intent.extras}"))
            val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
            XLog.i(getLog("WebRtcModuleService.dispatchProjectionIntent", "RunningAppProcessInfo.importance: $importance"))
            XLog.i(getLog("WebRtcModuleService.dispatchProjectionIntent", "SP_TRACE route=service_cached_permission stage=service_command startAttemptId=$startAttemptId importance=$importance"))
            runCatching {
                context.startService(intent)
            }.onFailure {
                XLog.e(
                    getLog(
                        "WebRtcModuleService.dispatchProjectionIntent",
                        "SP_TRACE route=service_cached_permission stage=service_command_failed startAttemptId=$startAttemptId importance=$importance"
                    ),
                    it
                )
            }.getOrThrow()
        }
    }

    override val notificationIdForeground: Int = 200
    override val notificationIdError: Int = 210

    private val webRtcStreamingModule: WebRtcStreamingModule by inject(WebRtcKoinQualifier, LazyThreadSafetyMode.NONE)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("WebRtcModuleService.onStartCommand: intent = null. Stop self, startId: $startId"))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "WebRtcModuleService.INTENT_ID: ${intent.getStringExtra(INTENT_ID)}"))

        val webRtcEvent = WebRtcEvent.Intentable.fromIntent(intent) ?: run {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("WebRtcModuleService.onStartCommand: WebRtcEvent = null, startId: $startId"))
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "WebRtcEvent: $webRtcEvent, startId: $startId"))

        val shouldDedupe = webRtcEvent is WebRtcEvent.Intentable.StartService
        if (shouldDedupe && isDuplicateIntent(intent)) {
            XLog.i(getLog("onStartCommand", "Duplicate intent for $webRtcEvent. Ignoring. startId: $startId"))
            return START_NOT_STICKY
        }

        if ((flags and START_FLAG_REDELIVERY) != 0) {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("WebRtcModuleService.onStartCommand: redelivered intent, WebRtcEvent: $webRtcEvent, startId: $startId, $intent"))
            return START_NOT_STICKY
        }

        if (streamingModuleManager.isActive(WebRtcStreamingModule.Id)) {
            when (webRtcEvent) {
                is WebRtcEvent.Intentable.StartService -> webRtcStreamingModule.onServiceStart(this, webRtcEvent.token)
                is WebRtcEvent.Intentable.StartProjection -> {
                    XLog.i(getLog("onStartCommand", "SP_TRACE route=service_cached_permission stage=service_dispatch event=StartProjection startAttemptId=${webRtcEvent.startAttemptId} startId=$startId"))
                    webRtcStreamingModule.startProjection(webRtcEvent.startAttemptId, webRtcEvent.intent)
                }
                is WebRtcEvent.Intentable.StopStream -> webRtcStreamingModule.sendEvent(webRtcEvent)
                WebRtcEvent.Intentable.RecoverError -> webRtcStreamingModule.sendEvent(webRtcEvent)
            }
        } else {
            XLog.w(getLog("onStartCommand", "Not active module. Stop self, startId: $startId"))
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))
        runBlocking { streamingModuleManager.stopModule(WebRtcStreamingModule.Id) }
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
            WebRtcEvent.Intentable.StopStream("WebRtcModuleService. User action: Notification").toIntent(this),
            fgsType
        )
    }

    internal fun showErrorNotification(error: WebRtcError) {
        if (error is WebRtcError.NotificationPermissionRequired) return

        if (error is WebRtcError.NetworkError && (error.cause is UnknownHostException || error.cause is ConnectException)) {
            XLog.i(getLog("showErrorNotification", "${error.javaClass.simpleName} ${error.cause}"))
        } else if (error is WebRtcError.PlayIntegrityError && error.isExpectedEnvironmentIssue()) {
            XLog.i(getLog("showErrorNotification", "Expected Play Integrity environment issue. code=${error.code}, message=${error.message}"))
        } else {
            XLog.e(getLog("showErrorNotification"), error)
        }

        showErrorNotification(
            message = error.toString(this),
            recoverIntent = WebRtcEvent.Intentable.RecoverError.toIntent(this)
        )
    }
}
