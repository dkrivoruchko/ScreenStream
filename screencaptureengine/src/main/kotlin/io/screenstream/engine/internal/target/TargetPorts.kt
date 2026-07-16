package io.screenstream.engine.internal.target

import android.graphics.SurfaceTexture
import android.view.Surface
import io.screenstream.engine.internal.AndroidVirtualDisplayAttachEvidence
import io.screenstream.engine.internal.AndroidVirtualDisplayCreationEvidence
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class TargetPorts private constructor(
    private val targetOwner: TargetOwner,
    private val constructionProof: () -> Unit,
    private val target: CurrentTarget,
    private val targetGate: ReentrantLock,
    private val generation: Long,
    listenerInstallationOperationIdentity: Long,
    surfaceReleaseOperationIdentity: Long,
    targetDestructionOperationIdentity: Long,
    private val settlementSignal: SettlementSignal,
    private val resources: ResourceAccess,
) {
    internal interface ResourceAccess {
        val installedResources: Boolean
        val surface: Surface?
        val surfaceTexture: SurfaceTexture?
        val oesTextureName: Int
    }

    internal interface AndroidSurfacePort {
        val operationIdentity: Long
        val operationKind: TargetProducerOperationKind
        val provenance: TargetOperationProvenance

        fun withSurface(block: (Surface) -> Unit): Boolean
    }

    internal interface AndroidDetachPort {
        val operationIdentity: Long
        val detachKind: TargetProducerDetachKind
        val provenance: TargetOperationProvenance
    }

    internal interface AndroidListenerInstallationPort {
        val operationIdentity: Long
        val provenance: TargetOperationProvenance

        fun withListener(block: (SurfaceTexture, SurfaceTexture.OnFrameAvailableListener) -> Unit): Boolean
    }

    internal interface AndroidListenerRemovalPort {
        val operationIdentity: Long
        val provenance: TargetOperationProvenance

        fun withSurfaceTexture(block: (SurfaceTexture) -> Unit): Boolean
    }

    internal interface GlFramePort {
        val operationIdentity: Long
        val provenance: TargetOperationProvenance

        fun withHandles(block: (SurfaceTexture, Int) -> Unit): Boolean
    }

    internal class TargetLease(
        private val owner: TargetPorts,
    ) {
        private enum class Disposition {
            Active,
            Released,
            FatalRetained,
        }

        private var disposition: Disposition = Disposition.Active

        fun release(): Boolean {
            val released = owner.targetGate.withLock {
                if (disposition != Disposition.Active) return@withLock false
                check(owner.leaseCount > 0)
                disposition = Disposition.Released
                owner.leaseCount -= 1
                true
            }
            if (released) owner.settlementSignal.signal()
            return released
        }

        fun retainAfterFatal(): Boolean = owner.targetGate.withLock {
            if (disposition != Disposition.Active) return@withLock false
            disposition = Disposition.FatalRetained
            true
        }
    }

    private class AndroidSurfacePortImpl(
        private val owner: TargetPorts,
        override val operationIdentity: Long,
        override val operationKind: TargetProducerOperationKind,
        override val provenance: TargetOperationProvenance,
        private val producerEvidence: TargetProducerEvidence,
        private val noProducerEvidence: Array<TargetNoProducerEvidence>,
    ) : AndroidSurfacePort {
        val lease: TargetLease = TargetLease(owner)
        private var rawHandleConsumed: Boolean = false
        private var outcomeClaimed: Boolean = false

        override fun withSurface(block: (Surface) -> Unit): Boolean =
            owner.withSurfaceLease(this, block)

        fun claimRawHandleLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (rawHandleConsumed) return false
            rawHandleConsumed = true
            return true
        }

        fun claimProducerEvidence(): TargetProducerEvidence? {
            check(owner.targetGate.isHeldByCurrentThread)
            if (outcomeClaimed) return null
            outcomeClaimed = true
            return producerEvidence
        }

        fun claimNoProducerEvidence(reason: TargetNoProducerReason): TargetNoProducerEvidence? {
            check(owner.targetGate.isHeldByCurrentThread)
            if (outcomeClaimed) return null
            outcomeClaimed = true
            return noProducerEvidence[reason.ordinal]
        }

        fun matchesProducerEvidence(evidence: TargetProducerEvidence): Boolean =
            evidence === producerEvidence

        fun matchesNoProducerEvidence(evidence: TargetNoProducerEvidence): Boolean =
            evidence === noProducerEvidence[evidence.reason.ordinal]
    }

    private class AndroidDetachPortImpl(
        private val owner: TargetPorts,
        override val operationIdentity: Long,
        override val detachKind: TargetProducerDetachKind,
        override val provenance: TargetOperationProvenance,
        private val receipt: TargetProducerDetachReceipt,
    ) : AndroidDetachPort {
        private var consumed: Boolean = false

        fun detachReceipt(): TargetProducerDetachReceipt? {
            check(owner.targetGate.isHeldByCurrentThread)
            if (consumed) return null
            consumed = true
            return receipt
        }
    }

    private class AndroidListenerInstallationPortImpl(
        private val owner: TargetPorts,
        override val operationIdentity: Long,
        override val provenance: TargetOperationProvenance,
    ) : AndroidListenerInstallationPort {
        val lease: TargetLease = TargetLease(owner)
        private var consumed: Boolean = false

        override fun withListener(block: (SurfaceTexture, SurfaceTexture.OnFrameAvailableListener) -> Unit): Boolean =
            owner.withListenerInstallationLease(this, block)

        fun claimLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (consumed) return false
            consumed = true
            return true
        }
    }

    private class AndroidListenerRemovalPortImpl(
        private val owner: TargetPorts,
        override val operationIdentity: Long,
        override val provenance: TargetOperationProvenance,
    ) : AndroidListenerRemovalPort {
        val lease: TargetLease = TargetLease(owner)
        private var consumed: Boolean = false

        override fun withSurfaceTexture(block: (SurfaceTexture) -> Unit): Boolean =
            owner.withListenerRemovalLease(this, block)

        fun claimLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (consumed) return false
            consumed = true
            return true
        }
    }

    private class GlFramePortImpl(
        private val owner: TargetPorts,
        override val operationIdentity: Long,
        override val provenance: TargetOperationProvenance,
    ) : GlFramePort {
        val lease: TargetLease = TargetLease(owner)
        private var consumed: Boolean = false

        override fun withHandles(block: (SurfaceTexture, Int) -> Unit): Boolean =
            owner.withGlFrameLease(this, block)

        fun claimLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (consumed) return false
            consumed = true
            return true
        }

        fun belongsTo(expectedTarget: CurrentTarget): Boolean = owner.target === expectedTarget
    }

    private var producerPort: AndroidSurfacePortImpl? = null
    private var producerDetachPort: AndroidDetachPortImpl? = null
    private var listenerInstallationPort: AndroidListenerInstallationPortImpl? = null
    private var listenerRemovalPort: AndroidListenerRemovalPortImpl? = null
    private var surfaceReleasePort: TargetRetirement.SurfaceReleasePort? = null
    private var glFramePort: GlFramePortImpl? = null
    private var leaseCount: Int = 0

    internal val precreatedSurfaceReleasePort: TargetRetirement.SurfaceReleasePort =
        TargetRetirement.SurfaceReleasePort.create(
            this,
            surfaceReleaseOperationIdentity,
            provenance(surfaceReleaseOperationIdentity, TargetPortKind.SurfaceRelease),
        )

    internal val targetScopeDestructionCommand: TargetRetirement.TargetScopeDestructionCommand =
        TargetRetirement.TargetScopeDestructionCommand.create(
            this,
            generation,
            targetDestructionOperationIdentity,
            provenance(targetDestructionOperationIdentity, TargetPortKind.TargetScopeDestruction),
        )

    internal val activeLeaseCount: Int
        get() = targetGate.withLock { leaseCount }

    internal fun acceptsLockedClaim(requester: TargetPorts): Boolean =
        requester === this && targetGate.isHeldByCurrentThread

    internal val hasProducerPortLocked: Boolean
        get() {
            check(targetGate.isHeldByCurrentThread)
            return producerPort != null
        }

    internal val hasGlFramePortLocked: Boolean
        get() {
            check(targetGate.isHeldByCurrentThread)
            return glFramePort != null
        }

    internal val leaseCountLocked: Int
        get() {
            check(targetGate.isHeldByCurrentThread)
            return leaseCount
        }

    internal val hasNoLeasesOrInstalledWorkLocked: Boolean
        get() {
            check(targetGate.isHeldByCurrentThread)
            return leaseCount == 0 && !target.listenerInstalled && !target.sourceSignalsAccepted
        }

    internal fun matchesListenerInstallationOperationIdentity(operationIdentity: Long): Boolean =
        target.listenerInstallationOperationIdentity == operationIdentity

    internal fun registerListenerInstallationPort(operationIdentity: Long): AndroidListenerInstallationPort? {
        require(operationIdentity > 0L)
        val provenance = provenance(operationIdentity, TargetPortKind.ListenerInstallation)
        val candidate = AndroidListenerInstallationPortImpl(this, operationIdentity, provenance)
        return targetGate.withLock {
            if (!target.canRegisterListenerInstallationLocked(operationIdentity, listenerInstallationPort != null)) {
                return@withLock null
            }
            candidate.also { listenerInstallationPort = it }
        }
    }

    internal fun applyListenerInstallationReceipt(port: AndroidListenerInstallationPort, operation: OperationOccurrence<*>): Boolean {
        val mechanicallyReturned = operation.settlementGate.withLock {
            operation.identity == port.operationIdentity && operation.returnCell.disposition == OperationReturnDisposition.Normal
        }
        if (!mechanicallyReturned) return false
        return targetGate.withLock {
            target.applyListenerInstallationLocked(port === listenerInstallationPort, port.operationIdentity)
        }
    }

    internal fun registerProducerPort(operationIdentity: Long, operationKind: TargetProducerOperationKind): AndroidSurfacePort? {
        require(operationIdentity > 0L)
        val portKind = when (operationKind) {
            TargetProducerOperationKind.VirtualDisplayCreation ->
                TargetPortKind.VirtualDisplayCreation

            TargetProducerOperationKind.VirtualDisplayAttachment ->
                TargetPortKind.VirtualDisplayAttachment
        }
        val provenance = provenance(operationIdentity, portKind)
        val producerEvidence = targetProducerEvidence(targetOwner, constructionProof, generation, operationIdentity, operationKind, provenance)
        val noProducerEvidence: Array<TargetNoProducerEvidence> =
            Array(TargetNoProducerReason.entries.size) { index ->
                targetNoProducerEvidence(
                    targetOwner,
                    constructionProof,
                    generation,
                    operationIdentity,
                    operationKind,
                    TargetNoProducerReason.entries[index],
                    provenance,
                )
            }
        val candidate = AndroidSurfacePortImpl(this, operationIdentity, operationKind, provenance, producerEvidence, noProducerEvidence)
        return targetGate.withLock {
            if (!target.canRegisterProducerPortLocked(producerPort != null)) {
                return@withLock null
            }
            candidate.also { producerPort = it }
        }
    }

    internal fun applyProducerEvidence(evidence: TargetProducerEvidence): Boolean = targetGate.withLock {
        val port = producerPort ?: return@withLock false
        target.applyProducerEvidenceLocked(
            evidence,
            port.matchesProducerEvidence(evidence),
            port.operationIdentity,
            port.operationKind,
            port.provenance,
        )
    }

    internal fun applyNoProducerEvidence(evidence: TargetNoProducerEvidence): Boolean = targetGate.withLock {
        val port = producerPort ?: return@withLock false
        target.applyNoProducerEvidenceLocked(
            evidence,
            port.matchesNoProducerEvidence(evidence),
            port.operationIdentity,
            port.operationKind,
            port.provenance,
        )
    }

    internal fun producerEvidenceAfterSettlement(port: AndroidSurfacePort, operation: OperationOccurrence<*>): TargetProducerEvidence? {
        val mechanicallyReturnedWithProducer = operation.settlementGate.withLock {
            if (operation.identity != port.operationIdentity || operation.returnCell.disposition != OperationReturnDisposition.Normal) {
                return@withLock false
            }
            when (port.operationKind) {
                TargetProducerOperationKind.VirtualDisplayCreation ->
                    (operation.returnCell.evidence as? AndroidVirtualDisplayCreationEvidence)?.virtualDisplay != null

                TargetProducerOperationKind.VirtualDisplayAttachment ->
                    operation.returnCell.evidence is AndroidVirtualDisplayAttachEvidence
            }
        }
        if (!mechanicallyReturnedWithProducer) return null
        return targetGate.withLock {
            val exactPort = producerPort ?: return@withLock null
            if (port !== exactPort || port.provenance !== exactPort.provenance) {
                return@withLock null
            }
            exactPort.claimProducerEvidence()
        }
    }

    internal fun noProducerEvidenceAfterSettlement(
        port: AndroidSurfacePort,
        operation: OperationOccurrence<*>,
        reason: TargetNoProducerReason,
    ): TargetNoProducerEvidence? {
        val mechanicallyProven = operation.settlementGate.withLock {
            if (operation.identity != port.operationIdentity) return@withLock false
            when (reason) {
                TargetNoProducerReason.ReturnedWithoutProducer ->
                    port.operationKind ==
                            TargetProducerOperationKind.VirtualDisplayCreation &&
                            operation.returnCell.disposition ==
                            OperationReturnDisposition.Normal &&
                            operation.returnCell.evidence is AndroidVirtualDisplayCreationEvidence &&
                            operation.returnCell.evidence.virtualDisplay == null

                TargetNoProducerReason.Unentered,
                TargetNoProducerReason.Inapplicable,
                    -> operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                        operation.entryDisposition != OperationEntryDisposition.Entered &&
                        (operation.disposition == OperationDisposition.Cancelled ||
                                operation.disposition == OperationDisposition.SchedulerRejected ||
                                operation.disposition == OperationDisposition.DeadlineGuardFailed)
            }
        }
        if (!mechanicallyProven) return null
        return targetGate.withLock {
            val exactPort = producerPort ?: return@withLock null
            if (port !== exactPort || port.provenance !== exactPort.provenance) {
                return@withLock null
            }
            exactPort.claimNoProducerEvidence(reason)
        }
    }

    internal fun producerDetachReceiptAfterSettlement(port: AndroidDetachPort, operation: OperationOccurrence<*>): TargetProducerDetachReceipt? {
        val mechanicallyReturned = operation.settlementGate.withLock {
            operation.identity == port.operationIdentity && operation.returnCell.disposition == OperationReturnDisposition.Normal
        }
        if (!mechanicallyReturned) return null
        return targetGate.withLock {
            val exactPort = producerDetachPort ?: return@withLock null
            if (port !== exactPort || port.provenance !== exactPort.provenance) {
                return@withLock null
            }
            exactPort.detachReceipt()
        }
    }

    internal fun detachedGlFramePort(operationIdentity: Long): GlFramePort? {
        require(operationIdentity > 0L)
        val provenance = provenance(operationIdentity, TargetPortKind.GlFrame)
        val candidate = GlFramePortImpl(this, operationIdentity, provenance)
        return targetGate.withLock {
            if (!resources.installedResources || !target.listenerInstalled || target.generationFenced || glFramePort != null ||
                target.producerState != TargetProducerState.ProducerAttached
            ) {
                return@withLock null
            }
            candidate
        }
    }

    internal fun commitGlFramePort(port: GlFramePort): Boolean = targetGate.withLock {
        val candidate = port as? GlFramePortImpl ?: return@withLock false
        if (!candidate.belongsTo(target) || !resources.installedResources ||
            !target.listenerInstalled || target.generationFenced || glFramePort != null ||
            target.producerState != TargetProducerState.ProducerAttached
        ) {
            return@withLock false
        }
        glFramePort = candidate
        true
    }

    internal fun retireGlFramePortAfterSettlement(port: GlFramePort, operation: OperationOccurrence<*>): Boolean {
        val mechanicallySettled = operation.settlementGate.withLock {
            if (operation.identity != port.operationIdentity) return@withLock false
            if (operation.returnCell.disposition != OperationReturnDisposition.Empty) {
                return@withLock true
            }
            if (operation.submissionDisposition ==
                io.screenstream.engine.internal.settlement.OperationSubmissionDisposition.None &&
                operation.entryDisposition == OperationEntryDisposition.Unentered
            ) {
                return@withLock true
            }
            operation.entryDisposition != OperationEntryDisposition.Entered &&
                    (operation.disposition == OperationDisposition.Cancelled ||
                            operation.disposition == OperationDisposition.SchedulerRejected ||
                            operation.disposition == OperationDisposition.DeadlineGuardFailed)
        }
        if (!mechanicallySettled) return false
        return targetGate.withLock {
            if (port !== glFramePort || port.provenance !== glFramePort?.provenance) {
                return@withLock false
            }
            glFramePort = null
            true
        }
    }

    internal fun registerListenerRemovalPort(operationIdentity: Long): AndroidListenerRemovalPort? {
        require(operationIdentity > 0L)
        val provenance = provenance(operationIdentity, TargetPortKind.ListenerRemoval)
        val candidate = AndroidListenerRemovalPortImpl(this, operationIdentity, provenance)
        return targetGate.withLock {
            when (target.claimListenerRemovalIdentityLocked(operationIdentity)) {
                TargetRegistrationClaim.New ->
                    candidate.also { listenerRemovalPort = it }

                TargetRegistrationClaim.Existing ->
                    listenerRemovalPort?.takeIf { it.operationIdentity == operationIdentity }

                TargetRegistrationClaim.Denied -> null
            }
        }
    }

    internal fun registerSetSurfaceDetachPort(operationIdentity: Long): AndroidDetachPort? =
        registerDetachPort(operationIdentity, TargetPortKind.VirtualDisplayDetach, TargetProducerDetachKind.VirtualDisplayDetach)

    internal fun registerVirtualDisplayReleasePort(operationIdentity: Long): AndroidDetachPort? =
        registerDetachPort(operationIdentity, TargetPortKind.VirtualDisplayRelease, TargetProducerDetachKind.VirtualDisplayRelease)

    private fun registerDetachPort(operationIdentity: Long, portKind: TargetPortKind, detachKind: TargetProducerDetachKind): AndroidDetachPort? {
        require(operationIdentity > 0L)
        val provenance = provenance(operationIdentity, portKind)
        val receipt = targetProducerDetachReceipt(targetOwner, constructionProof, generation, operationIdentity, detachKind, provenance)
        val candidate = AndroidDetachPortImpl(this, operationIdentity, detachKind, provenance, receipt)
        return targetGate.withLock {
            when (target.claimDetachIdentityLocked(operationIdentity, detachKind)) {
                TargetRegistrationClaim.New ->
                    candidate.also { producerDetachPort = it }

                TargetRegistrationClaim.Existing ->
                    producerDetachPort?.takeIf { it.operationIdentity == operationIdentity }

                TargetRegistrationClaim.Denied -> null
            }
        }
    }

    internal fun armListenerSentinelAfterRemovalReturn(operationIdentity: Long): Runnable? = targetGate.withLock {
        target.armListenerSentinelLocked(operationIdentity)
    }

    internal fun recordListenerRemovalReturn(port: AndroidListenerRemovalPort): Boolean = targetGate.withLock {
        target.recordListenerRemovalReturnLocked(port === listenerRemovalPort, port.operationIdentity)
    }

    internal fun applyListenerRemovalSettlement(port: AndroidListenerRemovalPort, operation: OperationOccurrence<*>): Boolean {
        val mechanicallyReturned = operation.settlementGate.withLock {
            operation.identity == port.operationIdentity && operation.returnCell.disposition == OperationReturnDisposition.Normal
        }
        if (!mechanicallyReturned) return false
        return targetGate.withLock {
            target.applyListenerRemovalSettlementLocked(port === listenerRemovalPort)
        }
    }

    internal fun applyProducerDetachReceipt(receipt: TargetProducerDetachReceipt): Boolean = targetGate.withLock {
        val port = producerDetachPort ?: return@withLock false
        target.applyProducerDetachReceiptLocked(
            receipt,
            exactPort = true,
            portOperationIdentity = port.operationIdentity,
            portDetachKind = port.detachKind,
            portProvenance = port.provenance,
        )
    }

    internal fun detachedSurfaceReleasePort(surfaceRequired: Boolean): TargetRetirement.SurfaceReleasePort? = targetGate.withLock {
        if (!surfaceRequired || surfaceReleasePort != null) return@withLock surfaceReleasePort
        precreatedSurfaceReleasePort
    }

    internal fun commitSurfaceReleasePort(port: TargetRetirement.SurfaceReleasePort, surfaceRequired: Boolean): Boolean = targetGate.withLock {
        if (port !== precreatedSurfaceReleasePort || !surfaceRequired || surfaceReleasePort != null) {
            return@withLock false
        }
        surfaceReleasePort = port
        true
    }

    internal fun isExactSurfaceReleasePort(port: TargetRetirement.SurfaceReleasePort): Boolean =
        targetGate.withLock { port === surfaceReleasePort }

    internal fun releaseEnteredSurface(port: TargetRetirement.SurfaceReleasePort): Boolean =
        port.releaseSurface()

    private fun withSurfaceLease(port: AndroidSurfacePortImpl, block: (Surface) -> Unit): Boolean = withLease(
        port.lease,
        ordinaryOutOfMemory = port.operationKind == TargetProducerOperationKind.VirtualDisplayCreation,
        claim = { port.claimRawHandleLocked() },
        admission = {
            port === producerPort && resources.installedResources && !target.generationFenced && target.producerState == TargetProducerState.AwaitingEvidence
        },
        value = { resources.surface },
        block = block,
    )

    private fun withListenerInstallationLease(
        port: AndroidListenerInstallationPortImpl,
        block: (SurfaceTexture, SurfaceTexture.OnFrameAvailableListener) -> Unit,
    ): Boolean {
        var surfaceTexture: SurfaceTexture? = null
        val admitted = targetGate.withLock {
            if (!port.claimLocked() || port !== listenerInstallationPort || !resources.installedResources ||
                target.generationFenced || target.listenerInstalled || leaseCount == Int.MAX_VALUE
            ) {
                return@withLock false
            }
            surfaceTexture = resources.surfaceTexture ?: return@withLock false
            leaseCount += 1
            true
        }
        if (!admitted) return false
        try {
            block(checkNotNull(surfaceTexture), target.frameAvailableListener)
        } catch (error: Error) {
            check(port.lease.retainAfterFatal())
            throw error
        } catch (exception: Exception) {
            check(port.lease.release())
            throw exception
        }
        check(port.lease.release())
        return true
    }

    private fun withListenerRemovalLease(port: AndroidListenerRemovalPortImpl, block: (SurfaceTexture) -> Unit): Boolean = withLease(
        port.lease,
        claim = { port.claimLocked() },
        admission = { port === listenerRemovalPort && resources.installedResources && target.generationFenced && !target.listenerRemoved },
        value = { resources.surfaceTexture },
        block = block,
    )

    internal fun releaseSurface(port: TargetRetirement.SurfaceReleasePort): Boolean {
        var surface: Surface? = null
        val admitted = targetGate.withLock {
            if (!port.claimConsumedLocked(this) || port !== surfaceReleasePort || leaseCount == Int.MAX_VALUE) {
                return@withLock false
            }
            surface = resources.surface ?: return@withLock false
            leaseCount += 1
            true
        }
        if (!admitted) return false
        try {
            checkNotNull(surface).release()
        } catch (error: Error) {
            check(port.retainLeaseAfterFatal())
            throw error
        } catch (exception: Exception) {
            check(port.releaseLease())
            throw exception
        }
        check(port.releaseLease())
        return true
    }

    internal fun withTargetScopeDestructionLease(command: TargetRetirement.TargetScopeDestructionCommand, block: (SurfaceTexture?, Int) -> Unit): Boolean {
        var surfaceTexture: SurfaceTexture? = null
        var oesTextureName = 0
        val admitted = targetGate.withLock {
            if (!command.claimConsumedLocked(this) || command !== targetScopeDestructionCommand || leaseCount == Int.MAX_VALUE) {
                return@withLock false
            }
            surfaceTexture = resources.surfaceTexture
            oesTextureName = resources.oesTextureName
            leaseCount += 1
            true
        }
        if (!admitted) return false
        try {
            block(surfaceTexture, oesTextureName)
        } catch (error: Error) {
            check(command.retainLeaseAfterFatal())
            throw error
        } catch (exception: Exception) {
            check(command.releaseLease())
            throw exception
        }
        check(command.releaseLease())
        return true
    }

    private fun withGlFrameLease(port: GlFramePortImpl, block: (SurfaceTexture, Int) -> Unit): Boolean {
        var surfaceTexture: SurfaceTexture? = null
        var oesTextureName = 0
        val admitted = targetGate.withLock {
            if (!port.claimLocked() || port !== glFramePort || !resources.installedResources || target.generationFenced || leaseCount == Int.MAX_VALUE) {
                return@withLock false
            }
            surfaceTexture = resources.surfaceTexture ?: return@withLock false
            oesTextureName = resources.oesTextureName
            if (oesTextureName == 0) return@withLock false
            leaseCount += 1
            true
        }
        if (!admitted) return false
        try {
            block(checkNotNull(surfaceTexture), oesTextureName)
        } catch (error: Error) {
            check(port.lease.retainAfterFatal())
            throw error
        } catch (exception: Exception) {
            check(port.lease.release())
            throw exception
        }
        check(port.lease.release())
        return true
    }

    private inline fun <T> withLease(
        lease: TargetLease,
        ordinaryOutOfMemory: Boolean = false,
        claim: () -> Boolean,
        admission: () -> Boolean,
        value: () -> T?,
        block: (T) -> Unit,
    ): Boolean {
        var ownedValue: T? = null
        val admitted = targetGate.withLock {
            if (!claim() || !admission() || leaseCount == Int.MAX_VALUE) return@withLock false
            ownedValue = value() ?: return@withLock false
            leaseCount += 1
            true
        }
        if (!admitted) return false
        try {
            block(checkNotNull(ownedValue))
        } catch (error: Error) {
            check(
                if (ordinaryOutOfMemory && error is OutOfMemoryError) {
                    lease.release()
                } else {
                    lease.retainAfterFatal()
                },
            )
            throw error
        } catch (exception: Exception) {
            check(lease.release())
            throw exception
        }
        check(lease.release())
        return true
    }

    private fun provenance(operationIdentity: Long, portKind: TargetPortKind): TargetOperationProvenance =
        targetOperationProvenance(targetOwner, constructionProof, target, operationIdentity, portKind)

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            target: CurrentTarget,
            targetGate: ReentrantLock,
            generation: Long,
            listenerInstallationOperationIdentity: Long,
            surfaceReleaseOperationIdentity: Long,
            targetDestructionOperationIdentity: Long,
            settlementSignal: SettlementSignal,
            resources: ResourceAccess,
        ): TargetPorts {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetPorts(
                targetOwner,
                constructionProof,
                target,
                targetGate,
                generation,
                listenerInstallationOperationIdentity,
                surfaceReleaseOperationIdentity,
                targetDestructionOperationIdentity,
                settlementSignal,
                resources,
            )
        }
    }
}
