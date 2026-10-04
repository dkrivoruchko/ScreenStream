package io.screenstream.mjpeg.networkaddress

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Collections

internal enum class AddressFamily {
    Ipv4,

    Ipv6,
}

internal enum class InterfaceType {
    /** Wi-Fi, including identified hotspot and Wi-Fi Direct interfaces. */
    Wifi,

    Ethernet,

    Mobile,

    Vpn,

    Other;

    val requiresLocalNetworkPermission: Boolean
        get() = when (this) {
            Wifi, Ethernet, Other -> true
            Mobile, Vpn -> false
        }
}

internal enum class AddressCategory {
    /** Private-network IPv4 addresses and unique-local IPv6 addresses. */
    Private,

    /** Other unicast addresses, excluding loopback and link-local; internet reachability is not implied. */
    NonPrivate,

    Loopback,

    /** IPv4 addresses limited to the local link; IPv6 link-local is always excluded. */
    LinkLocal,
}

/**
 * Matches one selected value in each group; an empty group selects nothing. Copies the input sets
 * so later caller mutations cannot change the filter. Unspecified, multicast and IPv6 link-local
 * addresses remain excluded regardless of criteria.
 */
internal class NetworkAddressFilter(
    families: Set<AddressFamily>,
    interfaceTypes: Set<InterfaceType>,
    categories: Set<AddressCategory>,
) {
    val families: Set<AddressFamily> = Collections.unmodifiableSet(HashSet(families))
    val interfaceTypes: Set<InterfaceType> = Collections.unmodifiableSet(HashSet(interfaceTypes))
    val categories: Set<AddressCategory> = Collections.unmodifiableSet(HashSet(categories))

    val requiresLocalNetworkPermission: Boolean
        get() = categories.any { it != AddressCategory.Loopback } && interfaceTypes.any { it.requiresLocalNetworkPermission }

    override fun equals(other: Any?): Boolean =
        other is NetworkAddressFilter && families == other.families && interfaceTypes == other.interfaceTypes && categories == other.categories

    override fun hashCode(): Int =
        31 * (31 * families.hashCode() + interfaceTypes.hashCode()) + categories.hashCode()
}

/**
 * A selected IP and the interface chosen for it. Use [ip] with the separately configured server
 * port; neither Internet access nor a successful server bind is guaranteed. IPv6 scope is preserved.
 *
 * [id] identifies this address during one monitor instance. Changing the filter, interface details
 * or server port preserves it. Once the address is confirmed gone, a later return gets a new id.
 * An IPv6 address with a different scope has a separate id.
 */
internal class NetworkAddress(
    val id: Long,
    val ip: InetAddress,
    val interfaceName: String,
    val interfaceType: InterfaceType,
) {
    val requiresLocalNetworkPermission: Boolean
        get() = !ip.isLoopbackAddress && interfaceType.requiresLocalNetworkPermission

    private val bytes: List<Byte> = ip.address.toList()
    private val scopeId: Int? = (ip as? Inet6Address)?.scopeId
    private val scopeInterface: String? = (ip as? Inet6Address)?.scopedInterface?.name

    override fun equals(other: Any?): Boolean = other is NetworkAddress &&
            id == other.id &&
            bytes == other.bytes &&
            interfaceName == other.interfaceName &&
            interfaceType == other.interfaceType &&
            scopeId == other.scopeId &&
            scopeInterface == other.scopeInterface

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + bytes.hashCode()
        result = 31 * result + interfaceName.hashCode()
        result = 31 * result + interfaceType.hashCode()
        result = 31 * result + scopeId.hashCode()
        return 31 * result + scopeInterface.hashCode()
    }
}
