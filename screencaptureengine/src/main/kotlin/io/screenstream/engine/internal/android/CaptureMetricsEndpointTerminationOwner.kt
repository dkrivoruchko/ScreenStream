package io.screenstream.engine.internal.android

import io.screenstream.engine.internal.settlement.PrivateExecutorRuntime
import io.screenstream.engine.internal.settlement.PrivateExecutorTerminationReceipt
import java.util.concurrent.atomic.AtomicReference

internal class CaptureMetricsEndpointIdentity internal constructor()

internal class CaptureMetricsEndpointShutdownIdentity internal constructor(
    internal val endpointIdentity: CaptureMetricsEndpointIdentity,
)

internal enum class CaptureMetricsEndpointShutdownActionState {
    Prepared,
    Entered,
    Returned,
    Thrown,
}

internal enum class CaptureMetricsEndpointShutdownReturn {
    Requested,
    AlreadyRequested,
}

internal sealed interface CaptureMetricsEndpointShutdownActionOutcome {
    val identity: CaptureMetricsEndpointShutdownIdentity

    internal class Entered internal constructor(
        override val identity: CaptureMetricsEndpointShutdownIdentity,
    ) : CaptureMetricsEndpointShutdownActionOutcome

    internal class Returned internal constructor(
        override val identity: CaptureMetricsEndpointShutdownIdentity,
        internal val disposition: CaptureMetricsEndpointShutdownReturn,
    ) : CaptureMetricsEndpointShutdownActionOutcome

    internal class Thrown internal constructor(
        override val identity: CaptureMetricsEndpointShutdownIdentity,
        private val throwableCell: CaptureMetricsEndpointShutdownThrowableCell,
    ) : CaptureMetricsEndpointShutdownActionOutcome {
        init {
            check(throwableCell.identity === identity)
        }

        internal val throwable: Throwable
            get() = throwableCell.exactThrowable
    }
}

internal class CaptureMetricsEndpointShutdownThrowableCell internal constructor(
    internal val identity: CaptureMetricsEndpointShutdownIdentity,
) {
    private val throwableSlot = AtomicReference<Throwable?>(null)

    internal val exactThrowable: Throwable
        get() = checkNotNull(throwableSlot.get())

    internal fun publishFirst(
        expectedIdentity: CaptureMetricsEndpointShutdownIdentity,
        raw: Throwable,
    ): Boolean = expectedIdentity === identity && throwableSlot.compareAndSet(null, raw)
}

internal class CaptureMetricsEndpointTerminationReceipt private constructor(
    internal val endpointIdentity: CaptureMetricsEndpointIdentity,
    internal val rawReceipt: PrivateExecutorTerminationReceipt,
) {
    internal companion object {
        internal fun create(
            endpointIdentity: CaptureMetricsEndpointIdentity,
            rawReceipt: PrivateExecutorTerminationReceipt,
        ): CaptureMetricsEndpointTerminationReceipt =
            CaptureMetricsEndpointTerminationReceipt(endpointIdentity, rawReceipt)
    }
}

internal sealed interface CaptureMetricsEndpointTerminationOutcome {
    internal class Pending internal constructor(
        internal val endpointIdentity: CaptureMetricsEndpointIdentity,
        internal val shutdownAction: CaptureMetricsEndpointShutdownAction,
    ) : CaptureMetricsEndpointTerminationOutcome {
        internal val shutdownOutcome: CaptureMetricsEndpointShutdownActionOutcome?
            get() = shutdownAction.outcome
    }

    internal class Terminated internal constructor(
        internal val receipt: CaptureMetricsEndpointTerminationReceipt,
    ) : CaptureMetricsEndpointTerminationOutcome
}

/** Opaque evidence that the terminated private-executor root was separately released. */
internal class CaptureMetricsEndpointRootReleaseReceipt private constructor() {
    internal companion object {
        internal fun create(): CaptureMetricsEndpointRootReleaseReceipt =
            CaptureMetricsEndpointRootReleaseReceipt()
    }
}

internal sealed interface CaptureMetricsEndpointRootSettlement {
    internal class Retained internal constructor(
        internal val terminationOutcome: CaptureMetricsEndpointTerminationOutcome,
    ) : CaptureMetricsEndpointRootSettlement

    internal class Released internal constructor(
        internal val receipt: CaptureMetricsEndpointRootReleaseReceipt,
    ) : CaptureMetricsEndpointRootSettlement
}

/**
 * Exact one-shot shutdown entry. Entered-without-return is durable nonreturn evidence; it is not executor
 * termination, endpoint-root release, or settlement of any metrics source, subscription, listener, or callback.
 */
