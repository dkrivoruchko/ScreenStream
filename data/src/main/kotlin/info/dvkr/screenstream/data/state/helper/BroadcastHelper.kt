package info.dvkr.screenstream.data.state.helper

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.other.getLog
import kotlinx.coroutines.*

internal sealed class BroadcastHelper(context: Context) {

    companion object {
        fun getInstance(context: Context): BroadcastHelper {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                NougatBroadcastHelper(context)
            else
                LegacyBroadcastHelper(context)
        }
    }

    protected val applicationContext: Context = context.applicationContext
    private val coroutineScope = CoroutineScope(Job() + Dispatchers.Main.immediate)

    protected abstract val intentFilter: IntentFilter
    protected abstract val broadcastReceiver: BroadcastReceiver
    private lateinit var onScreenOff: () -> Unit
    private lateinit var onConnectionChanged: () -> Unit

    fun startListening(onScreenOff: () -> Unit, onConnectionChanged: () -> Unit) {
        this.onScreenOff = onScreenOff
        this.onConnectionChanged = onConnectionChanged
        applicationContext.registerReceiver(broadcastReceiver, intentFilter)
    }

    fun stopListening() {
        applicationContext.unregisterReceiver(broadcastReceiver)
        coroutineScope.cancel()
    }

    protected fun onScreenIntentAction() {
        if (::onScreenOff.isInitialized) onScreenOff.invoke()
    }

    private var isConnectionEventScheduled: Boolean = false
    private var isFirstConnectionEvent: Boolean = true

    protected fun onConnectivityIntentAction() {
        isConnectionEventScheduled.not() || return
        isConnectionEventScheduled = true
        coroutineScope.launch {
            delay(1000)
            isConnectionEventScheduled = false
            if (isFirstConnectionEvent) isFirstConnectionEvent = false
            else if (::onConnectionChanged.isInitialized) onConnectionChanged.invoke()
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private class NougatBroadcastHelper(context: Context) : BroadcastHelper(context) {

        override val intentFilter: IntentFilter by lazy {
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            }
        }

        override val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                XLog.d(this@NougatBroadcastHelper.getLog("onReceive", "Action: ${intent?.action}"))

                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenIntentAction()
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> onConnectivityIntentAction()
                }
            }
        }
    }

    @Suppress("Deprecation")
    private class LegacyBroadcastHelper(context: Context) : BroadcastHelper(context) {

        override val intentFilter: IntentFilter by lazy {
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            }
        }

        override val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                XLog.d(this@LegacyBroadcastHelper.getLog("onReceive", "Action: ${intent?.action}"))

                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenIntentAction()

                    WifiManager.WIFI_STATE_CHANGED_ACTION,
                    ConnectivityManager.CONNECTIVITY_ACTION,
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> onConnectivityIntentAction()
                }
            }
        }
    }
}