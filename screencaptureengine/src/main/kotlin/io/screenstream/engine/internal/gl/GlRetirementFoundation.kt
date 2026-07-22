package io.screenstream.engine.internal.gl

import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationTerminalArbitration

/** GL-owned aggregate for names whose individual deletion can no longer be proved safe. */
internal class ContextNamespace private constructor(
    internal val owner: GlPipelineOwner,
    internal val triggerOperationIdentity: Long,
    internal val triggerKind: GlDestructionKind,
    integrityAtTransfer: ContextIntegrity?,
) {
    private var transferredIntegrity: ContextIntegrity? = integrityAtTransfer
    internal val integrityAtTransfer: ContextIntegrity
        get() = checkNotNull(transferredIntegrity)

    init {
        require(triggerOperationIdentity > 0L)
        require(integrityAtTransfer != ContextIntegrity.Intact)
    }

    internal fun matches(expectedOwner: GlPipelineOwner): Boolean = owner === expectedOwner

    internal companion object {
        internal fun createForProgram(
            owner: GlPipelineOwner,
            triggerOperationIdentity: Long,
        ): ContextNamespace = ContextNamespace(
            owner = owner,
            triggerOperationIdentity = triggerOperationIdentity,
            triggerKind = GlDestructionKind.Program,
            integrityAtTransfer = null,
        )

        internal fun createForPartialStartup(
            owner: GlPipelineOwner,
            triggerOperationIdentity: Long,
        ): ContextNamespace = ContextNamespace(
            owner = owner,
            triggerOperationIdentity = triggerOperationIdentity,
            triggerKind = GlDestructionKind.ContextNamespace,
            integrityAtTransfer = ContextIntegrity.Unknown,
        )

        internal fun activateProgramLocked(
            namespace: ContextNamespace,
            integrity: ContextIntegrity,
        ): Boolean {
            check(namespace.owner.glGate.isHeldByCurrentThread)
            if (integrity == ContextIntegrity.Intact) return false
            val existing = namespace.transferredIntegrity
            if (existing != null) return existing == integrity
            namespace.transferredIntegrity = integrity
            return true
        }
    }
}

/** A resource receipt is deliberately not an executor/lane-termination receipt. */
internal sealed interface GlPhysicalResourceRetirementReceipt : OperationReceipt

internal sealed interface GlPhysicalResourceReturnOutcome {
    internal class HealthyContextDeleted internal constructor(
        internal val receipt: GlDestructionSuccessReceipt,
    ) : GlPhysicalResourceReturnOutcome {
        init {
            require(receipt.destructionKind != GlDestructionKind.ContextNamespace)
        }
    }

    internal class ContextNamespaceRequired internal constructor(
        internal val namespace: ContextNamespace,
    ) : GlPhysicalResourceReturnOutcome

    internal class ContextNamespaceRetired internal constructor(
        internal val namespace: ContextNamespace,
        internal val receipt: GlDestructionSuccessReceipt,
    ) : GlPhysicalResourceReturnOutcome {
        init {
            require(receipt.destructionKind == GlDestructionKind.ContextNamespace)
        }
    }

    /** No physical context namespace ever existed, so this outcome deliberately carries no receipt. */
    internal class StructurallyNoContext internal constructor(
        internal val operationIdentity: Long,
    ) : GlPhysicalResourceReturnOutcome {
        init {
            require(operationIdentity > 0L)
        }
    }

    internal class ReturnedFailure internal constructor(
        internal val destructionKind: GlDestructionKind,
        internal val integrity: ContextIntegrity,
        internal val namespace: ContextNamespace?,
    ) : GlPhysicalResourceReturnOutcome
}

internal enum class GlTerminalCleanupOrigin {
    TerminalOrigin,
    TerminalConverted,
}

internal class GlTerminalCleanupWork private constructor(
    internal val occurrenceIdentity: Long,
    internal val origin: GlTerminalCleanupOrigin,
) {
    init {
        require(occurrenceIdentity > 0L)
    }

    internal companion object {
        internal fun create(
            occurrenceIdentity: Long,
            origin: GlTerminalCleanupOrigin,
        ): GlTerminalCleanupWork = GlTerminalCleanupWork(occurrenceIdentity, origin)
    }
}

internal sealed interface GlTerminalCleanupConversion {
    internal class Ready internal constructor(
        internal val work: GlTerminalCleanupWork,
    ) : GlTerminalCleanupConversion

    internal class SettledBeforeConversion internal constructor(
        internal val arbitration: OperationTerminalArbitration,
    ) : GlTerminalCleanupConversion {
        init {
            require(arbitration != OperationTerminalArbitration.Transferred)
        }
    }
}

internal sealed interface GlPhysicalRetirementProgress {
    internal class CleanupUnentered internal constructor(
        internal val work: GlTerminalCleanupWork,
    ) : GlPhysicalRetirementProgress

    internal class CleanupEnteredNonreturnResidue internal constructor(
        internal val residue: GlPhysicalRetirementResidue,
    ) : GlPhysicalRetirementProgress

    internal class QuarantineRequired internal constructor(
        internal val residue: GlPhysicalRetirementResidue,
    ) : GlPhysicalRetirementProgress

    internal class ReturnedSuffixFailure internal constructor(
        internal val residue: GlPhysicalRetirementResidue,
    ) : GlPhysicalRetirementProgress
}

internal class GlPhysicalRetirementResidue internal constructor(
    internal val owner: GlPipelineOwner,
    internal val work: GlTerminalCleanupWork,
    internal val destructionKind: GlDestructionKind,
    internal val namespace: ContextNamespace?,
) {
    init {
        check(work.occurrenceIdentity > 0L)
        check(namespace == null || namespace.matches(owner))
    }
}

/**
 * Converts the same precreated occurrence; it does not mint a replacement occurrence or cleanup deadline.
 */
internal fun <R : io.screenstream.engine.internal.settlement.OperationEvidence> OperationOccurrence<R>.toGlTerminalCleanup(
    work: GlTerminalCleanupWork,
): GlTerminalCleanupConversion {
    check(work.occurrenceIdentity == identity)
    val arbitration = arbitrateTerminal(mandatoryCleanup = true)
    return if (arbitration == OperationTerminalArbitration.Transferred) {
        check(domain == OperationDomain.Cleanup)
        GlTerminalCleanupConversion.Ready(work)
    } else {
        GlTerminalCleanupConversion.SettledBeforeConversion(arbitration)
    }
}