internal class CaptureMetricsEndpointShutdownAction internal constructor(
    private val terminationOwner: CaptureMetricsEndpointTerminationOwner,
    internal val identity: CaptureMetricsEndpointShutdownIdentity,
) {
    private val stateSlot = AtomicReference(CaptureMetricsEndpointShutdownActionState.Prepared)
    private val outcomeSlot = AtomicReference<CaptureMetricsEndpointShutdownActionOutcome?>(null)
    internal val enteredOutcome: CaptureMetricsEndpointShutdownActionOutcome.Entered =
        CaptureMetricsEndpointShutdownActionOutcome.Entered(identity)
    private val requestedOutcome = CaptureMetricsEndpointShutdownActionOutcome.Returned(
        identity,
        CaptureMetricsEndpointShutdownReturn.Requested,
    )
    private val alreadyRequestedOutcome = CaptureMetricsEndpointShutdownActionOutcome.Returned(
        identity,
        CaptureMetricsEndpointShutdownReturn.AlreadyRequested,
    )
    private val throwableCell = CaptureMetricsEndpointShutdownThrowableCell(identity)
    private val thrownOutcome = CaptureMetricsEndpointShutdownActionOutcome.Thrown(identity, throwableCell)

    internal val state: CaptureMetricsEndpointShutdownActionState
        get() = stateSlot.get()

    internal val outcome: CaptureMetricsEndpointShutdownActionOutcome?
        get() = outcomeSlot.get()

    internal fun enter(): CaptureMetricsEndpointShutdownActionOutcome {
        if (!stateSlot.compareAndSet(
                CaptureMetricsEndpointShutdownActionState.Prepared,
                CaptureMetricsEndpointShutdownActionState.Entered,
            )
        ) {
            return outcomeSlot.get() ?: enteredOutcome
        }
        return try {
            val disposition = terminationOwner.requestExactShutdown(this)
            val returned = when (disposition) {
                CaptureMetricsEndpointShutdownReturn.Requested -> requestedOutcome
                CaptureMetricsEndpointShutdownReturn.AlreadyRequested -> alreadyRequestedOutcome
            }
            check(outcomeSlot.compareAndSet(null, returned))
            stateSlot.set(CaptureMetricsEndpointShutdownActionState.Returned)
            returned
        } catch (raw: Throwable) {
            check(throwableCell.publishFirst(identity, raw))
            check(outcomeSlot.compareAndSet(null, thrownOutcome))
            stateSlot.set(CaptureMetricsEndpointShutdownActionState.Thrown)
            throw raw
        }
    }
}

/**
 * Owns only the metrics private-executor endpoint. Its termination and root release cannot settle the source,
 * subscription, listener, attachment, close occurrence, or any entered operation that did not return.
 */
