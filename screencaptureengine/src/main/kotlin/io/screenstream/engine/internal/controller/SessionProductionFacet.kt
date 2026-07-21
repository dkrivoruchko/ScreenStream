package io.screenstream.engine.internal.controller

import io.screenstream.engine.internal.jpeg.NativeEncodeOccurrence

internal sealed interface SessionProductionAdmission {
    internal object Open : SessionProductionAdmission

    internal class Sealed internal constructor(
        internal val occurrence: SessionRetainedReconfigurationOccurrence,
    ) : SessionProductionAdmission

    internal object Terminal : SessionProductionAdmission
}

internal class SessionProductionFacet internal constructor() {
    internal var pendingNativeEncode: NativeEncodeOccurrence? = null
}
