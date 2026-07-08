package dev.dmkr.screencaptureengine.internal.lifecycle

import dev.dmkr.screencaptureengine.internal.encoding.provider.ProviderPreparationToken
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * Lifecycle fence for resources prepared for one pre-active output plan and projection-target generation.
 *
 * The token starts current, invalidates in-flight provider work on rollback or caller cancellation,
 * and is consumed exactly once when ownership moves into the initial runtime resource owner. A stale
 * token must not install prepared resources or replace the primary startup outcome.
 */
internal class PlanPreparationToken internal constructor(
    internal val ownerToken: Any,
    internal val planToken: Long,
    internal val projectionTargetGeneration: Long,
) {
    private val state = AtomicReference(PlanPreparationTokenState.Current)
    private val providerTokens = Collections.synchronizedSet(mutableSetOf<ProviderPreparationToken>())

    internal val isCurrent: Boolean
        get() = state.get() == PlanPreparationTokenState.Current

    internal fun newProviderPreparationToken(): ProviderPreparationToken {
        val token = ProviderPreparationToken()
        providerTokens += token
        if (!isCurrent) {
            providerTokens -= token
            token.cancel()
        }
        return token
    }

    internal fun detachProviderPreparationToken(token: ProviderPreparationToken) {
        providerTokens -= token
    }

    internal fun invalidate() {
        if (!state.compareAndSet(PlanPreparationTokenState.Current, PlanPreparationTokenState.Invalidated)) return
        val snapshot = synchronized(providerTokens) { providerTokens.toList() }
        snapshot.forEach { it.cancel() }
    }

    internal fun consumeForHandoff(): Boolean =
        state.compareAndSet(PlanPreparationTokenState.Current, PlanPreparationTokenState.Consumed)

    internal fun matches(ownerToken: Any, planToken: Long, projectionTargetGeneration: Long): Boolean =
        this.ownerToken === ownerToken &&
                this.planToken == planToken &&
                this.projectionTargetGeneration == projectionTargetGeneration
}

private enum class PlanPreparationTokenState {
    Current,
    Invalidated,
    Consumed,
}
