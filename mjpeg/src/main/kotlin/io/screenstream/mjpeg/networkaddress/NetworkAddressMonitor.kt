package io.screenstream.mjpeg.networkaddress

import kotlinx.coroutines.flow.StateFlow

/**
 * Discovery starts at [start] and continues without subscribers. Observations retain all supported
 * scoped IPs and their interface alternatives; callers select using their current filter. The owner
 * must [close] even if discovery was never started. Discovery does not guarantee viewer reachability.
 */
internal interface NetworkAddressMonitor {
    val state: StateFlow<State>

    /**
     * Starts discovery and returns immediately. Safe to call repeatedly from any thread. Calls after
     * closing begins are ignored.
     */
    fun start()

    /**
     * All waiters receive the same cleanup result; cancelling one does not interrupt cleanup.
     * Success ends in Closed. Discovery or cleanup failure stays Failed and is thrown with cleanup errors.
     */
    suspend fun close()

    /**
     * Discovery status. Inventories and their selected lists cannot be modified and keep a consistent order.
     * Unchanged values do not produce another update.
     */
    sealed interface State {
        /** Discovery has not started. */
        data object NotStarted : State

        /** Discovery has started, but no address result is available yet. */
        data object Loading : State

        /**
         * All supported address candidates; [inventory] selects using the caller's current filter.
         * If Android cannot read an interface, its previously known addresses may remain until a later
         * read confirms they are gone. An empty selection means no addresses match the filter; it does
         * not prove that every connection is unavailable.
         */
        data class Observed(val inventory: NetworkAddressInventory) : State

        /** Discovery or cleanup failed and cannot resume. Call [close] to await cleanup and receive the failure. */
        data object Failed : State

        /** Discovery has stopped and cleanup completed successfully. */
        data object Closed : State
    }
}
