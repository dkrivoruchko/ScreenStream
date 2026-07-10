@file:Suppress("unused") // Dormant until controller authority integration.

package dev.dmkr.screencaptureengine.internal.control

@JvmInline
internal value class ControllerIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class SessionIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class TransactionIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class ReconfigurationIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class CandidateIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class CompleteOwnerIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class TargetIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class IngressSequence(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class ReasonToken(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class DesiredSnapshotIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class EffectiveSnapshotIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class ControllerOperationIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class ControllerResourceBagIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class ControllerProductionAttemptIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class ControllerCallbackAttachmentIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class ControllerMetricsAttachmentIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class ControllerInvariantIdentity(val value: Long) {
    init {
        require(value > 0)
    }
}

@JvmInline
internal value class ControllerCancellationMarkerRevision(val value: Long) {
    init {
        require(value >= 0)
    }
}

/** Immutable origin fence shared by direct work and exact terminal-operation identities. */
internal sealed interface ControllerFactOrigin {
    val candidate: CandidateIdentity

    sealed interface Recovery : ControllerFactOrigin

    data class Startup(override val candidate: CandidateIdentity) : Recovery

    data class Parameter(
        val transaction: TransactionIdentity,
        override val candidate: CandidateIdentity,
    ) : ControllerFactOrigin

    data class Reconfiguration(
        val reconfiguration: ReconfigurationIdentity,
        override val candidate: CandidateIdentity,
    ) : Recovery
}

/**
 * One externally reserved ordinary controller-wide state/result/event occurrence. The reserved
 * `Long.MAX_VALUE` boundary is deliberately not representable by this normal-range identity.
 */
@JvmInline
internal value class ControllerOccurrenceIdentity(val value: Long) {
    init {
        require(value in 1L until Long.MAX_VALUE)
    }
}

/** Concrete occurrence reservations supplied only after pure reduction derives the exact shape. */
internal sealed interface ControllerTurnOccurrenceReservations {
    class NormalRange(
        val stateCommit: ControllerOccurrenceIdentity? = null,
        val resultCommit: ControllerOccurrenceIdentity? = null,
        val event: ControllerOccurrenceIdentity? = null,
        val diagnostic: ControllerOccurrenceIdentity? = null,
    ) : ControllerTurnOccurrenceReservations {
        init {
            require(stateCommit == null || resultCommit == null || resultCommit.value > stateCommit.value)
            require(event == null || stateCommit != null || resultCommit != null)
            require(event == null || stateCommit == null || event.value > stateCommit.value)
            require(event == null || resultCommit == null || event.value > resultCommit.value)
            require(diagnostic == null || stateCommit == null || diagnostic.value > stateCommit.value)
            require(diagnostic == null || resultCommit == null || diagnostic.value > resultCommit.value)
            require(event == null || diagnostic == null || diagnostic.value > event.value)
        }
    }

    /** The unresolved reserved-sentinel schedule; ordinary reducer arithmetic must not inspect it. */
    data object FrozenSequenceBoundary : ControllerTurnOccurrenceReservations
}
