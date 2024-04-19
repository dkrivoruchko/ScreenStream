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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import java.net.ConnectException
import java.net.UnknownHostException

public class WebRtcModuleService : StreamingModuleService() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, WebRtcModuleService::class.java)

        @Throws(ServiceStartNotAllowedException::class)
        internal fun startService(context: Context, intent: Intent) {
            XLog.d(getLog("WebRtcModuleService.startService", "Run intent: ${intent.extras}"))
            val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
            XLog.i(getLog("WebRtcModuleService.startService", "RunningAppProcessInfo.importance: $importance"))
            context.startService(intent)
        }
    }

    override val notificationIdForeground: Int = 200
    override val notificationIdError: Int = 210

    private val webRtcStreamingModule: WebRtcStreamingModule by inject(named(WebRtcKoinQualifier), LazyThreadSafetyMode.NONE)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("WebRtcModuleService.onStartCommand: intent = null. Stop self, startId: $startId"))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "WebRtcEvent.intent: ${intent.extras}"))
        val webRtcEvent = WebRtcEvent.Intentable.fromIntent(intent) ?: run {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("WebRtcModuleService.onStartCommand: WebRtcEvent = null, startId: $startId, $intent"))
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "WebRtcEvent: $webRtcEvent, startId: $startId"))

        if (streamingModuleManager.isActive(WebRtcStreamingModule.Id)) {
            when (webRtcEvent) {
                is WebRtcEvent.Intentable.StartService -> webRtcStreamingModule.onServiceStart(this)
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

    @Throws(WebRtcError.NotificationPermissionRequired::class, IllegalStateException::class)
    internal fun startForeground() {
        XLog.d(getLog("startForeground"))

        if (notificationHelper.notificationPermissionGranted(this) && notificationHelper.foregroundNotificationsEnabled()) {
            val stopIntent = WebRtcEvent.Intentable.StopStream("WebRtcModuleService. User action: Notification").toIntent(this)
            startForeground(stopIntent)
        } else {
            throw WebRtcError.NotificationPermissionRequired
        }
    }

    internal fun showErrorNotification(error: WebRtcError) {
        if (error is WebRtcError.NotificationPermissionRequired) return

        if (error is WebRtcError.NetworkError && (error.cause is UnknownHostException || error.cause is ConnectException)) {
            XLog.i(getLog("showErrorNotification", "${error.javaClass.simpleName} ${error.cause}"))
        } else {
            XLog.e(getLog("showErrorNotification"), error)
        }

        showErrorNotification(
            message = error.toString(this),
            recoverIntent = WebRtcEvent.Intentable.RecoverError.toIntent(this)
        )
    }
}