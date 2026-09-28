package io.screenstream.mjpeg

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.koin.android.ext.android.inject

/** Android callback adapter; injects the shared module host without constructing the module API. */
public class MjpegStreamingModuleService : Service() {
    private val moduleHost: MjpegStreamingModuleHost by inject(mode = LazyThreadSafetyMode.NONE)

    override fun onCreate() {
        super.onCreate()
        moduleHost.onServiceCreated(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        moduleHost.onServiceStartCommand(this, intent, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        moduleHost.onServiceDestroyed(this)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) {
        moduleHost.onServiceTimeout(this, startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        moduleHost.onServiceTimeout(this, startId, fgsType)
    }
}
