package io.screenstream.mjpeg.networkaddress

import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Enumeration

/** Reads native interfaces lazily; enumeration and getters retain their native failures. */
internal interface NativeInterfaceAccess {
    fun enumerate(): Enumeration<Handle>?
    fun getByName(name: String): Handle?

    interface Handle {
        val name: String
        val isUp: Boolean
        val inetAddresses: Enumeration<InetAddress>
    }
}

internal object SystemNativeInterfaceAccess : NativeInterfaceAccess {
    override fun enumerate(): Enumeration<NativeInterfaceAccess.Handle>? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        return object : Enumeration<NativeInterfaceAccess.Handle> {
            override fun hasMoreElements(): Boolean = interfaces.hasMoreElements()
            override fun nextElement(): NativeInterfaceAccess.Handle = NativeHandle(interfaces.nextElement())
        }
    }

    override fun getByName(name: String): NativeInterfaceAccess.Handle? = NetworkInterface.getByName(name)?.let(::NativeHandle)

    private class NativeHandle(private val delegate: NetworkInterface) : NativeInterfaceAccess.Handle {
        override val name: String get() = delegate.name
        override val isUp: Boolean get() = delegate.isUp
        override val inetAddresses: Enumeration<InetAddress> get() = delegate.inetAddresses
    }
}
