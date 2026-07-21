package io.screenstream.engine.internal.target

import android.graphics.SurfaceTexture
import android.view.Surface
import io.screenstream.engine.internal.android.androidEnteredOperationSafetyNanos
import io.screenstream.engine.internal.gl.ContextIntegrity
import io.screenstream.engine.internal.gl.GlDestructionEvidence
import io.screenstream.engine.internal.gl.GlDestructionKind
import io.screenstream.engine.internal.gl.GlDestructionSuccessReceipt
import io.screenstream.engine.internal.gl.GlFiniteOperationIdentity
import io.screenstream.engine.internal.gl.GlOperationResult
import io.screenstream.engine.internal.gl.glEnteredOperationSafetyNanos
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class TargetSurfaceReleaseReceipt private constructor(
    internal val operationIdentity: Long,
) : OperationReceipt {
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            operationIdentity: Long,
        ): TargetSurfaceReleaseReceipt {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetSurfaceReleaseReceipt(operationIdentity)
        }
    }
}

internal class TargetSurfaceReleaseEvidence private constructor(
    internal val operationIdentity: Long,
    override val receipt: TargetSurfaceReleaseReceipt,
) : OperationEvidence {
    override val returnedOwner: OperationReturnedOwner? = null

    init {
        require(operationIdentity > 0L)
        require(receipt.operationIdentity == operationIdentity)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            operationIdentity: Long,
            receipt: TargetSurfaceReleaseReceipt,
        ): TargetSurfaceReleaseEvidence {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetSurfaceReleaseEvidence(operationIdentity, receipt)
        }
    }
}

internal sealed interface TargetRetirementSuffixEvidence {
    val targetIdentity: TargetIdentity
}

internal class TargetSurfaceReleaseSuffixEvidence private constructor(
    override val targetIdentity: TargetIdentity,
    internal val occurrence: OperationOccurrence<TargetSurfaceReleaseEvidence>,
) : TargetRetirementSuffixEvidence {
    init {
        check(occurrence.identity == occurrence.returnCell.evidence.operationIdentity)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            occurrence: OperationOccurrence<TargetSurfaceReleaseEvidence>,
        ): TargetSurfaceReleaseSuffixEvidence {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetSurfaceReleaseSuffixEvidence(targetIdentity, occurrence)
        }
    }
}

internal class TargetScopeSuffixEvidence private constructor(
    override val targetIdentity: TargetIdentity,
    internal val graph: TargetRetirement.TargetScopeDestructionGraph,
) : TargetRetirementSuffixEvidence {
    init {
        check(graph.targetGeneration == targetIdentity.generation)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            graph: TargetRetirement.TargetScopeDestructionGraph,
        ): TargetScopeSuffixEvidence {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetScopeSuffixEvidence(targetIdentity, graph)
        }
    }
}

internal class TargetNamespaceSuffixEvidence private constructor(
    override val targetIdentity: TargetIdentity,
    internal val graph: TargetRetirement.TargetScopeDestructionGraph,
    internal val occurrence: OperationOccurrence<GlDestructionEvidence>,
) : TargetRetirementSuffixEvidence {
    init {
        check(graph.targetGeneration == targetIdentity.generation)
        check(occurrence === graph.namespaceOccurrence)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            graph: TargetRetirement.TargetScopeDestructionGraph,
            occurrence: OperationOccurrence<GlDestructionEvidence>,
        ): TargetNamespaceSuffixEvidence {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetNamespaceSuffixEvidence(targetIdentity, graph, occurrence)
        }
    }
}

internal class TargetRetirementCompleteEvidence private constructor(
    override val targetIdentity: TargetIdentity,
) : TargetRetirementSuffixEvidence {
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
        ): TargetRetirementCompleteEvidence {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetRetirementCompleteEvidence(targetIdentity)
        }
    }
}

/**
 * Exact passive evidence for cleanup adoption. The [owner] remains the physical owner; this evidence never
 * transfers ownership or stands in for the permanent Session quarantine-root commit.
 */
internal class TargetQuarantineEvidence private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val owner: CurrentTarget,
    internal val suffix: TargetRetirementSuffixEvidence,
    internal val activeLeaseCount: Int,
) {
    init {
        require(activeLeaseCount >= 0)
        check(targetIdentity.matches(owner))
        check(suffix.targetIdentity === targetIdentity)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            owner: CurrentTarget,
            suffix: TargetRetirementSuffixEvidence,
            activeLeaseCount: Int,
        ): TargetQuarantineEvidence {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetQuarantineEvidence(targetIdentity, owner, suffix, activeLeaseCount)
        }
    }
}

