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
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
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

internal class TargetSurfaceReleaseReceipt internal constructor(
    internal val operationIdentity: Long,
) : OperationReceipt

internal class TargetSurfaceReleaseEvidence internal constructor(
    internal val operationIdentity: Long,
    override val receipt: TargetSurfaceReleaseReceipt,
) : OperationEvidence {
    override val returnedOwner: OperationReturnedOwner? = null

    init {
        require(operationIdentity > 0L)
        require(receipt.operationIdentity == operationIdentity)
    }
}

internal class TargetRetirement private constructor(
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
        internal val operationIdentity: Long,
        internal val provenance: TargetOperationProvenance,
    ) {
        private val lease: TargetPorts.TargetLease = TargetPorts.TargetLease(owner)
        private var consumed: Boolean = false

        internal fun releaseSurface(): Boolean =
            owner.releaseSurface(this)

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
                SurfaceReleasePort(owner, operationIdentity, provenance)
        }
    }

    internal class TargetScopeDestructionCommand private constructor(
        private val owner: TargetPorts,
        internal val targetGeneration: Long,
        internal val operationIdentity: Long,
        internal val provenance: TargetOperationProvenance,
    ) {
        private val lease: TargetPorts.TargetLease = TargetPorts.TargetLease(owner)
        private var consumed: Boolean = false

        internal fun withHandles(block: (SurfaceTexture?, Int) -> Unit): Boolean =
            owner.withTargetScopeDestructionLease(this, block)

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
                TargetScopeDestructionCommand(owner, targetGeneration, operationIdentity, provenance)
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
        internal val targetEvidence: GlDestructionEvidence =
            GlDestructionEvidence(targetIdentity.operationIdentity, GlDestructionKind.TargetScope)
        internal val targetOccurrence: OperationOccurrence<GlDestructionEvidence> =
            occurrenceFactory(targetIdentity, targetEvidence, TargetScopeDestructionOwnerBag(target, command))
        internal val namespaceEvidence: GlDestructionEvidence =
            GlDestructionEvidence(namespaceIdentity.operationIdentity, GlDestructionKind.ContextNamespace)
        internal val namespaceOccurrence: OperationOccurrence<GlDestructionEvidence> =
            occurrenceFactory(namespaceIdentity, namespaceEvidence, TargetScopeDestructionOwnerBag(target, command))
        private var frozen: Boolean = false
        private var surfaceTextureReleased: Boolean = false
        private var targetReceiptSelected: Boolean = false
        private var namespaceTransferSelected: Boolean = false
        private var targetApplied: Boolean = false
        private var namespaceApplied: Boolean = false

        internal val targetGeneration: Long
            get() = command.targetGeneration

        internal fun withHandles(block: (SurfaceTexture?, Int) -> Unit): Boolean = command.withHandles(block)

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
        ): Boolean =
            target === expectedTarget && command === expectedCommand &&
                    targetOccurrence.identity == targetIdentity.operationIdentity &&
                    namespaceOccurrence.identity == namespaceIdentity.operationIdentity

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
    internal lateinit var surfaceReleaseOccurrence: OperationOccurrence<TargetSurfaceReleaseEvidence>
        private set

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

    internal val hasAppliedSurfaceReleaseReceipt: Boolean
        get() = surfaceReleaseOccurrence.settlementGate.withLock {
            val evidence = surfaceReleaseOccurrence.returnCell.evidence
            evidence === surfaceReleaseEvidence &&
                    surfaceReleaseOccurrence.returnCell.disposition ==
                    OperationReturnDisposition.Normal &&
                    surfaceReleaseOccurrence.returnCell.use != OperationReturnUse.Unclaimed &&
                    evidence.receipt === surfaceReleaseReceipt &&
                    surfaceReleaseReceipt.operationIdentity ==
                    surfaceReleaseOccurrence.identity
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

    private val surfaceReleaseReceipt: TargetSurfaceReleaseReceipt = TargetSurfaceReleaseReceipt(surfaceReleaseOperationIdentity)
    private val surfaceReleaseEvidence: TargetSurfaceReleaseEvidence = TargetSurfaceReleaseEvidence(surfaceReleaseOperationIdentity, surfaceReleaseReceipt)

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
    }

    internal fun beginSurfaceReleaseSubmission(): Boolean {
        if (!target.isSurfaceReleaseReady) return false
        return surfaceReleaseOccurrence.beginSubmission()
    }

    internal fun beginUninstalledSurfaceReleaseSubmission(constructionSettled: Boolean): Boolean {
        val surfaceReleased = hasAppliedSurfaceReleaseReceipt
        val ready = targetGate.withLock {
            constructionSettled && cleanup && surfaceObligation == TargetResourceObligation.Required && ownedSurface != null &&
                    !surfaceReleased && ports.hasNoLeasesOrInstalledWorkLocked
        }
        return ready && surfaceReleaseOccurrence.beginSubmission()
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

    internal fun enterSurfaceRelease(port: SurfaceReleasePort): OperationEntryResult {
        if (!ports.isExactSurfaceReleasePort(port)) return OperationEntryResult.NotCurrent
        val entryResult = surfaceReleaseOccurrence.tryEnter()
        if (entryResult == OperationEntryResult.NotCurrent) return entryResult
        if (entryResult == OperationEntryResult.Entered && surfaceReleaseOccurrence.domain == OperationDomain.Active) {
            surfaceReleaseOccurrence.requestDeadlineWake()
        }
        settlementSignal.signal()
        return entryResult
    }

    internal fun releaseEnteredSurface(port: SurfaceReleasePort): Boolean {
        if (!ports.isExactSurfaceReleasePort(port)) return false
        val entered = surfaceReleaseOccurrence.settlementGate.withLock {
            surfaceReleaseOccurrence.entryDisposition == OperationEntryDisposition.Entered &&
                    surfaceReleaseOccurrence.returnCell.disposition == OperationReturnDisposition.Empty
        }
        return entered && ports.releaseEnteredSurface(port)
    }

    internal fun publishSurfaceReleaseNormalReturn(): Boolean {
        val published = surfaceReleaseOccurrence.publishNormalReturn()
        if (published) settlementSignal.signal()
        return published
    }

    internal fun publishSurfaceReleaseThrownReturn(thrown: Throwable): Boolean {
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
        val surfaceReleaseReceiptApplied = hasAppliedSurfaceReleaseReceipt
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
                graph.targetOccurrence.returnCell.evidence !== graph.targetEvidence || !graph.markTargetApplied()
            ) {
                return@withLock false
            }
            val targetReceipt = graph.isTargetReceiptSelected()
            val transfer = graph.isNamespaceTransferSelected()
            if (targetReceipt && transfer) return@withLock false
            if (targetReceipt && (!normal || graph.targetEvidence.result != GlOperationResult.Success ||
                        graph.targetEvidence.contextIntegrity != ContextIntegrity.Intact)
            ) {
                return@withLock false
            }
            if (normal && oesTextureObligation == TargetOesObligation.Required && !targetReceipt && !transfer) {
                return@withLock false
            }
            if (graph.isSurfaceTextureReleased() && surfaceTextureObligation == TargetResourceObligation.Required) {
                surfaceTextureObligation = TargetResourceObligation.Completed
            }
            if (targetReceipt && oesTextureObligation == TargetOesObligation.Required) {
                oesTextureObligation = TargetOesObligation.Deleted
                targetDestructionReceipt = graph.targetEvidence.receipt
            } else if (transfer && oesTextureObligation == TargetOesObligation.Required) {
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
        check(!installed && ownedOesTextureName == 0) { "Target OES ownership has already been assigned" }
        require(oesTextureName != 0)
        ownedOesTextureName = oesTextureName
    }

    internal fun adoptConstructionSurfaceTexture(surfaceTexture: SurfaceTexture) {
        check(!installed && ownedSurfaceTexture == null) { "Target SurfaceTexture ownership has already been assigned" }
        ownedSurfaceTexture = surfaceTexture
    }

    internal fun adoptConstructionSurface(surface: Surface) {
        check(!installed && ownedSurface == null)
        ownedSurface = surface
    }

    internal fun finishConstructionOwnership(installed: Boolean) {
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
