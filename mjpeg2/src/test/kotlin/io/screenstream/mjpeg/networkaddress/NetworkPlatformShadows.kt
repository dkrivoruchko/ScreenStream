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
    var registeredCallback: TetheringManager.TetheringEventCallback? = null
        private set
    var registeredExecutor: Executor? = null
        private set

    @Implementation
    protected fun registerTetheringEventCallback(executor: Executor, callback: TetheringManager.TetheringEventCallback) {
        check(registeredCallback == null) { "Observer already registered" }
        registeredExecutor = executor
        registeredCallback = callback
    }

    @Implementation
    protected fun unregisterTetheringEventCallback(callback: TetheringManager.TetheringEventCallback) {
        check(registeredCallback === callback) { "Observer was not registered" }
        registeredCallback = null
        registeredExecutor = null
    }
}

/** Faults and interleavings at Android calls; all normal network behavior remains the stock shadow's. */
@Implements(ConnectivityManager::class)
internal class SourceConnectivityShadow : ShadowConnectivityManager() {
    var registeredRequest: NetworkRequest? = null
        private set
    var beforeRegistration: (() -> Unit)? = null
    var afterRegistration: ((ConnectivityManager.NetworkCallback) -> Unit)? = null
    var afterLinkPropertiesRead: ((Network, LinkProperties?) -> Unit)? = null

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
}
