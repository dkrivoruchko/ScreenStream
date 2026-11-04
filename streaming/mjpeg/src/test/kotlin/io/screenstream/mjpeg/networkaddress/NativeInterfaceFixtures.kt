package io.screenstream.mjpeg.networkaddress

import java.net.InetAddress
import java.util.Collections
import java.util.Enumeration

/** Supplies native inputs only; source and monitor retain all reconciliation and lifecycle behavior. */
internal class NativeInterfaceFixture(
    @Volatile var enumeration: () -> Enumeration<NativeInterfaceAccess.Handle>? = { Collections.emptyEnumeration() },
    @Volatile var lookup: (String) -> NativeInterfaceAccess.Handle? = { null },
) : NativeInterfaceAccess {
    override fun enumerate(): Enumeration<NativeInterfaceAccess.Handle>? = enumeration()
    override fun getByName(name: String): NativeInterfaceAccess.Handle? = lookup(name)
}

internal fun nativeInterface(
    interfaceName: String,
    addresses: List<InetAddress> = emptyList(),
    readIsUp: () -> Boolean = { true },
    readAddresses: () -> Enumeration<InetAddress> = { Collections.enumeration(addresses) },
): NativeInterfaceAccess.Handle = object : NativeInterfaceAccess.Handle {
    override val name: String = interfaceName
    override val isUp: Boolean get() = readIsUp()
    override val inetAddresses: Enumeration<InetAddress> get() = readAddresses()
}
