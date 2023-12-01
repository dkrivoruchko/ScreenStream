package info.dvkr.screenstream.mjpeg.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

@OptIn(FlowPreview::class)
@Suppress("DEPRECATION")
internal fun Context.startListening(serviceJob: Job, onScreenOff: () -> Unit, onConnectionChanged: () -> Unit) {
    XLog.d(this@startListening.getLog("startListening"))

    val connectionChangeMutableStateFlow = MutableStateFlow<Long>(0)

    connectionChangeMutableStateFlow
        .onStart { XLog.v(this@startListening.getLog("startListening", "onStart")) }
        .debounce(500)
        .onEach {
            XLog.d(this@startListening.getLog("startListening", "onEach: $it"))
            onConnectionChanged.invoke()
        }
        .onCompletion { XLog.v(this@startListening.getLog("startListening", "onCompletion")) }
        .launchIn(CoroutineScope(serviceJob + Dispatchers.Default))

    val intentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
    }

    val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            XLog.v(this@startListening.getLog("BroadcastReceiver.onReceive", "Action: ${intent?.action}"))

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff.invoke()

                WifiManager.WIFI_STATE_CHANGED_ACTION,
                ConnectivityManager.CONNECTIVITY_ACTION,
                "android.net.wifi.WIFI_AP_STATE_CHANGED" -> connectionChangeMutableStateFlow.value = System.currentTimeMillis()
            }
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
    else
        registerReceiver(broadcastReceiver, intentFilter)

    serviceJob.invokeOnCompletion {
        XLog.d(getLog("invokeOnCompletion", "unregisterBroadcastReceiver"))
        runCatching { unregisterReceiver(broadcastReceiver) }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                XLog.v(this@startListening.getLog("onAvailable", "Network: $network"))
                connectionChangeMutableStateFlow.value = System.currentTimeMillis()
            }

            override fun onLost(network: Network) {
                XLog.v(this@startListening.getLog("onLost", "Network: $network"))
                connectionChangeMutableStateFlow.value = System.currentTimeMillis()
            }
        }

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        serviceJob.invokeOnCompletion {
            XLog.d(getLog("invokeOnCompletion", "unregisterNetworkCallback"))
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }
}