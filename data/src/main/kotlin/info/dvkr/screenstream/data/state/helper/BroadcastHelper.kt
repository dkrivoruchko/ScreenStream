package info.dvkr.screenstream.data.state.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.other.getLog
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class BroadcastHelper(context: Context, private val onError: (AppError) -> Unit) : CoroutineScope {

    private val applicationContext: Context = context.applicationContext
    private val supervisorJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = supervisorJob + Dispatchers.Main + CoroutineExceptionHandler { _, throwable ->
            XLog.e(getLog("onCoroutineException"), throwable)
            onError(FatalError.CoroutineException)
        }

    // Registering receiver for screen off messages and network & WiFi changes
    private val intentFilter: IntentFilter by lazy {
        IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
    }

    private var isConnectionEventScheduled: Boolean = false
    private var isFirstConnectionEvent: Boolean = true
    private var broadcastReceiver: BroadcastReceiver? = null

    fun registerReceiver(onScreenOff: () -> Unit, onConnectionChanged: () -> Unit) {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                XLog.d(this@BroadcastHelper.getLog("onReceive", "Action: ${intent?.action}"))

                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()

                    WifiManager.WIFI_STATE_CHANGED_ACTION, ConnectivityManager.CONNECTIVITY_ACTION -> {
                        isConnectionEventScheduled.not() || return
                        isConnectionEventScheduled = true
                        launch {
                            delay(1000)
                            if (isActive) {
                                isConnectionEventScheduled = false
                                if (isFirstConnectionEvent) isFirstConnectionEvent = false
                                else onConnectionChanged()
                            }
                        }
                    }
                }
            }
        }

        applicationContext.registerReceiver(broadcastReceiver, intentFilter)
    }

    fun unregisterReceiver() {
        applicationContext.unregisterReceiver(broadcastReceiver)
        coroutineContext.cancelChildren()
    }
}