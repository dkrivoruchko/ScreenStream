package io.screenstream.engine.internal.controller

internal sealed interface SessionNewDeliveryAdmission {
    internal object Open : SessionNewDeliveryAdmission

    internal class Sealed internal constructor(
        internal val occurrence: SessionRetainedReconfigurationOccurrence,
    ) : SessionNewDeliveryAdmission

    internal object Terminal : SessionNewDeliveryAdmission
}

internal class SessionDeliveryFacet internal constructor() {
    internal var targetAdmissionClosePending: Boolean = false
}
