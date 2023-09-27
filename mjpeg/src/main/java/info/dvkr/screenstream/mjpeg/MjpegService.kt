package info.dvkr.screenstream.mjpeg

import android.app.Service
import android.app.ServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.RestrictTo
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.StreamingModulesManager
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import kotlin.jvm.Throws

@RestrictTo(RestrictTo.Scope.LIBRARY)
public class MjpegService : Service() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, MjpegService::class.java)

        @Throws(ServiceStartNotAllowedException::class)
        internal fun startService(context: Context, intent: Intent) {
            XLog.d(getLog("MjpegService.startService", "Run intent: ${intent.extras}"))
            context.startService(intent)
        }
    }

    private val streamingModulesManager: StreamingModulesManager by inject(mode = LazyThreadSafetyMode.NONE)
    private val mjpegStreamingModule: MjpegStreamingModule by inject(named(MjpegKoinQualifier), LazyThreadSafetyMode.NONE)

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

        when (mjpegEvent) {
            is MjpegEvent.Intentable.StartService -> mjpegStreamingModule.sendEvent(MjpegEvent.CreateStreamingService(this))
            is MjpegEvent.Intentable.StopStream -> mjpegStreamingModule.sendEvent(mjpegEvent)
            MjpegEvent.Intentable.RecoverError -> mjpegStreamingModule.sendEvent(mjpegEvent)
        }


        return START_NOT_STICKY
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))
        streamingModulesManager.deactivate(MjpegStreamingModule.Id)
        super.onDestroy()
    }
}