internal class TargetRetirement private constructor(
    private val targetOwner: TargetOwner,
    private val constructionProof: () -> Unit,
    private val target: CurrentTarget,
    private val targetGate: ReentrantLock,
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
    private val surfaceReleaseOperationIdentity: Long,
    private val surfaceReleaseDeadlineIdentity: Long,
    private val surfaceReleaseDeadlineWakeGeneration: Long,
    private val surfaceReleaseTimeoutCause: Throwable,
    private val targetDestructionIdentity: GlFiniteOperationIdentity,
    private val namespaceDestructionIdentity: GlFiniteOperationIdentity,
) : TargetPorts.ResourceAccess {
    internal class SurfaceReleasePort private constructor(
        private val owner: TargetPorts,
        internal val targetIdentity: TargetIdentity,
        internal val operationIdentity: Long,
        internal val provenance: TargetOperationProvenance,
    ) {
        private val lease: TargetPorts.TargetLease = TargetPorts.TargetLease(owner)
        private var consumed: Boolean = false

        internal fun releaseSurface(): Boolean = owner.releaseSurface(this)

        internal fun claimConsumedLocked(requester: TargetPorts): Boolean {
            check(owner.acceptsLockedClaim(requester))
            if (consumed) return false
            consumed = true
            return true
        }

        internal fun retainLeaseAfterFatal(): Boolean = lease.retainAfterFatal()

        internal fun releaseLease(): Boolean = lease.release()

        internal companion object {
            internal fun create(owner: TargetPorts, operationIdentity: Long, provenance: TargetOperationProvenance): SurfaceReleasePort =
                SurfaceReleasePort(owner, provenance.targetIdentity, operationIdentity, provenance)
        }
    }

    internal class SurfaceReleaseOperation internal constructor(
        private val retirement: TargetRetirement,
    ) {
        private var committedPort: SurfaceReleasePort? = null

        internal val occurrence: OperationOccurrence<TargetSurfaceReleaseEvidence>
            get() = retirement.surfaceReleaseOccurrence

        internal fun prepareInstalled(readinessFact: TargetSurfaceReleaseReadyFact): Boolean {
            if (readinessFact.targetIdentity !== retirement.target.identity ||
                retirement.target.surfaceReleaseReadyFact() !== readinessFact
            ) {
                return false
            }
            val port = retirement.detachedSurfaceReleasePort() ?: return false
            return bind(port)
        }

        internal fun prepareUninstalled(constructionSettled: Boolean): Boolean {
            if (!retirement.beginUninstalledSurfaceReleaseSubmissionReadiness(constructionSettled)) return false
            val port = retirement.detachedSurfaceReleasePort() ?: return false
            return bind(port)
        }

        internal fun release(): TargetPortUseOutcome {
            val port = retirement.targetGate.withLock { committedPort }
                ?: return TargetPortUseOutcome.Rejected
            return retirement.releaseEnteredSurface(port)
        }

        internal fun publishNormalReturn(): Boolean = retirement.publishSurfaceReleaseNormalReturn()

        internal fun publishThrownReturn(thrown: Exception): Boolean =
            retirement.publishSurfaceReleaseThrownReturn(thrown)

        private fun bind(port: SurfaceReleasePort): Boolean = retirement.targetGate.withLock {
            val existing = committedPort
            if (existing != null) return@withLock existing === port
            if (!retirement.commitSurfaceReleasePort(port)) return@withLock false
            committedPort = port
            true
        }
    }

    internal class TargetScopeDestructionCommand private constructor(
        private val owner: TargetPorts,
        internal val targetIdentity: TargetIdentity,
        internal val targetGeneration: Long,
        internal val operationIdentity: Long,
        internal val provenance: TargetOperationProvenance,
    ) {
        private val lease: TargetPorts.TargetLease = TargetPorts.TargetLease(owner)
        private var consumed: Boolean = false

        internal fun withHandles(block: (SurfaceTexture?, Int) -> Unit): TargetPortUseOutcome =
            if (owner.withTargetScopeDestructionLease(this, block)) {
                TargetPortUseOutcome.BodyReturned
            } else {
                TargetPortUseOutcome.Rejected
            }

        internal fun claimConsumedLocked(requester: TargetPorts): Boolean {
            check(owner.acceptsLockedClaim(requester))
            if (consumed) return false
            consumed = true
            return true
        }

        internal fun retainLeaseAfterFatal(): Boolean = lease.retainAfterFatal()

        internal fun releaseLease(): Boolean = lease.release()

        internal companion object {
            internal fun create(
                owner: TargetPorts,
                targetGeneration: Long,
                operationIdentity: Long,
                provenance: TargetOperationProvenance
            ): TargetScopeDestructionCommand =
                TargetScopeDestructionCommand(owner, provenance.targetIdentity, targetGeneration, operationIdentity, provenance)
        }
    }

    private enum class TargetOesObligation {
        AwaitingSettlement,
        Required,
        Deleted,
        Transferred,
        Inapplicable,
    }

    private class SurfaceReleaseOwnerBag(
        val target: CurrentTarget,
        val port: SurfaceReleasePort,
    ) : OperationOwnerBag

    private class TargetScopeDestructionOwnerBag(
        val target: CurrentTarget,
        val command: TargetScopeDestructionCommand,
    ) : OperationOwnerBag

    internal class TargetScopeDestructionGraph private constructor(
        private val target: CurrentTarget,
        private val command: TargetScopeDestructionCommand,
        targetIdentity: GlFiniteOperationIdentity,
        namespaceIdentity: GlFiniteOperationIdentity,
        occurrenceFactory: (GlFiniteOperationIdentity, GlDestructionEvidence, OperationOwnerBag) -> OperationOccurrence<GlDestructionEvidence>,
    ) {
        private val targetOperationIdentity: Long = targetIdentity.operationIdentity
        private val targetDeadlineIdentity: Long = targetIdentity.deadlineIdentity
        private val targetInitialWakeGeneration: Long = targetIdentity.initialWakeGeneration
        private val targetTimeoutCause: Throwable = targetIdentity.timeoutCause
        private val namespaceOperationIdentity: Long = namespaceIdentity.operationIdentity
        private val namespaceDeadlineIdentity: Long = namespaceIdentity.deadlineIdentity
        private val namespaceInitialWakeGeneration: Long = namespaceIdentity.initialWakeGeneration
        private val namespaceTimeoutCause: Throwable = namespaceIdentity.timeoutCause
        private val targetProvenance: TargetOperationProvenance = command.provenance
        private val targetOwnerBag = TargetScopeDestructionOwnerBag(target, command)
        private val namespaceOwnerBag = TargetScopeDestructionOwnerBag(target, command)
        internal val targetEvidence: GlDestructionEvidence =
            GlDestructionEvidence(targetOperationIdentity, GlDestructionKind.TargetScope)
        internal val targetOccurrence: OperationOccurrence<GlDestructionEvidence> =
            occurrenceFactory(targetIdentity, targetEvidence, targetOwnerBag)
        internal val namespaceEvidence: GlDestructionEvidence =
            GlDestructionEvidence(namespaceOperationIdentity, GlDestructionKind.ContextNamespace)
        internal val namespaceOccurrence: OperationOccurrence<GlDestructionEvidence> =
            occurrenceFactory(namespaceIdentity, namespaceEvidence, namespaceOwnerBag)
        private var frozen: Boolean = false
        private var surfaceTextureReleased: Boolean = false
        private var targetReceiptSelected: Boolean = false
        private var namespaceTransferSelected: Boolean = false
        private var targetApplied: Boolean = false
        private var namespaceApplied: Boolean = false

        internal val targetGeneration: Long
            get() = command.targetGeneration

        internal fun withHandles(block: (SurfaceTexture?, Int) -> Unit): TargetPortUseOutcome =
            command.withHandles(block)

        internal fun recordSurfaceTextureRelease() {
            check(!frozen && !surfaceTextureReleased)
            surfaceTextureReleased = true
        }

        internal fun recordTargetReceipt() {
            check(!frozen && !targetReceiptSelected && !namespaceTransferSelected)
            targetReceiptSelected = true
        }

        internal fun recordNamespaceTransfer() {
            check(!frozen && !targetReceiptSelected && !namespaceTransferSelected)
            namespaceTransferSelected = true
        }

        internal fun freeze() {
            if (!frozen) frozen = true
        }

        internal fun applyTargetProjection(): Boolean = target.applyTargetScopeDestructionGraph(this)

        internal fun applyNamespaceProjection(): Boolean = target.applyTargetScopeNamespaceGraph(this)

        internal fun isFrozen(): Boolean = frozen

        internal fun isTargetReceiptSelected(): Boolean = targetReceiptSelected

        internal fun isNamespaceTransferSelected(): Boolean = namespaceTransferSelected

        internal fun isSurfaceTextureReleased(): Boolean = surfaceTextureReleased

        internal fun matches(
            expectedTarget: CurrentTarget,
            targetIdentity: GlFiniteOperationIdentity,
            namespaceIdentity: GlFiniteOperationIdentity,
            expectedCommand: TargetScopeDestructionCommand,
        ): Boolean {
            if (target !== expectedTarget || command !== expectedCommand ||
                command.targetGeneration != target.generation ||
                command.operationIdentity != targetOperationIdentity ||
                command.provenance !== targetProvenance ||
                expectedCommand.provenance !== targetProvenance
            ) {
                return false
            }
            return finiteIdentityMatches(
                expected = targetIdentity,
                storedOperationIdentity = targetOperationIdentity,
                storedDeadlineIdentity = targetDeadlineIdentity,
                storedInitialWakeGeneration = targetInitialWakeGeneration,
                storedTimeoutCause = targetTimeoutCause,
                occurrence = targetOccurrence,
                evidence = targetEvidence,
                expectedKind = GlDestructionKind.TargetScope,
                expectedOwnerBag = targetOwnerBag,
            ) && finiteIdentityMatches(
                expected = namespaceIdentity,
                storedOperationIdentity = namespaceOperationIdentity,
                storedDeadlineIdentity = namespaceDeadlineIdentity,
                storedInitialWakeGeneration = namespaceInitialWakeGeneration,
                storedTimeoutCause = namespaceTimeoutCause,
                occurrence = namespaceOccurrence,
                evidence = namespaceEvidence,
                expectedKind = GlDestructionKind.ContextNamespace,
                expectedOwnerBag = namespaceOwnerBag,
            )
        }

        private fun finiteIdentityMatches(
            expected: GlFiniteOperationIdentity,
            storedOperationIdentity: Long,
            storedDeadlineIdentity: Long,
            storedInitialWakeGeneration: Long,
            storedTimeoutCause: Throwable,
            occurrence: OperationOccurrence<GlDestructionEvidence>,
            evidence: GlDestructionEvidence,
            expectedKind: GlDestructionKind,
            expectedOwnerBag: TargetScopeDestructionOwnerBag,
        ): Boolean {
            val deadline = occurrence.deadlineOccurrence ?: return false
            val wakeLink = occurrence.controlWakeLink ?: return false
            return expected.operationIdentity == storedOperationIdentity &&
                    expected.deadlineIdentity == storedDeadlineIdentity &&
                    expected.initialWakeGeneration == storedInitialWakeGeneration &&
                    expected.timeoutCause === storedTimeoutCause &&
                    occurrence.identity == storedOperationIdentity &&
                    occurrence.ownerBag === expectedOwnerBag &&
                    expectedOwnerBag.target === target &&
                    expectedOwnerBag.command === command &&
                    occurrence.returnCell.evidence === evidence &&
                    evidence.operationIdentity == storedOperationIdentity &&
                    evidence.destructionKind == expectedKind &&
                    evidence.receipt.operationIdentity == storedOperationIdentity &&
                    evidence.receipt.destructionKind == expectedKind &&
                    deadline.identity == storedDeadlineIdentity &&
                    deadline.boundOccurrenceIdentity == storedOperationIdentity &&
                    deadline.durationNanos == glEnteredOperationSafetyNanos &&
                    wakeLink.generation == storedInitialWakeGeneration &&
                    deadline.timeoutCause === storedTimeoutCause
        }

        internal fun markTargetApplied(): Boolean {
            if (targetApplied) return false
            targetApplied = true
            return true
        }

        internal fun markNamespaceApplied(): Boolean {
            if (namespaceApplied) return false
            namespaceApplied = true
            return true
        }

        internal companion object {
            internal fun create(
                targetOwner: TargetOwner,
                constructionProof: () -> Unit,
                target: CurrentTarget,
                command: TargetScopeDestructionCommand,
                targetIdentity: GlFiniteOperationIdentity,
                namespaceIdentity: GlFiniteOperationIdentity,
                occurrenceFactory: (GlFiniteOperationIdentity, GlDestructionEvidence, OperationOwnerBag) -> OperationOccurrence<GlDestructionEvidence>,
            ): TargetScopeDestructionGraph {
                check(targetOwner.acceptsConstructionProof(constructionProof))
                return TargetScopeDestructionGraph(target, command, targetIdentity, namespaceIdentity, occurrenceFactory)
            }
        }
    }

    private lateinit var ports: TargetPorts
    private lateinit var targetScopeDestructionGraph: TargetScopeDestructionGraph
    private lateinit var surfaceReleaseSuffixEvidence: TargetSurfaceReleaseSuffixEvidence
    private lateinit var targetScopeSuffixEvidence: TargetScopeSuffixEvidence
    private lateinit var namespaceSuffixEvidence: TargetNamespaceSuffixEvidence
    private val retirementCompleteEvidence: TargetRetirementCompleteEvidence =
        TargetRetirementCompleteEvidence.create(targetOwner, constructionProof, target.identity)
    internal lateinit var surfaceReleaseOccurrence: OperationOccurrence<TargetSurfaceReleaseEvidence>
        private set
    internal val surfaceReleaseOperation: SurfaceReleaseOperation = SurfaceReleaseOperation(this)

    private var surfaceObligation: TargetResourceObligation = TargetResourceObligation.AwaitingSettlement
    private var surfaceTextureObligation: TargetResourceObligation = TargetResourceObligation.AwaitingSettlement
    private var oesTextureObligation: TargetOesObligation = TargetOesObligation.AwaitingSettlement
    private var targetDestructionReceipt: GlDestructionSuccessReceipt? = null
    private var contextNamespaceTransfer: TargetScopeDestructionGraph? = null
    private var contextNamespaceReceipt: GlDestructionSuccessReceipt? = null
    private var installed: Boolean = false
    private var cleanup: Boolean = false
    private var terminalCleanupOccurrencesConverted: Boolean = false
    private var ownedOesTextureName: Int = 0
    private var ownedSurfaceTexture: SurfaceTexture? = null
    private var ownedSurface: Surface? = null

    override val installedResources: Boolean
        get() = installed

    override val surface: Surface?
        get() = ownedSurface

    override val surfaceTexture: SurfaceTexture?
        get() = ownedSurfaceTexture

    override val oesTextureName: Int
        get() = ownedOesTextureName

    internal fun appliedSurfaceReleaseReceipt(): TargetSurfaceReleaseReceipt? =
        surfaceReleaseOccurrence.settlementGate.withLock {
            val evidence = surfaceReleaseOccurrence.returnCell.evidence
            val applied = evidence === surfaceReleaseEvidence &&
                    surfaceReleaseOccurrence.returnCell.disposition ==
                    OperationReturnDisposition.Normal &&
                    surfaceReleaseOccurrence.returnCell.use != OperationReturnUse.Unclaimed &&
                    evidence.receipt === surfaceReleaseReceipt &&
                    surfaceReleaseReceipt.operationIdentity ==
                    surfaceReleaseOccurrence.identity
            surfaceReleaseReceipt.takeIf { applied }
        }

    internal val isFullyRetired: Boolean
        get() = targetGate.withLock {
            val surfaceSettled =
                surfaceObligation == TargetResourceObligation.Completed || surfaceObligation == TargetResourceObligation.Inapplicable
            val targetScopeInapplicable =
                surfaceTextureObligation == TargetResourceObligation.Inapplicable && oesTextureObligation == TargetOesObligation.Inapplicable
            val oesSettled =
                oesTextureObligation == TargetOesObligation.Deleted || oesTextureObligation == TargetOesObligation.Inapplicable ||
                        (oesTextureObligation == TargetOesObligation.Transferred && contextNamespaceReceipt != null)
            val targetScopeSettled =
                targetScopeInapplicable || ((targetDestructionReceipt != null || contextNamespaceReceipt != null) &&
                        (surfaceTextureObligation == TargetResourceObligation.Completed ||
                                surfaceTextureObligation == TargetResourceObligation.Inapplicable) && oesSettled)
            surfaceSettled && targetScopeSettled
        }

    internal fun retirementSuffixEvidence(): TargetRetirementSuffixEvidence = targetGate.withLock {
        retirementSuffixEvidenceLocked()
    }

    private fun retirementSuffixEvidenceLocked(): TargetRetirementSuffixEvidence {
        check(targetGate.isHeldByCurrentThread)
        return when {
            surfaceObligation == TargetResourceObligation.AwaitingSettlement ||
                    surfaceObligation == TargetResourceObligation.Required ->
                surfaceReleaseSuffixEvidence

            oesTextureObligation == TargetOesObligation.Transferred && contextNamespaceReceipt == null ->
                namespaceSuffixEvidence

            surfaceTextureObligation == TargetResourceObligation.AwaitingSettlement ||
                    surfaceTextureObligation == TargetResourceObligation.Required ||
                    oesTextureObligation == TargetOesObligation.AwaitingSettlement ||
                    oesTextureObligation == TargetOesObligation.Required ->
                targetScopeSuffixEvidence

            else -> retirementCompleteEvidence
        }
    }

    internal fun quarantineEvidence(): TargetQuarantineEvidence? {
        var suffix: TargetRetirementSuffixEvidence? = null
        var leaseCount = 0
        val quarantineEligible = targetGate.withLock {
            if (!cleanup && !target.retirementAdmissionClosed) return@withLock false
            suffix = retirementSuffixEvidenceLocked()
            if (suffix is TargetRetirementCompleteEvidence) return@withLock false
            leaseCount = ports.leaseCountLocked
            true
        }
        if (!quarantineEligible) return null
        return TargetQuarantineEvidence.create(
            targetOwner = targetOwner,
            constructionProof = constructionProof,
            targetIdentity = target.identity,
            owner = target,
            suffix = checkNotNull(suffix),
            activeLeaseCount = leaseCount,
        )
    }

    private val surfaceReleaseReceipt: TargetSurfaceReleaseReceipt =
        TargetSurfaceReleaseReceipt.create(targetOwner, constructionProof, surfaceReleaseOperationIdentity)
    private val surfaceReleaseEvidence: TargetSurfaceReleaseEvidence =
        TargetSurfaceReleaseEvidence.create(
            targetOwner,
            constructionProof,
            surfaceReleaseOperationIdentity,
            surfaceReleaseReceipt,
        )

    internal fun bindPorts(targetOwner: TargetOwner, constructionProof: () -> Unit, ports: TargetPorts) {
        check(targetOwner.acceptsConstructionProof(constructionProof))
        check(!this::ports.isInitialized)
        this.ports = ports
        surfaceReleaseOccurrence =
            OperationOccurrence(
                identity = surfaceReleaseOperationIdentity,
                clock = clock,
                returnCell = OperationReturnCell(surfaceReleaseEvidence),
                ownerBag = SurfaceReleaseOwnerBag(target, ports.precreatedSurfaceReleasePort),
                deadlineIdentity = surfaceReleaseDeadlineIdentity,
                deadlineDurationNanos = androidEnteredOperationSafetyNanos,
                initialWakeGeneration = surfaceReleaseDeadlineWakeGeneration,
                timeoutCause = surfaceReleaseTimeoutCause,
                wakeSignal = settlementSignal,
            )
        targetScopeDestructionGraph =
            TargetScopeDestructionGraph.create(
                targetOwner,
                constructionProof,
                target,
                ports.targetScopeDestructionCommand,
                targetDestructionIdentity,
                namespaceDestructionIdentity,
                ::destructionOccurrence,
            )
        surfaceReleaseSuffixEvidence = TargetSurfaceReleaseSuffixEvidence.create(
            targetOwner,
            constructionProof,
            target.identity,
            surfaceReleaseOccurrence,
        )
        targetScopeSuffixEvidence = TargetScopeSuffixEvidence.create(
            targetOwner,
            constructionProof,
            target.identity,
            targetScopeDestructionGraph,
        )
        namespaceSuffixEvidence = TargetNamespaceSuffixEvidence.create(
            targetOwner,
            constructionProof,
            target.identity,
            targetScopeDestructionGraph,
            targetScopeDestructionGraph.namespaceOccurrence,
        )
    }

    private fun beginUninstalledSurfaceReleaseSubmissionReadiness(constructionSettled: Boolean): Boolean {
        val surfaceReleased = appliedSurfaceReleaseReceipt() != null
        val ready = targetGate.withLock {
            constructionSettled && cleanup && surfaceObligation == TargetResourceObligation.Required && ownedSurface != null &&
                    !surfaceReleased && ports.hasNoLeasesOrInstalledWorkLocked
        }
        return ready
    }

    internal fun detachedSurfaceReleasePort(): SurfaceReleasePort? =
        ports.detachedSurfaceReleasePort(
            targetGate.withLock {
                surfaceObligation == TargetResourceObligation.Required
            }
        )

    internal fun commitSurfaceReleasePort(port: SurfaceReleasePort): Boolean =
        ports.commitSurfaceReleasePort(
            port,
            targetGate.withLock {
                surfaceObligation == TargetResourceObligation.Required
            },
        )

    internal fun releaseEnteredSurface(port: SurfaceReleasePort): TargetPortUseOutcome {
        if (!ports.isExactSurfaceReleasePort(port)) return TargetPortUseOutcome.Rejected
        val entered = surfaceReleaseOccurrence.settlementGate.withLock {
            surfaceReleaseOccurrence.entryDisposition == OperationEntryDisposition.Entered &&
                    surfaceReleaseOccurrence.returnCell.disposition == OperationReturnDisposition.Empty
        }
        return if (entered && ports.releaseEnteredSurface(port)) {
            TargetPortUseOutcome.BodyReturned
        } else {
            TargetPortUseOutcome.Rejected
        }
    }

    internal fun publishSurfaceReleaseNormalReturn(): Boolean {
        val published = surfaceReleaseOccurrence.publishNormalReturn()
        if (published) settlementSignal.signal()
        return published
    }

    internal fun publishSurfaceReleaseThrownReturn(thrown: Exception): Boolean {
        val published = surfaceReleaseOccurrence.publishThrownReturn(thrown)
        if (published) settlementSignal.signal()
        return published
    }

    internal fun prepareTargetScopeDestructionGraph(
        targetIdentity: GlFiniteOperationIdentity,
        namespaceIdentity: GlFiniteOperationIdentity,
    ): TargetScopeDestructionGraph? {
        if (!targetScopeDestructionGraph.matches(target, targetIdentity, namespaceIdentity, ports.targetScopeDestructionCommand)) {
            return null
        }
        val surfaceReleaseReceiptApplied = appliedSurfaceReleaseReceipt() != null
        return targetGate.withLock {
            if (surfaceObligation == TargetResourceObligation.Required && surfaceReleaseReceiptApplied) {
                surfaceObligation = TargetResourceObligation.Completed
            }
            val surfaceReady = surfaceObligation == TargetResourceObligation.Completed || surfaceObligation == TargetResourceObligation.Inapplicable
            val targetScopeApplicable = surfaceTextureObligation == TargetResourceObligation.Required || oesTextureObligation == TargetOesObligation.Required
            if (!surfaceReady || !targetScopeApplicable ||
                surfaceTextureObligation == TargetResourceObligation.AwaitingSettlement ||
                oesTextureObligation == TargetOesObligation.AwaitingSettlement
            ) {
                return@withLock null
            }
            targetScopeDestructionGraph
        }
    }

    internal fun applyTargetScopeDestructionGraph(graph: TargetScopeDestructionGraph): Boolean {
        var normal = false
        var thrown = false
        var claimed = false
        graph.targetOccurrence.settlementGate.withLock {
            if (graph.targetOccurrence.returnCell.evidence !== graph.targetEvidence) {
                return@withLock
            }
            normal = graph.targetOccurrence.returnCell.disposition == OperationReturnDisposition.Normal
            thrown = graph.targetOccurrence.returnCell.disposition == OperationReturnDisposition.Thrown
            claimed = graph.targetOccurrence.returnCell.use != OperationReturnUse.Unclaimed
        }
        if (!claimed || !normal && !thrown) return false
        return targetGate.withLock {
            if (graph !== targetScopeDestructionGraph || !graph.isFrozen() ||
                graph.targetOccurrence.returnCell.evidence !== graph.targetEvidence
            ) {
                return@withLock false
            }
            val targetReceipt = graph.isTargetReceiptSelected()
            val transfer = graph.isNamespaceTransferSelected()
            val surfaceTextureReleased = graph.isSurfaceTextureReleased()
            if (targetReceipt && transfer) return@withLock false
            if (targetReceipt && (!normal || graph.targetEvidence.result != GlOperationResult.Success ||
                        graph.targetEvidence.contextIntegrity != ContextIntegrity.Intact)
            ) {
                return@withLock false
            }
            if (transfer && (graph.targetEvidence.contextIntegrity == ContextIntegrity.Intact ||
                        graph.targetEvidence.result == GlOperationResult.Success)
            ) {
                return@withLock false
            }
            if (normal && oesTextureObligation == TargetOesObligation.Required && !targetReceipt && !transfer) {
                return@withLock false
            }
            if ((targetReceipt || transfer) && oesTextureObligation != TargetOesObligation.Required) {
                return@withLock false
            }
            if (surfaceTextureReleased && surfaceTextureObligation != TargetResourceObligation.Required) {
                return@withLock false
            }
            if (normal && surfaceTextureObligation == TargetResourceObligation.Required && !surfaceTextureReleased) {
                return@withLock false
            }
            if (!graph.markTargetApplied()) return@withLock false
            if (surfaceTextureReleased) {
                surfaceTextureObligation = TargetResourceObligation.Completed
            }
            if (targetReceipt) {
                oesTextureObligation = TargetOesObligation.Deleted
                targetDestructionReceipt = graph.targetEvidence.receipt
            } else if (transfer) {
                oesTextureObligation = TargetOesObligation.Transferred
                contextNamespaceTransfer = graph
            }
            true
        }
    }

    internal fun applyTargetScopeNamespaceGraph(graph: TargetScopeDestructionGraph): Boolean {
        val mechanicallyClaimed = graph.namespaceOccurrence.settlementGate.withLock {
            graph.namespaceOccurrence.returnCell.disposition == OperationReturnDisposition.Normal &&
                    graph.namespaceOccurrence.returnCell.use != OperationReturnUse.Unclaimed &&
                    graph.namespaceOccurrence.returnCell.evidence === graph.namespaceEvidence &&
                    graph.namespaceEvidence.result == GlOperationResult.Success
        }
        if (!mechanicallyClaimed) return false
        return targetGate.withLock {
            if (graph !== targetScopeDestructionGraph || graph !== contextNamespaceTransfer ||
                oesTextureObligation != TargetOesObligation.Transferred || contextNamespaceReceipt != null ||
                !graph.markNamespaceApplied()
            ) {
                return@withLock false
            }
            contextNamespaceReceipt = graph.namespaceEvidence.receipt
            true
        }
    }

    internal fun adoptConstructionOesTextureName(oesTextureName: Int) {
        check(targetGate.isHeldByCurrentThread)
        check(!installed && ownedOesTextureName == 0) { "Target OES ownership has already been assigned" }
        require(oesTextureName != 0)
        ownedOesTextureName = oesTextureName
    }

    internal fun adoptConstructionSurfaceTexture(surfaceTexture: SurfaceTexture) {
        check(targetGate.isHeldByCurrentThread)
        check(!installed && ownedSurfaceTexture == null) { "Target SurfaceTexture ownership has already been assigned" }
        ownedSurfaceTexture = surfaceTexture
    }

    internal fun adoptConstructionSurface(surface: Surface) {
        check(targetGate.isHeldByCurrentThread)
        check(!installed && ownedSurface == null)
        ownedSurface = surface
    }

    internal fun finishConstructionOwnership(installed: Boolean) {
        check(targetGate.isHeldByCurrentThread)
        check(!this.installed && !cleanup)
        if (installed) {
            check(ownedOesTextureName != 0)
            check(ownedSurfaceTexture != null)
            check(ownedSurface != null)
            this.installed = true
            surfaceObligation = TargetResourceObligation.Required
            surfaceTextureObligation = TargetResourceObligation.Required
            oesTextureObligation = TargetOesObligation.Required
        } else {
            cleanup = true
        }
    }

    internal fun settleConstructionResourceObligations(): Boolean {
        var surfaceInapplicable = false
        val settled = targetGate.withLock {
            if (!cleanup ||
                surfaceObligation != TargetResourceObligation.AwaitingSettlement ||
                surfaceTextureObligation !=
                TargetResourceObligation.AwaitingSettlement ||
                oesTextureObligation != TargetOesObligation.AwaitingSettlement
            ) {
                return@withLock false
            }
            surfaceObligation =
                if (ownedSurface == null) {
                    TargetResourceObligation.Inapplicable
                } else {
                    TargetResourceObligation.Required
                }
            surfaceTextureObligation =
                if (ownedSurfaceTexture == null) {
                    TargetResourceObligation.Inapplicable
                } else {
                    TargetResourceObligation.Required
                }
            oesTextureObligation =
                if (ownedOesTextureName == 0) {
                    TargetOesObligation.Inapplicable
                } else {
                    TargetOesObligation.Required
                }
            surfaceInapplicable = surfaceObligation == TargetResourceObligation.Inapplicable
            true
        }
        if (settled && surfaceInapplicable) {
            surfaceReleaseOccurrence.arbitrateTerminal(mandatoryCleanup = false)
        }
        return settled
    }

    internal fun convertConstructionCleanupOccurrencesForTerminal(): Boolean {
        val obligation = targetGate.withLock {
            if (!cleanup ||
                terminalCleanupOccurrencesConverted ||
                surfaceObligation == TargetResourceObligation.AwaitingSettlement ||
                surfaceTextureObligation == TargetResourceObligation.AwaitingSettlement ||
                oesTextureObligation == TargetOesObligation.AwaitingSettlement
            ) {
                return@withLock null
            }
            terminalCleanupOccurrencesConverted = true
            surfaceObligation
        } ?: return false
        surfaceReleaseOccurrence.arbitrateTerminal(
            mandatoryCleanup = obligation == TargetResourceObligation.Required
        )
        return true
    }

    private fun destructionOccurrence(
        identity: GlFiniteOperationIdentity,
        evidence: GlDestructionEvidence,
        ownerBag: OperationOwnerBag,
    ): OperationOccurrence<GlDestructionEvidence> =
        OperationOccurrence(
            identity = identity.operationIdentity,
            clock = clock,
            returnCell = OperationReturnCell(evidence),
            ownerBag = ownerBag,
            deadlineIdentity = identity.deadlineIdentity,
            deadlineDurationNanos = glEnteredOperationSafetyNanos,
            initialWakeGeneration = identity.initialWakeGeneration,
            timeoutCause = identity.timeoutCause,
            wakeSignal = settlementSignal,
        )

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            target: CurrentTarget,
            targetGate: ReentrantLock,
            clock: EngineClock,
            settlementSignal: SettlementSignal,
            surfaceReleaseOperationIdentity: Long,
            surfaceReleaseDeadlineIdentity: Long,
            surfaceReleaseDeadlineWakeGeneration: Long,
            surfaceReleaseTimeoutCause: Throwable,
            targetDestructionIdentity: GlFiniteOperationIdentity,
            namespaceDestructionIdentity: GlFiniteOperationIdentity,
        ): TargetRetirement {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetRetirement(
                targetOwner,
                constructionProof,
                target,
                targetGate,
                clock,
                settlementSignal,
                surfaceReleaseOperationIdentity,
                surfaceReleaseDeadlineIdentity,
                surfaceReleaseDeadlineWakeGeneration,
                surfaceReleaseTimeoutCause,
                targetDestructionIdentity,
                namespaceDestructionIdentity,
            )
        }
    }
}
