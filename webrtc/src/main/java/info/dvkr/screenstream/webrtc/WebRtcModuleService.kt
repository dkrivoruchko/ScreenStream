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
import info.dvkr.screenstream.webrtc.ui.isStartupPolicyError
import io.screenstream.streaming.legacy.LegacyStreamingModuleAdapter
import io.screenstream.streaming.StreamingModuleManager
import org.koin.android.ext.android.inject
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * Creates one legacy controller for accepted core startup, before dispatching the backend's old commands.
 * Retains that original controller for this Service lifetime; lifecycle callbacks never wait for cleanup.
 */
public class WebRtcModuleService : StreamingModuleService() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, WebRtcModuleService::class.java).addIntentId().let { intent ->
            (context as? WebRtcModuleService)?.legacyOwner?.prepareCommand(intent) ?: intent
        }

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
            context.startService(intent)
        }
    }

    override val notificationIdForeground: Int = 200
    override val notificationIdError: Int = 210

    private val webRtcStreamingModule: WebRtcStreamingModuleLegacy by inject(WebRtcKoinQualifier, LazyThreadSafetyMode.NONE)

    private val legacyWebRtcStreamingModule: LegacyWebRtcStreamingModule by inject(
        org.koin.core.qualifier.named("LegacyWebRtcStreamingModule"), LazyThreadSafetyMode.NONE
    )

    private var legacyOwner: LegacyStreamingModuleAdapter.LegacyController? = null
    private val streamingModuleManager: StreamingModuleManager by inject(mode = LazyThreadSafetyMode.NONE)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == StreamingModuleManager.ACTION_START_MODULE) {
            streamingModuleManager.onServiceStart(service = this, intent = intent, existingController = legacyOwner) { runtime ->
                legacyWebRtcStreamingModule.createController(runtime, this).also { legacyOwner = it }
            }
            if (legacyOwner == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val originalOwner = legacyOwner
        if (originalOwner == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!originalOwner.acceptsCommand(intent)) return START_NOT_STICKY
        if (intent == null) {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("WebRtcModuleService.onStartCommand: intent = null. Stop self, startId: $startId"))
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
        when (webRtcEvent) {
            is WebRtcEvent.Intentable.StartService -> webRtcStreamingModule.onServiceStart(this, webRtcEvent.token)
            is WebRtcEvent.Intentable.StartProjection -> {
                XLog.i(getLog("onStartCommand", "SP_TRACE route=service_cached_permission stage=service_dispatch event=StartProjection startAttemptId=${webRtcEvent.startAttemptId} startId=$startId"))
                webRtcStreamingModule.startProjection(webRtcEvent.startAttemptId, webRtcEvent.intent)
            }
            is WebRtcEvent.Intentable.StopStream -> webRtcStreamingModule.sendEvent(webRtcEvent)
            WebRtcEvent.Intentable.RecoverError -> webRtcStreamingModule.sendEvent(webRtcEvent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))
        val originalOwner = legacyOwner
        legacyOwner = null
        try {
            super.onDestroy()
        } finally {
            originalOwner?.onServiceDestroyed()
        }
    }

    override fun onTimeout(startId: Int) {
        try {
            stopSelf()
        } finally {
            legacyOwner?.requestModuleShutdown()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        try {
            stopSelf()
        } finally {
            legacyOwner?.requestModuleShutdown()
        }
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

    internal fun showErrorNotification(error: WebRtcError, showRecoverAction: Boolean = true) {
        if (error is WebRtcError.NotificationPermissionRequired) return

        val startupPolicyError = error.isStartupPolicyError()
        if (error is WebRtcError.NetworkError && (error.cause is UnknownHostException || error.cause is ConnectException)) {
            XLog.i(getLog("showErrorNotification", "${error.javaClass.simpleName} ${error.cause}"))
        } else if (error is WebRtcError.PlayIntegrityError && error.isExpectedEnvironmentIssue()) {
            XLog.i(getLog("showErrorNotification", "Expected Play Integrity environment issue. code=${error.code}, message=${error.message}"))
        } else if (startupPolicyError) {
            XLog.i(getLog("showErrorNotification", "${error.javaClass.simpleName} ${error.cause}"))
        } else {
            XLog.e(getLog("showErrorNotification"), error)
        }

        showErrorNotification(
            message = error.toString(this),
            recoverIntent = if (showRecoverAction) WebRtcEvent.Intentable.RecoverError.toIntent(this) else null
        )
    }
}
