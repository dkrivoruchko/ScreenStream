package io.screenstream.mjpeg.networkaddress

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.TetheringManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import java.net.InetAddress
import java.util.concurrent.Executor

/** Only the Android callback input getter is substituted; LinkProperties keeps its real setter. */
@Implements(LinkAddress::class)
internal class CallbackLinkAddressShadow {
    lateinit var callbackAddress: InetAddress

    @Implementation
    protected fun getAddress(): InetAddress = callbackAddress
}

/** Captures only the two observer operations used by the source; events use its registered executor. */
@Implements(TetheringManager::class, minSdk = 36)
internal class SourceTetheringShadow {
    @Volatile
    var registeredCallback: TetheringManager.TetheringEventCallback? = null
        private set

    @Volatile
    var registeredExecutor: Executor? = null
        private set

    @Volatile
    var beforeRegistration: (() -> Unit)? = null

    @Volatile
    var afterRegistration: (() -> Unit)? = null

    @Volatile
    var beforeUnregistration: (() -> Unit)? = null

    @Implementation
    protected fun registerTetheringEventCallback(executor: Executor, callback: TetheringManager.TetheringEventCallback) {
        beforeRegistration?.invoke()
        check(registeredCallback == null) { "Observer already registered" }
        registeredExecutor = executor
        registeredCallback = callback
        afterRegistration?.invoke()
    }

    @Implementation
    protected fun unregisterTetheringEventCallback(callback: TetheringManager.TetheringEventCallback) {
        beforeUnregistration?.invoke()
        check(registeredCallback === callback) { "Observer was not registered" }
        registeredCallback = null
        registeredExecutor = null
    }
}

/** Faults and interleavings at Android calls; all normal network behavior remains the stock shadow's. */
@Implements(ConnectivityManager::class)
internal class SourceConnectivityShadow : ShadowConnectivityManager() {
    @Volatile
    var registeredRequest: NetworkRequest? = null
        private set

    @Volatile
    var beforeRegistration: (() -> Unit)? = null

    @Volatile
    var afterRegistration: ((ConnectivityManager.NetworkCallback) -> Unit)? = null

    @Volatile
    var afterLinkPropertiesRead: ((Network, LinkProperties?) -> Unit)? = null

    @Volatile
    var beforeUnregistration: ((ConnectivityManager.NetworkCallback) -> Unit)? = null

    @Implementation
    override fun registerNetworkCallback(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback) {
        beforeRegistration?.invoke()
        super.registerNetworkCallback(request, callback)
        registeredRequest = request
        afterRegistration?.invoke(callback)
    }

    @Implementation
    override fun getLinkProperties(network: Network): LinkProperties? {
        val captured = super.getLinkProperties(network)
        afterLinkPropertiesRead?.invoke(network, captured)
        return captured
    }

    @Implementation
    override fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
        beforeUnregistration?.invoke(callback)
        super.unregisterNetworkCallback(callback)
    }
}
