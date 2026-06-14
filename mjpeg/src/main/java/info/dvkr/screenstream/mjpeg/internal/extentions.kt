package info.dvkr.screenstream.mjpeg.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlin.time.Duration.Companion.milliseconds

internal fun Context.getFileFromAssets(fileName: String): ByteArray {
    XLog.d(getLog("getFileFromAssets", fileName))
    return assets.open(fileName).use { inputStream -> inputStream.readBytes() }
        .also { if (it.isEmpty()) throw IllegalStateException("$fileName is empty") }
}

internal fun <T> Flow<T>.listenForChange(scope: CoroutineScope, drop: Int = 0, action: suspend (T) -> Unit) =
    distinctUntilChanged().drop(drop).onEach { action(it) }.launchIn(scope)

internal fun Int.toColorHexString(): String = "#%06X".format(0xFFFFFF and this)

@OptIn(FlowPreview::class)
@Suppress("DEPRECATION")
internal fun Context.startListening(serviceJob: Job, onScreenOff: () -> Unit, onConnectionChanged: () -> Unit) {
    XLog.d(this@startListening.getLog("startListening"))

    val connectionChangeMutableStateFlow = MutableStateFlow<Long>(0)

    connectionChangeMutableStateFlow
        .onStart { XLog.v(this@startListening.getLog("startListening", "onStart")) }
        .debounce(500.milliseconds)
        .onEach {
            XLog.d(this@startListening.getLog("startListening", "onEach: $it"))
            onConnectionChanged.invoke()
        }
        .onCompletion { XLog.v(this@startListening.getLog("startListening", "onCompletion")) }
        .launchIn(CoroutineScope(serviceJob + Dispatchers.Default))

    val intentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
    }

    val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            XLog.v(this@startListening.getLog("BroadcastReceiver.onReceive", "Action: ${intent?.action}"))

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff.invoke()

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

    val connectivityManager = getSystemService(ConnectivityManager::class.java)

    connectivityManager.registerDefaultNetworkCallback(networkCallback)

    serviceJob.invokeOnCompletion {
        XLog.d(getLog("invokeOnCompletion", "unregisterNetworkCallback"))
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}
