package io.screenstream.engine.internal

import io.screenstream.engine.internal.android.AndroidCaptureOwner
import io.screenstream.engine.internal.android.CaptureMetricsOwner
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.PreparedTarget

/** Leaf-owned physical roots retained by their owners; Session cleanup policy lives under internal/session. */
internal class MetricsEndpointShutdownAction internal constructor(internal val owner: CaptureMetricsOwner)

internal class AndroidLaneQuitAction internal constructor(internal val owner: AndroidCaptureOwner)

internal sealed interface TargetQuarantineChild {
    internal class Prepared internal constructor(internal val target: PreparedTarget) : TargetQuarantineChild
    internal class Current internal constructor(internal val target: CurrentTarget) : TargetQuarantineChild
}
