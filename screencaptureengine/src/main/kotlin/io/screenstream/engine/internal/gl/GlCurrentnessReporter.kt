package io.screenstream.engine.internal.gl

import kotlin.concurrent.withLock

/**
 * Reports identity-fenced mechanical GL state to the Session authority.
 *
 * It owns only the GL-local observation version. It cannot select a topology, apply Session currentness,
 * choose replacement/fallback, or reopen production.
 */
internal class GlCurrentnessReporter internal constructor(
    private val authority: GlPipelineOwner,
) {
    private var version: Long = 1L
    private var exhausted: Boolean = false

    internal fun snapshot(expected: GlPipelineOwner.GlRenderTargetOwner): GlRenderCurrentnessFact? =
        authority.glGate.withLock {
            val state = authority.installedRenderTarget ?: return@withLock null
            if (state.owner !== expected) return@withLock null

            val fatal = authority.laneRuntime.observedFatal
            val poisoned = authority.laneRuntime.isPoisoned
            val pipelineComplete = authority.renderPipelineCompleteLocked(state)
            val destructionClaimed = state.destructionClaimedLocked(authority)
            GlRenderCurrentnessFact(
                renderTargetOwner = state.owner,
                renderGeneration = state.owner.renderGeneration,
                compatibilityFacts = state.owner.compatibilityFacts,
                actualState = state.actualStateLocked(authority),
                contextIntegrity = authority.contextIntegrity,
                pipelineComplete = pipelineComplete,
                destructionClaimed = destructionClaimed,
                lanePoisoned = poisoned,
                observedFatal = fatal,
                version = version,
                versionExhausted = exhausted,
                reusable = !exhausted && pipelineComplete && !destructionClaimed &&
                        authority.contextIntegrity == ContextIntegrity.Intact && !poisoned && fatal == null,
            )
        }

    internal fun stillMatches(fact: GlRenderCurrentnessFact): Boolean = authority.glGate.withLock {
        val state = authority.installedRenderTarget ?: return@withLock false
        val fatal = authority.laneRuntime.observedFatal
        val poisoned = authority.laneRuntime.isPoisoned
        val pipelineComplete = authority.renderPipelineCompleteLocked(state)
        val destructionClaimed = state.destructionClaimedLocked(authority)
        val reusable = pipelineComplete && !destructionClaimed &&
                authority.contextIntegrity == ContextIntegrity.Intact && !poisoned && fatal == null

        !exhausted && !fact.versionExhausted && fact.version == version &&
                fact.renderTargetOwner === state.owner &&
                fact.renderGeneration == state.owner.renderGeneration &&
                fact.compatibilityFacts === state.owner.compatibilityFacts &&
                fact.actualState === state.actualStateLocked(authority) &&
                fact.contextIntegrity == authority.contextIntegrity &&
                fact.pipelineComplete == pipelineComplete &&
                fact.destructionClaimed == destructionClaimed &&
                fact.lanePoisoned == poisoned &&
                fact.observedFatal === fatal &&
                fact.reusable == reusable
    }

    internal val isExhausted: Boolean
        get() = authority.glGate.withLock { exhausted }

    internal fun isExhaustedLocked(): Boolean {
        check(authority.glGate.isHeldByCurrentThread)
        return exhausted
    }

    internal fun recordMutationLocked() {
        check(authority.glGate.isHeldByCurrentThread)
        if (exhausted) return
        if (version == Long.MAX_VALUE) {
            exhausted = true
            return
        }
        version += 1L
        if (version == Long.MAX_VALUE) exhausted = true
    }
}
