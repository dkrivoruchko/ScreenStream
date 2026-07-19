package io.screenstream.engine.internal.controller

import io.screenstream.engine.internal.delivery.ObservationDiagnosticRequest
import io.screenstream.engine.internal.delivery.ObservationOwner
import io.screenstream.engine.internal.delivery.ObservationStateSnapshot
import io.screenstream.engine.internal.delivery.ObservationStatsSnapshot

internal class SessionPublicationBatch internal constructor(
    internal val starting: Boolean = false,
    internal val startingStats: ObservationStatsSnapshot? = null,
    internal val running: ObservationStateSnapshot.Running? = null,
    internal val terminalStats: ObservationStatsSnapshot? = null,
    internal val terminalDiagnostic: ObservationDiagnosticRequest? = null,
    internal val terminalState: ObservationStateSnapshot? = null,
)

internal object SessionPublication {
    internal fun dispatch(owner: ObservationOwner, batch: SessionPublicationBatch) {
        if (batch.starting) owner.assignState(ObservationStateSnapshot.Starting)
        batch.startingStats?.let(owner::assignStats)
        batch.running?.let(owner::assignState)
        batch.terminalStats?.let(owner::assignStats)
        batch.terminalDiagnostic?.let(owner::tryEmitDiagnostic)
        batch.terminalState?.let(owner::assignState)
    }
}
