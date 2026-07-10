@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.control

import java.util.concurrent.atomic.AtomicReference

internal sealed interface ProductionAttemptOutcome {
    data class Completed(
        val published: Boolean,
    ) : ProductionAttemptOutcome

    data object RateLimited : ProductionAttemptOutcome

    data object PipelineBusy : ProductionAttemptOutcome

    data object StaleWork : ProductionAttemptOutcome

    data object Failure : ProductionAttemptOutcome

    data object EncodedSizeLimit : ProductionAttemptOutcome
}

internal class ProductionAttemptOutcomeSlot {
    private val committed = AtomicReference<ProductionAttemptOutcome?>()

    internal val outcome: ProductionAttemptOutcome?
        get() = committed.get()

    internal fun tryCommit(outcome: ProductionAttemptOutcome): Boolean =
        committed.compareAndSet(null, outcome)
}
