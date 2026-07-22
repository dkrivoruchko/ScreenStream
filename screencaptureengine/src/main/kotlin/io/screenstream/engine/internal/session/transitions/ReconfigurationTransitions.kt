package io.screenstream.engine.internal.session.transitions

import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.internal.session.SessionLifecycle

internal object ReconfigurationTransitions {
    internal fun acceptUpdate(
        lifecycle: SessionLifecycle,
        currentRequested: ScreenCaptureParameters?,
        requested: ScreenCaptureParameters,
        nextRevision: Long,
    ): ReconfigurationDecision {
        require(nextRevision > 0L)
        return when {
            lifecycle !is SessionLifecycle.Active &&
                    lifecycle !is SessionLifecycle.Reconfiguring &&
                    lifecycle !is SessionLifecycle.Suspended -> ReconfigurationDecision.WrongState

            currentRequested == requested -> ReconfigurationDecision.NoChange
            lifecycle is SessionLifecycle.Active -> ReconfigurationDecision.Accepted(
                revision = nextRevision,
                lifecycle = SessionLifecycle.Reconfiguring(
                    requestedParameters = requested,
                    lastEffectiveParameters = lifecycle.effectiveParameters,
                    lastKnownCaptureGeometry = lifecycle.effectiveParameters.captureGeometry,
                    capturedContentVisible = lifecycle.capturedContentVisible,
                ),
            )

            lifecycle is SessionLifecycle.Reconfiguring -> ReconfigurationDecision.Accepted(
                revision = nextRevision,
                lifecycle = SessionLifecycle.Reconfiguring(
                    requestedParameters = requested,
                    lastEffectiveParameters = lifecycle.lastEffectiveParameters,
                    lastKnownCaptureGeometry = lifecycle.lastKnownCaptureGeometry,
                    capturedContentVisible = lifecycle.capturedContentVisible,
                ),
            )

            lifecycle is SessionLifecycle.Suspended -> ReconfigurationDecision.Accepted(
                revision = nextRevision,
                lifecycle = SessionLifecycle.Reconfiguring(
                    requestedParameters = requested,
                    lastEffectiveParameters = lifecycle.lastEffectiveParameters,
                    lastKnownCaptureGeometry = lifecycle.lastKnownCaptureGeometry,
                    capturedContentVisible = lifecycle.capturedContentVisible,
                ),
            )

            else -> ReconfigurationDecision.WrongState
        }
    }
}

internal sealed interface ReconfigurationDecision {
    internal object WrongState : ReconfigurationDecision
    internal object NoChange : ReconfigurationDecision

    internal class Accepted internal constructor(
        internal val revision: Long,
        internal val lifecycle: SessionLifecycle.Reconfiguring,
    ) : ReconfigurationDecision {
        init {
            require(revision > 0L)
        }
    }
}
