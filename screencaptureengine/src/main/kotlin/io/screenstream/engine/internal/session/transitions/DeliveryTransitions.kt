package io.screenstream.engine.internal.session.transitions

import io.screenstream.engine.internal.delivery.DeliveryEntryRequest
import io.screenstream.engine.internal.session.SessionState

internal object DeliveryTransitions {
    /** Pause is intentionally absent: an already-admitted handoff is grandfathered. */
    internal fun acceptedHandoffStillAdmitted(
        state: SessionState,
        request: DeliveryEntryRequest,
    ): Boolean = !state.terminalCutoffApplied &&
            state.registration === request.registration &&
            state.handoff === request.handoff &&
            request.registrationGeneration == request.registration.generation
}
