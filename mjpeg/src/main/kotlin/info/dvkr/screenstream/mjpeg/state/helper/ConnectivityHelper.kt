package info.dvkr.screenstream.mjpeg.state.helper

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

internal sealed class ConnectivityHelper {
    companion object {
        fun getInstance(context: Context): ConnectivityHelper {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                NougatConnectivityHelper(context)
            else
                EmptyConnectivityHelper()
        }
    }

    protected val connectionChangEventFlow =
        MutableSharedFlow<Network>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    abstract fun startListening(coroutineScope: CoroutineScope, onConnectionChanged: () -> Unit)
    abstract fun stopListening()

    @TargetApi(Build.VERSION_CODES.N)
    private class NougatConnectivityHelper(context: Context) : ConnectivityHelper() {

        private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        private val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                XLog.d(this@NougatConnectivityHelper.getLog("onAvailable", "Network: $network"))
                connectionChangEventFlow.tryEmit(network)
            }

            override fun onLost(network: Network) {
                XLog.d(this@NougatConnectivityHelper.getLog("onLost", "Network: $network"))
                connectionChangEventFlow.tryEmit(network)
            }
        }

        override fun startListening(coroutineScope: CoroutineScope, onConnectionChanged: () -> Unit) {
            XLog.d(this@NougatConnectivityHelper.getLog("startListening"))

            connectionChangEventFlow.conflate()
                .onStart {
                    XLog.d(this@NougatConnectivityHelper.getLog("startListening", "onStart"))
                    onConnectionChanged.invoke()
                }
                .onEach {
                    XLog.d(this@NougatConnectivityHelper.getLog("onEach", "Network: $it"))
                    onConnectionChanged.invoke()
                    delay(250)
                }.shareIn(coroutineScope, SharingStarted.Eagerly)

            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        }

        override fun stopListening() {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }

    @Suppress("Deprecation")
    private class EmptyConnectivityHelper : ConnectivityHelper() {
        override fun startListening(coroutineScope: CoroutineScope, onConnectionChanged: () -> Unit) {
            XLog.d(this@EmptyConnectivityHelper.getLog("startListening", "Trigger on start address discovery"))
            onConnectionChanged.invoke()
        }

        override fun stopListening() = Unit
    }
}