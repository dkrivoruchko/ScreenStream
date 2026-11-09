package io.screenstream.mjpeg.networkaddress

import java.net.Inet4Address
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

    override fun equals(other: Any?): Boolean =
        other is NetworkAddressFilter && families == other.families && interfaceTypes == other.interfaceTypes && categories == other.categories

    override fun hashCode(): Int =
        31 * (31 * families.hashCode() + interfaceTypes.hashCode()) + categories.hashCode()
}

/** Identifies the same IP and IPv6 scope even when interface details change. */
internal class IpAddressKey(ip: InetAddress) {
    val bytes: List<Byte> = Collections.unmodifiableList(ip.address.toList())
    val scopeId: Int? = (ip as? Inet6Address)?.scopeId
    val scopeInterface: String? = (ip as? Inet6Address)?.scopedInterface?.name

    override fun equals(other: Any?): Boolean = other is IpAddressKey &&
            bytes == other.bytes && scopeId == other.scopeId && scopeInterface == other.scopeInterface

    override fun hashCode(): Int = 31 * (31 * bytes.hashCode() + scopeId.hashCode()) + scopeInterface.hashCode()

    companion object {
        val ORDER: Comparator<IpAddressKey> = compareBy(
            { it.bytes.size },
            { it.bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } },
            { it.scopeId ?: 0 },
            { it.scopeInterface.orEmpty() },
        )
    }
}

/**
 * A supported IP and one interface supplying it. Use [ip] with the separately configured server
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

    val ipKey: IpAddressKey = IpAddressKey(ip)

    override fun equals(other: Any?): Boolean = other is NetworkAddress &&
            id == other.id &&
            ipKey == other.ipKey &&
            interfaceName == other.interfaceName &&
            interfaceType == other.interfaceType

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + ipKey.hashCode()
        result = 31 * result + interfaceName.hashCode()
        return 31 * result + interfaceType.hashCode()
    }

    companion object {
        val ORDER: Comparator<NetworkAddress> = compareBy<NetworkAddress> { it.interfaceName }
            .thenBy(IpAddressKey.ORDER) { it.ipKey }
            .thenBy { it.interfaceType.ordinal }
    }
}

/** All supported scoped IPs and their ordered interface alternatives at one observation. */
internal class NetworkAddressInventory(addressesByIp: Map<IpAddressKey, List<NetworkAddress>>) {
    private val addressesByIp: Map<IpAddressKey, List<NetworkAddress>> = Collections.unmodifiableMap(
        addressesByIp.mapValues { (_, alternatives) -> Collections.unmodifiableList(ArrayList(alternatives)) },
    )

    /** Filters alternatives before choosing one per scoped IP; the returned list cannot be modified. */
    fun select(filter: NetworkAddressFilter): List<NetworkAddress> = Collections.unmodifiableList(
        addressesByIp.values.mapNotNull { alternatives -> alternatives.firstOrNull { it.matches(filter) } }
            .sortedWith(NetworkAddress.ORDER),
    )

    override fun equals(other: Any?): Boolean = other is NetworkAddressInventory && addressesByIp == other.addressesByIp

    override fun hashCode(): Int = addressesByIp.hashCode()

    private fun NetworkAddress.matches(filter: NetworkAddressFilter): Boolean {
        val family = if (ip is Inet4Address) AddressFamily.Ipv4 else AddressFamily.Ipv6
        return family in filter.families && interfaceType in filter.interfaceTypes && category() in filter.categories
    }

    private fun NetworkAddress.category(): AddressCategory {
        val first = ip.address[0].toInt() and 0xff
        val second = ip.address[1].toInt() and 0xff
        val privateAddress = if (ip is Inet4Address) {
            first == 10 || first == 172 && second in 16..31 || first == 192 && second == 168
        } else {
            first and 0xfe == 0xfc
        }
        return when {
            ip.isLoopbackAddress -> AddressCategory.Loopback
            ip.isLinkLocalAddress -> AddressCategory.LinkLocal
            privateAddress -> AddressCategory.Private
            else -> AddressCategory.NonPrivate
        }
    }
}
