package info.dvkr.screenstream.data.state.helper

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.other.getLog

internal sealed class ConnectivityHelper {
    companion object {
        fun getInstance(context: Context): ConnectivityHelper {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                NougatConnectivityHelper(context)
            else
                EmptyConnectivityHelper()
        }
    }

    protected lateinit var onConnectionChanged: () -> Unit

    abstract fun startListening(onConnectionChanged: () -> Unit)
    abstract fun stopListening()

    @TargetApi(Build.VERSION_CODES.N)
    private class NougatConnectivityHelper(context: Context) : ConnectivityHelper() {

        private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        private val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                XLog.d(this@NougatConnectivityHelper.getLog("onAvailable", "Network: $network"))
                if (::onConnectionChanged.isInitialized) onConnectionChanged.invoke()
            }

            override fun onLost(network: Network) {
                XLog.d(this@NougatConnectivityHelper.getLog("onLost", "Network: $network"))
                if (::onConnectionChanged.isInitialized) onConnectionChanged.invoke()
            }
        }

        override fun startListening(onConnectionChanged: () -> Unit) {
            this.onConnectionChanged = onConnectionChanged
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            onConnectionChanged.invoke()
        }

        override fun stopListening() {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }

    @Suppress("Deprecation")
    private class EmptyConnectivityHelper : ConnectivityHelper() {
        override fun startListening(onConnectionChanged: () -> Unit) {
            XLog.d(this@EmptyConnectivityHelper.getLog("startListening", "Trigger on start address discovery"))
            onConnectionChanged.invoke()
        }

        override fun stopListening() = Unit
    }
}