internal class CaptureMetricsEndpointTerminationOwner internal constructor(
    endpoint: PrivateExecutorRuntime,
) {
    private sealed interface EndpointRootState {
        class Retained(
            val endpoint: PrivateExecutorRuntime,
        ) : EndpointRootState

        class Terminated(
            val endpoint: PrivateExecutorRuntime,
            val outcome: CaptureMetricsEndpointTerminationOutcome.Terminated,
        ) : EndpointRootState

        class Released(
            val terminationOutcome: CaptureMetricsEndpointTerminationOutcome.Terminated,
            val settlement: CaptureMetricsEndpointRootSettlement.Released,
            val observedFatal: Throwable?,
            val observedStartupFailure: Throwable?,
            val wasPoisoned: Boolean,
        ) : EndpointRootState
    }

    internal val endpointIdentity = CaptureMetricsEndpointIdentity()
    internal val shutdownIdentity = CaptureMetricsEndpointShutdownIdentity(endpointIdentity)
    internal val shutdownAction = CaptureMetricsEndpointShutdownAction(this, shutdownIdentity)

    private val pendingTermination =
        CaptureMetricsEndpointTerminationOutcome.Pending(endpointIdentity, shutdownAction)
    private val preparedRootReleaseReceipt = CaptureMetricsEndpointRootReleaseReceipt.create()
    private val preparedReleasedSettlement =
        CaptureMetricsEndpointRootSettlement.Released(preparedRootReleaseReceipt)
    private val rootState = AtomicReference<EndpointRootState>(EndpointRootState.Retained(endpoint))

    internal fun retainedEndpoint(): PrivateExecutorRuntime? = when (val state = rootState.get()) {
        is EndpointRootState.Retained -> state.endpoint
        is EndpointRootState.Terminated -> state.endpoint
        is EndpointRootState.Released -> null
    }

    internal val observedFatal: Throwable?
        get() = when (val state = rootState.get()) {
            is EndpointRootState.Retained -> state.endpoint.observedFatal
            is EndpointRootState.Terminated -> state.endpoint.observedFatal
            is EndpointRootState.Released -> state.observedFatal
        }

    internal val observedStartupFailure: Throwable?
        get() = when (val state = rootState.get()) {
            is EndpointRootState.Retained -> state.endpoint.observedStartupFailure
            is EndpointRootState.Terminated -> state.endpoint.observedStartupFailure
            is EndpointRootState.Released -> state.observedStartupFailure
        }

    internal val isPoisoned: Boolean
        get() = when (val state = rootState.get()) {
            is EndpointRootState.Retained -> state.endpoint.isPoisoned
            is EndpointRootState.Terminated -> state.endpoint.isPoisoned
            is EndpointRootState.Released -> state.wasPoisoned
        }

    internal fun terminationOutcome(): CaptureMetricsEndpointTerminationOutcome {
        while (true) {
            when (val state = rootState.get()) {
                is EndpointRootState.Released -> return state.terminationOutcome
                is EndpointRootState.Terminated -> return state.outcome
                is EndpointRootState.Retained -> {
                    val raw = state.endpoint.terminationReceipt ?: return pendingTermination
                    if (!state.endpoint.acceptsTerminationReceipt(raw)) return pendingTermination
                    val receipt = CaptureMetricsEndpointTerminationReceipt.create(endpointIdentity, raw)
                    val outcome = CaptureMetricsEndpointTerminationOutcome.Terminated(receipt)
                    if (rootState.compareAndSet(state, EndpointRootState.Terminated(state.endpoint, outcome))) {
                        return outcome
                    }
                }
            }
        }
    }

    internal fun endpointForRelease(
        receipt: CaptureMetricsEndpointTerminationReceipt,
    ): PrivateExecutorRuntime? {
        val outcome = terminationOutcome() as? CaptureMetricsEndpointTerminationOutcome.Terminated ?: return null
        if (outcome.receipt !== receipt) return null
        val state = rootState.get() as? EndpointRootState.Terminated ?: return null
        return state.endpoint.takeIf {
            receipt.endpointIdentity === endpointIdentity &&
                    it.terminationReceipt === receipt.rawReceipt &&
                    it.acceptsTerminationReceipt(receipt.rawReceipt)
        }
    }

    internal fun releaseEndpointRoot(
        receipt: CaptureMetricsEndpointTerminationReceipt,
    ): CaptureMetricsEndpointRootSettlement {
        while (true) {
            when (val state = rootState.get()) {
                is EndpointRootState.Retained -> {
                    val outcome = terminationOutcome()
                    if (outcome is CaptureMetricsEndpointTerminationOutcome.Pending) {
                        return CaptureMetricsEndpointRootSettlement.Retained(outcome)
                    }
                }

                is EndpointRootState.Released ->
                    return if (state.terminationOutcome.receipt === receipt) {
                        state.settlement
                    } else {
                        CaptureMetricsEndpointRootSettlement.Retained(state.terminationOutcome)
                    }

                is EndpointRootState.Terminated -> {
                    if (state.outcome.receipt !== receipt || receipt.endpointIdentity !== endpointIdentity ||
                        state.endpoint.terminationReceipt !== receipt.rawReceipt ||
                        !state.endpoint.acceptsTerminationReceipt(receipt.rawReceipt)
                    ) {
                        return CaptureMetricsEndpointRootSettlement.Retained(state.outcome)
                    }
                    val released = EndpointRootState.Released(
                        terminationOutcome = state.outcome,
                        settlement = preparedReleasedSettlement,
                        observedFatal = state.endpoint.observedFatal,
                        observedStartupFailure = state.endpoint.observedStartupFailure,
                        wasPoisoned = state.endpoint.isPoisoned,
                    )
                    if (rootState.compareAndSet(state, released)) return preparedReleasedSettlement
                }
            }
        }
    }

    internal fun requestExactShutdown(
        action: CaptureMetricsEndpointShutdownAction,
    ): CaptureMetricsEndpointShutdownReturn {
        check(action === shutdownAction && action.identity === shutdownIdentity)
        val endpoint = checkNotNull(retainedEndpoint())
        return if (endpoint.requestShutdown()) {
            CaptureMetricsEndpointShutdownReturn.Requested
        } else {
            CaptureMetricsEndpointShutdownReturn.AlreadyRequested
        }
    }
}
