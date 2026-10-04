package io.screenstream.mjpeg.networkaddress

import kotlinx.coroutines.flow.StateFlow

/**
 * Discovery starts at [updateFilter] and continues without subscribers. Results select one interface
 * per IP and IPv6 scope; callers replace their list rather than repeat selection. The owner must [close]
 * even if no filter was supplied. Discovery does not guarantee a bind or viewer reachability.
 */
internal interface NetworkAddressMonitor {
    val state: StateFlow<State>

    /**
     * Requests a selection using [filter] and returns immediately. The first call starts discovery;
     * later calls replace the requested filter. Safe to call from any thread. Calls after closing
     * begins are ignored. Check the filter in [State.Observed] before using its addresses.
     */
    fun updateFilter(filter: NetworkAddressFilter)

    /**
     * All waiters receive the same cleanup result; cancelling one does not interrupt cleanup.
     * Success ends in Closed. Discovery or cleanup failure stays Failed and is thrown with cleanup errors.
     */
    suspend fun close()

    /**
     * Discovery status. Selected address lists cannot be modified and keep a consistent order.
     * Unchanged values do not produce another update.
     */
    sealed interface State {
        /** No filter has been supplied and discovery has not started. */
        data object NotStarted : State

        /** Discovery has started for [filter], but no address result is available yet. */
        data class Loading(val filter: NetworkAddressFilter) : State

        /**
         * The complete selected list for [filter]; replace the caller's previous list with [addresses].
         * If Android cannot read an interface, its previously known addresses may remain until a later
         * read confirms they are gone. An empty list means no addresses are currently selected; it does
         * not prove that every connection is unavailable.
         */
        data class Observed(val filter: NetworkAddressFilter, val addresses: List<NetworkAddress>) : State

        /** Discovery or cleanup failed and cannot resume. Call [close] to await cleanup and receive the failure. */
        data object Failed : State

        /** Discovery has stopped and cleanup completed successfully. */
        data object Closed : State
    }
}
