package io.screenstream.mjpeg.networkaddress

import kotlinx.coroutines.flow.StateFlow
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Collections

/**
 * Provides selected IP addresses for services on this device. Start discovery with [updateFilter]
 * and use [state] to replace the current address list. Discovery continues without state subscribers.
 * Results already apply the filter and choose one interface for each IP address, treating different
 * IPv6 scopes separately. Callers do not repeat that selection. Call [close] when finished.
 *
 * An address does not guarantee that a server can bind to it or that another device can reach it.
 */
internal interface NetworkAddressMonitor {
    /** The current discovery status and selected addresses. New subscribers receive the latest value. */
    val state: StateFlow<State>

    /**
     * Requests a selection using [filter] and returns immediately. The first call starts discovery;
     * later calls replace the requested filter. Safe to call from any thread. Calls after closing
     * begins are ignored. Check the filter in [State.Observed] before using its addresses.
     */
    fun updateFilter(filter: Filter)

    /**
     * Stops discovery and waits for cleanup. The owner must call this, even if no filter was supplied.
     * Callers that await completion receive the same result; cancelling one caller does not interrupt
     * cleanup.
     *
     * Returns after successful cleanup with [State.Closed]. If discovery or cleanup failed, close
     * reports that failure, including any additional cleanup errors, and the state is [State.Failed].
     */
    suspend fun close()

    /** IP protocol family used to select addresses. */
    enum class AddressFamily {
        /** IPv4 addresses. */
        Ipv4,

        /** IPv6 addresses, including their scope. */
        Ipv6,
    }

    /** The kind of connection provided by an address's interface. */
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
        Other,
    }

    /** Where an address can be used. Unspecified, multicast and IPv6 link-local addresses are never selected. */
    enum class AddressCategory {
        /** Private-network IPv4 addresses and unique-local IPv6 addresses. */
        Private,

        /** Other unicast addresses, excluding loopback and link-local; internet reachability is not implied. */
        NonPrivate,

        /** Loopback addresses for access on this device. */
        Loopback,

        /** IPv4 addresses limited to the local link; IPv6 link-local is always excluded. */
        LinkLocal,
    }

    /**
     * Selects addresses that match a family, an interface type and a category. Multiple values within
     * one group are alternatives; an empty group selects nothing. The filter does not change after
     * creation, even if the supplied sets change. Unspecified, multicast and IPv6 link-local addresses
     * remain excluded regardless of the selected criteria.
     *
     * @property families Allowed IP protocol families.
     * @property interfaceTypes Allowed connection types; Other must be selected explicitly.
     * @property categories Allowed address categories.
     */
    class Filter(
        families: Set<AddressFamily>,
        interfaceTypes: Set<InterfaceType>,
        categories: Set<AddressCategory>,
    ) {
        val families: Set<AddressFamily> = Collections.unmodifiableSet(HashSet(families))
        val interfaceTypes: Set<InterfaceType> = Collections.unmodifiableSet(HashSet(interfaceTypes))
        val categories: Set<AddressCategory> = Collections.unmodifiableSet(HashSet(categories))

        override fun equals(other: Any?): Boolean =
            other is Filter && families == other.families && interfaceTypes == other.interfaceTypes && categories == other.categories

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
    class Address(
        val id: Long,
        val ip: InetAddress,
        val interfaceName: String,
        val interfaceType: InterfaceType,
    ) {
        private val bytes: List<Byte> = ip.address.toList()
        private val scopeId: Int? = (ip as? Inet6Address)?.scopeId
        private val scopeInterface: String? = (ip as? Inet6Address)?.scopedInterface?.name

        override fun equals(other: Any?): Boolean = other is Address &&
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

    /**
     * Discovery status. Selected address lists cannot be modified and keep a consistent order.
     * Unchanged values do not produce another update.
     */
    sealed interface State {
        /** No filter has been supplied and discovery has not started. */
        data object NotStarted : State

        /** Discovery has started for [filter], but no address result is available yet. */
        data class Loading(val filter: Filter) : State

        /**
         * The complete selected list for [filter]; replace the caller's previous list with [addresses].
         * If Android cannot read an interface, its previously known addresses may remain until a later
         * read confirms they are gone. An empty list means no addresses are currently selected; it does
         * not prove that every connection is unavailable.
         */
        data class Observed(val filter: Filter, val addresses: List<Address>) : State

        /** Discovery or cleanup failed and cannot resume. Call [close] to await cleanup and receive the failure. */
        data object Failed : State

        /** Discovery has stopped and cleanup completed successfully. */
        data object Closed : State
    }
}
