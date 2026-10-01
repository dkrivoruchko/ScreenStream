package io.screenstream.mjpeg.ipaddress

import kotlinx.coroutines.flow.StateFlow
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Collections

/** Discover device-local address candidates for one controller, independently of UI subscriptions. */
internal interface NetworkInterfaceMonitor {
    /** Retains the latest snapshot, including [State.Closed] after cleanup. */
    val state: StateFlow<State>

    /**
     * Copy and apply [filter] without blocking; the first call starts discovery. Thread-safe and
     * ignored once closing begins. Construction alone does not start monitoring.
     */
    fun updateFilter(filter: Filter)

    /**
     * Idempotently unregister observers and await owned work, including before the first filter.
     * Cancelling a waiter does not abandon cleanup; cleanup failure is shared with waiters and does
     * not publish [State.Closed]. The controller owns this obligation and must call close.
     */
    suspend fun close()

    /** IP protocol family used to select addresses. */
    enum class AddressFamily {
        /** IPv4 addresses. */
        Ipv4,

        /** IPv6 addresses, including their scope. */
        Ipv6,
    }

    /** Type of the local network interface; confirmed VPN metadata takes priority. */
    enum class InterfaceType {
        /** Wi-Fi, including identified hotspot and Wi-Fi Direct interfaces. */
        Wifi,

        /** Ethernet interfaces. */
        Ethernet,

        /** Mobile data interfaces. */
        Mobile,

        /** VPN interfaces. */
        Vpn,

        /** Interfaces whose type could not be established. */
        Unknown,
    }

    /** Scope category of a unicast address; unspecified and multicast addresses are excluded. */
    enum class AddressCategory {
        /** RFC1918 IPv4 and IPv6 ULA addresses. */
        Private,

        /** Other unicast addresses, excluding loopback and link-local; internet reachability is not implied. */
        NonPrivate,

        /** Loopback addresses for access on this device. */
        Loopback,

        /** Addresses limited to the local link; IPv6 scope belongs to this device's interface. */
        LinkLocal,
    }

    /**
     * Intersects the three groups; values within each group are alternatives. An empty group selects
     * nothing. Unspecified and multicast addresses are always excluded. Sets are privately owned and immutable.
     *
     * @property families Selected IP protocol families.
     * @property interfaceTypes Selected local interface types, including Unknown only when explicitly selected.
     * @property categories Selected unicast address categories.
     */
    class Filter(
        families: Set<AddressFamily>,
        interfaceTypes: Set<InterfaceType>,
        categories: Set<AddressCategory>,
    ) {
        val families: Set<AddressFamily> = Collections.unmodifiableSet(HashSet(families))
        val interfaceTypes: Set<InterfaceType> = Collections.unmodifiableSet(HashSet(interfaceTypes))
        val categories: Set<AddressCategory> = Collections.unmodifiableSet(HashSet(categories))

        override fun equals(other: Any?): Boolean = other is Filter &&
                families == other.families && interfaceTypes == other.interfaceTypes && categories == other.categories

        override fun hashCode(): Int = 31 * (31 * families.hashCode() + interfaceTypes.hashCode()) + categories.hashCode()
    }

    /**
     * A device-local candidate; binding and viewer reachability are checked separately.
     * Identity includes IP bytes, interface name/type, and IPv6 numeric and interface scope.
     *
     * @property address IP address with its original IPv6 scope.
     * @property interfaceName Name of the local interface that owns the address.
     * @property interfaceType Established type of that interface, or Unknown.
     */
    class Address(
        val address: InetAddress,
        val interfaceName: String,
        val interfaceType: InterfaceType,
    ) {
        private val bytes: List<Byte> = address.address.toList()
        private val scopeId: Int? = (address as? Inet6Address)?.scopeId
        private val scopeInterface: String? = (address as? Inet6Address)?.scopedInterface?.name

        override fun equals(other: Any?): Boolean = other is Address &&
                bytes == other.bytes && interfaceName == other.interfaceName && interfaceType == other.interfaceType &&
                scopeId == other.scopeId && scopeInterface == other.scopeInterface

        override fun hashCode(): Int {
            var result: Int = bytes.hashCode()
            result = 31 * result + interfaceName.hashCode()
            result = 31 * result + interfaceType.hashCode()
            result = 31 * result + (scopeId?.hashCode() ?: 0)
            return 31 * result + (scopeInterface?.hashCode() ?: 0)
        }
    }

    /**
     * Producers own immutable address lists in stable order and do not republish equal snapshots.
     */
    sealed interface State {
        /** No filter has been supplied and discovery has not started. */
        data object NotStarted : State

        /** The first read has not completed. */
        data class Loading(val filter: Filter) : State

        /**
         * Complete discovery, including a successful empty result.
         *
         * @property addresses Selected address candidates.
         */
        data class Ready(val filter: Filter, val addresses: List<Address>) : State

        /**
         * Partial or unconfirmed evidence; a missing address does not prove that it disappeared.
         *
         * @property addresses Available selected candidates, including evidence not yet reconfirmed.
         */
        data class Incomplete(val filter: Filter, val addresses: List<Address>) : State

        /** Observers have been unregistered and owned work has finished successfully. */
        data object Closed : State
    }
}
