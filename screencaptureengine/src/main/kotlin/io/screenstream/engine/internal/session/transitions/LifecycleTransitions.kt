package io.screenstream.engine.internal.session.transitions

import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.internal.session.SessionCurrentness
import io.screenstream.engine.internal.session.SessionLifecycle
import io.screenstream.engine.internal.session.SessionRuntimeStartedFact
import io.screenstream.engine.internal.session.SessionStartupTopologyReadyFact
import io.screenstream.engine.internal.session.runtime.RuntimeLaneReadiness
import io.screenstream.engine.internal.session.runtime.SessionRuntimeOwnership

internal object LifecycleTransitions {
    internal fun acceptStart(
        lifecycle: SessionLifecycle,
        startupIdentity: Long,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        parameters: ScreenCaptureParameters,
    ): StartDecision {
        require(startupIdentity > 0L)
        require(desiredRevision > 0L)
        require(geometryGeneration > 0L)
        require(lifecycleEpoch > 0L)
        val expectedCurrentness = SessionCurrentness(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
        )
        return if (lifecycle === SessionLifecycle.NotStarted) {
            StartDecision.Accepted(
                lifecycle = SessionLifecycle.Starting(
                    startupIdentity = startupIdentity,
                    expectedCurrentness = expectedCurrentness,
                ),
                currentness = expectedCurrentness,
                parameters = parameters,
            )
        } else {
            StartDecision.Rejected
        }
    }

    internal fun acceptRuntimeStarted(
        lifecycle: SessionLifecycle,
        installedOwnership: SessionRuntimeOwnership?,
        fact: SessionRuntimeStartedFact,
    ): RuntimeStartedDecision = if (lifecycle is SessionLifecycle.Starting &&
        lifecycle.startupIdentity == fact.startupIdentity &&
        installedOwnership == null
    ) {
        RuntimeStartedDecision.Accepted(
            ownership = fact.ownership,
            laneReadiness = fact.laneReadiness,
        )
    } else {
        RuntimeStartedDecision.Rejected
    }

    internal fun acceptStartupTopology(
        lifecycle: SessionLifecycle,
        expectedOwnership: SessionRuntimeOwnership?,
        fact: SessionStartupTopologyReadyFact,
    ): StartupTopologyDecision = if (lifecycle is SessionLifecycle.Starting &&
        lifecycle.startupIdentity == fact.startupIdentity &&
        expectedOwnership === fact.ownership &&
        fact.currentness == lifecycle.expectedCurrentness &&
        fact.effectiveParameters.captureGeometry == fact.captureGeometry
    ) {
        StartupTopologyDecision.Accepted(
            lifecycle = SessionLifecycle.Active(
                effectiveParameters = fact.effectiveParameters,
                capturedContentVisible = fact.capturedContentVisible,
            ),
            currentness = fact.currentness,
        )
    } else {
        StartupTopologyDecision.Rejected
    }
}

internal sealed interface StartDecision {
    internal class Accepted internal constructor(
        internal val lifecycle: SessionLifecycle.Starting,
        internal val currentness: SessionCurrentness,
        internal val parameters: ScreenCaptureParameters,
    ) : StartDecision

    internal object Rejected : StartDecision
}

internal sealed interface RuntimeStartedDecision {
    internal class Accepted internal constructor(
        internal val ownership: SessionRuntimeOwnership,
        internal val laneReadiness: RuntimeLaneReadiness,
    ) : RuntimeStartedDecision

    internal object Rejected : RuntimeStartedDecision
}

internal sealed interface StartupTopologyDecision {
    internal class Accepted internal constructor(
        internal val lifecycle: SessionLifecycle.Active,
        internal val currentness: SessionCurrentness,
    ) : StartupTopologyDecision

    internal object Rejected : StartupTopologyDecision
}
