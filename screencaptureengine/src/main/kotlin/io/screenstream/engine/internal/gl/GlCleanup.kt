package io.screenstream.engine.internal.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.DeadlineDisposition
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.isHandedOff
import io.screenstream.engine.internal.target.TargetPortUseOutcome
import io.screenstream.engine.internal.target.TargetIdentity
import io.screenstream.engine.internal.target.TargetRetirement
import io.screenstream.engine.internal.target.TargetSurfaceReleaseReceipt
import kotlin.concurrent.withLock

internal class GlOwnerBag internal constructor(
    internal var returnedOwner: OperationReturnedOwner? = null,
) : OperationOwnerBag

internal class GlClaimedOperationFacts private constructor(
    internal val operationIdentity: Long,
    internal val operationKind: GlOperationKind,
    private val frameTargetIdentity: TargetIdentity?,
    private val frameRenderTargetOwner: GlPipelineOwner.GlRenderTargetOwner?,
) {
    internal lateinit var result: GlOperationResult
        private set
    internal var receipt: GlOperationSuccessReceipt? = null
        private set
    internal var returnedOwner: OperationReturnedOwner? = null
        private set
    internal var throwable: Throwable? = null
        private set
    internal var preprobeErrorCode: Int = GLES20.GL_NO_ERROR
        private set
    internal var preprobeErrorCodePresent: Boolean = false
        private set
    internal var postprobeErrorCode: Int = GLES20.GL_NO_ERROR
        private set
    internal var postprobeErrorCodePresent: Boolean = false
        private set
    internal var contextIntegrity: ContextIntegrity = ContextIntegrity.Unknown
        private set
    internal var colorActionFacts: GlColorActionFacts? = null
        private set
    internal var stateProbeFacts: GlProbeFacts? = null
        private set
    internal var drawReadProbeFacts: GlProbeFacts? = null
        private set
    internal var readbackTimingFact: GlReadbackTimingFact? = null
        private set
    internal var timely: Boolean = false
        private set
    private var frozen: Boolean = false

    init {
        require(operationIdentity > 0L)
        check(
            (operationKind == GlOperationKind.Frame) ==
                    (frameTargetIdentity != null && frameRenderTargetOwner != null)
        )
    }

    private fun createReadbackTimingFact(durationNanos: Long): GlReadbackTimingFact? {
        if (frozen || operationKind != GlOperationKind.Frame || durationNanos < 0L) return null
        return GlReadbackTimingFact.create(
            operationIdentity = operationIdentity,
            targetIdentity = frameTargetIdentity ?: return null,
            renderTargetOwner = frameRenderTargetOwner ?: return null,
            durationNanos = durationNanos,
        )
    }

    private fun freeze(
        evidence: GlOperationEvidence,
        normal: Boolean,
        timely: Boolean,
        colorActionFacts: GlColorActionFacts?,
        stateProbeFacts: GlProbeFacts?,
        drawReadProbeFacts: GlProbeFacts?,
        readbackTimingFact: GlReadbackTimingFact?,
    ): GlClaimedOperationFacts? {
        if (frozen) return null
        val exactResult = evidence.result ?: return null
        val successful = timely && normal && exactResult == GlOperationResult.Success && evidence.contextIntegrity == ContextIntegrity.Intact
        val exactFrameTargetIdentity = frameTargetIdentity
        val exactFrameRenderTargetOwner = frameRenderTargetOwner
        val acceptedReadbackTimingFact = if (successful && operationKind == GlOperationKind.Frame &&
            exactFrameTargetIdentity != null && exactFrameRenderTargetOwner != null &&
            readbackTimingFact?.matches(
                operationIdentity = operationIdentity,
                targetIdentity = exactFrameTargetIdentity,
                renderTargetOwner = exactFrameRenderTargetOwner,
            ) == true
        ) {
            readbackTimingFact
        } else {
            null
        }
        result = exactResult
        receipt = if (successful) evidence.receipt else null
        returnedOwner = evidence.returnedOwner
        throwable = evidence.throwable
        preprobeErrorCode = evidence.preprobeErrorCode
        preprobeErrorCodePresent = evidence.preprobeErrorCodePresent
        postprobeErrorCode = evidence.postprobeErrorCode
        postprobeErrorCodePresent = evidence.postprobeErrorCodePresent
        contextIntegrity = evidence.contextIntegrity
        this.colorActionFacts = colorActionFacts
        this.stateProbeFacts = stateProbeFacts
        this.drawReadProbeFacts = drawReadProbeFacts
        this.readbackTimingFact = acceptedReadbackTimingFact
        this.timely = timely
        frozen = true
        return this
    }

    internal companion object {
        internal fun precreate(evidence: GlOperationEvidence): GlClaimedOperationFacts {
            check(evidence.operationKind != GlOperationKind.Frame)
            return GlClaimedOperationFacts(
                operationIdentity = evidence.operationIdentity,
                operationKind = evidence.operationKind,
                frameTargetIdentity = null,
                frameRenderTargetOwner = null,
            )
        }

        internal fun precreateFrame(
            evidence: GlOperationEvidence,
            targetIdentity: TargetIdentity,
            renderTargetOwner: GlPipelineOwner.GlRenderTargetOwner,
        ): GlClaimedOperationFacts {
            check(evidence.operationKind == GlOperationKind.Frame)
            return GlClaimedOperationFacts(
                operationIdentity = evidence.operationIdentity,
                operationKind = evidence.operationKind,
                frameTargetIdentity = targetIdentity,
                frameRenderTargetOwner = renderTargetOwner,
            )
        }

        internal fun createReadbackTimingFact(
            facts: GlClaimedOperationFacts,
            durationNanos: Long,
        ): GlReadbackTimingFact? = facts.createReadbackTimingFact(durationNanos)

        internal fun freeze(
            facts: GlClaimedOperationFacts,
            evidence: GlOperationEvidence,
            normal: Boolean,
            timely: Boolean,
            colorActionFacts: GlColorActionFacts? = null,
            stateProbeFacts: GlProbeFacts? = null,
            drawReadProbeFacts: GlProbeFacts? = null,
            readbackTimingFact: GlReadbackTimingFact? = null,
        ): GlClaimedOperationFacts? =
            facts.freeze(
                evidence,
                normal,
                timely,
                colorActionFacts,
                stateProbeFacts,
                drawReadProbeFacts,
                readbackTimingFact,
            )
    }
}

internal class GlClaimedDestructionFacts private constructor(
    internal val operationIdentity: Long,
    internal val destructionKind: GlDestructionKind,
) {
    internal lateinit var result: GlOperationResult
        private set
    internal var receipt: GlDestructionSuccessReceipt? = null
        private set
    internal var throwable: Throwable? = null
        private set
    internal var returnThrowable: Throwable? = null
        private set
    internal var normalReturn: Boolean = false
        private set
    internal var preprobeErrorCode: Int = GLES20.GL_NO_ERROR
        private set
    internal var preprobeErrorCodePresent: Boolean = false
        private set
    internal var postprobeErrorCode: Int = GLES20.GL_NO_ERROR
        private set
    internal var postprobeErrorCodePresent: Boolean = false
        private set
    internal var contextIntegrity: ContextIntegrity = ContextIntegrity.Unknown
        private set
    internal var returnOutcome: GlPhysicalResourceReturnOutcome? = null
        private set
    internal var timely: Boolean = false
        private set
    private var frozen: Boolean = false

    private fun freeze(
        evidence: GlDestructionEvidence,
        normal: Boolean,
        timely: Boolean,
        returnThrowable: Throwable?,
    ): GlClaimedDestructionFacts? {
        if (frozen) return null
        val exactResult = evidence.result ?: return null
        result = exactResult
        val durableNamespaceRetirement = evidence.durablyRetiredContextNamespaceOrNull()
        val exactReceipt = durableNamespaceRetirement?.receipt ?: if (normal && exactResult == GlOperationResult.Success &&
            (evidence.contextIntegrity == ContextIntegrity.Intact || evidence.destructionKind == GlDestructionKind.ContextNamespace)
        ) {
            evidence.physicalRetirementReceiptOrNull()
        } else {
            null
        }
        receipt = exactReceipt
        val namespace = evidence.contextNamespace
        returnOutcome = when {
            durableNamespaceRetirement != null -> durableNamespaceRetirement

            normal && exactResult == GlOperationResult.StructurallyNoContext &&
                    evidence.destructionKind == GlDestructionKind.ContextNamespace && exactReceipt == null ->
                evidence.structurallyNoContextOutcome()

            evidence.destructionKind == GlDestructionKind.ContextNamespace && namespace == null -> null

            normal && exactReceipt != null && evidence.contextIntegrity == ContextIntegrity.Intact ->
                GlPhysicalResourceReturnOutcome.HealthyContextDeleted(exactReceipt)

            evidence.destructionKind == GlDestructionKind.Program && namespace != null ->
                GlPhysicalResourceReturnOutcome.ContextNamespaceRequired(namespace)

            else -> GlPhysicalResourceReturnOutcome.ReturnedFailure(
                destructionKind = evidence.destructionKind,
                integrity = evidence.contextIntegrity,
                namespace = namespace,
            )
        }
        throwable = evidence.throwable
        this.returnThrowable = returnThrowable
        normalReturn = normal
        preprobeErrorCode = evidence.preprobeErrorCode
        preprobeErrorCodePresent = evidence.preprobeErrorCodePresent
        postprobeErrorCode = evidence.postprobeErrorCode
        postprobeErrorCodePresent = evidence.postprobeErrorCodePresent
        contextIntegrity = evidence.contextIntegrity
        this.timely = timely
        frozen = true
        return this
    }

    internal companion object {
        internal fun precreate(evidence: GlDestructionEvidence): GlClaimedDestructionFacts =
            GlClaimedDestructionFacts(evidence.operationIdentity, evidence.destructionKind)

        internal fun freeze(
            facts: GlClaimedDestructionFacts,
            evidence: GlDestructionEvidence,
            normal: Boolean,
            timely: Boolean,
            returnThrowable: Throwable?,
        ): GlClaimedDestructionFacts? = facts.freeze(evidence, normal, timely, returnThrowable)
    }
}

internal class TargetSurfaceReleaseClaim private constructor(
    internal val receipt: TargetSurfaceReleaseReceipt,
) {
    internal var timely: Boolean = false
        private set
    private var frozen: Boolean = false

    private fun freeze(timely: Boolean): TargetSurfaceReleaseClaim? {
        if (frozen) return null
        this.timely = timely
        frozen = true
        return this
    }

    internal companion object {
        internal fun precreate(receipt: TargetSurfaceReleaseReceipt): TargetSurfaceReleaseClaim = TargetSurfaceReleaseClaim(receipt)

        internal fun freeze(claim: TargetSurfaceReleaseClaim, timely: Boolean): TargetSurfaceReleaseClaim? = claim.freeze(timely)
    }
}

internal enum class GlDestructionAction {
    RenderTarget,
    Program,
    ProgramNamespace,
    Session,
    ContextNamespace,
}

private class GlSessionTeardownOwner : GlTeardownOwner

internal class GlDestructionHandle internal constructor(
    private val owner: GlPipelineOwner,
    identity: GlFiniteOperationIdentity,
    private val action: GlDestructionAction,
    renderTarget: GlPipelineOwner.GlRenderTargetOwner?,
    private val renderState: GlRenderTargetState?,
    internal var installedRenderTargetDestruction: Boolean,
    private val contextNamespace: ContextNamespace? = null,
    private val programFallbackNamespace: ContextNamespace? = null,
    terminalCleanupOrigin: GlTerminalCleanupOrigin? = null,
) : GlPipelineOwner.DestructionCommand {
    private val kind: GlDestructionKind = when (action) {
        GlDestructionAction.RenderTarget -> GlDestructionKind.RenderTarget
        GlDestructionAction.Program -> GlDestructionKind.Program
        GlDestructionAction.ProgramNamespace -> GlDestructionKind.Program
        GlDestructionAction.Session -> GlDestructionKind.Session
        GlDestructionAction.ContextNamespace -> GlDestructionKind.ContextNamespace
    }
    private val precreatedRetirementReceipt: GlDestructionSuccessReceipt =
        GlDestructionSuccessReceipt(identity.operationIdentity, kind)
    private val precreatedContextNamespaceRetired: GlPhysicalResourceReturnOutcome.ContextNamespaceRetired? =
        if (action == GlDestructionAction.ContextNamespace) {
            GlPhysicalResourceReturnOutcome.ContextNamespaceRetired(
                namespace = checkNotNull(contextNamespace),
                receipt = precreatedRetirementReceipt,
            )
        } else {
            null
        }
    private val evidence: GlDestructionEvidence = GlDestructionEvidence(
        operationIdentity = identity.operationIdentity,
        destructionKind = kind,
        precreatedPhysicalRetirementReceipt = precreatedRetirementReceipt,
        precreatedContextNamespaceRetired = precreatedContextNamespaceRetired,
    )
    override val occurrence: OperationOccurrence<GlDestructionEvidence> =
        owner.destructionOccurrence(
            identity = identity,
            evidence = evidence,
            ownerBag = GlOwnerBag(renderTarget ?: owner),
        )
    private val claimedFacts: GlClaimedDestructionFacts = GlClaimedDestructionFacts.precreate(evidence)
    private val endpointOperation = owner.endpointOperation(occurrence, Runnable { execute() })
    private val sessionTeardownOwner: GlTeardownOwner? =
        if (action == GlDestructionAction.Session || action == GlDestructionAction.ContextNamespace) {
            GlSessionTeardownOwner()
        } else {
            null
        }
    private val terminalCleanupWork: GlTerminalCleanupWork? = terminalCleanupOrigin?.let { origin ->
        GlTerminalCleanupWork.create(occurrence.identity, origin)
    }
    private val terminalCleanupConversion: GlTerminalCleanupConversion? =
        terminalCleanupWork?.let { work -> occurrence.toGlTerminalCleanup(work) }
    private val terminalResidue: GlPhysicalRetirementResidue? = terminalCleanupWork?.let { work ->
        GlPhysicalRetirementResidue(owner, work, kind, contextNamespace)
    }
    private val cleanupUnenteredProgress: GlPhysicalRetirementProgress.CleanupUnentered? =
        terminalCleanupWork?.let { work -> GlPhysicalRetirementProgress.CleanupUnentered(work) }
    private val enteredNonreturnProgress: GlPhysicalRetirementProgress.CleanupEnteredNonreturnResidue? =
        terminalResidue?.let { residue -> GlPhysicalRetirementProgress.CleanupEnteredNonreturnResidue(residue) }
    private val quarantineProgress: GlPhysicalRetirementProgress.QuarantineRequired? =
        terminalResidue?.let { residue -> GlPhysicalRetirementProgress.QuarantineRequired(residue) }
    private val returnedSuffixFailureProgress: GlPhysicalRetirementProgress.ReturnedSuffixFailure? =
        terminalResidue?.let { residue -> GlPhysicalRetirementProgress.ReturnedSuffixFailure(residue) }
    private var namespaceConsumed: Boolean = false
    private var namespaceSuffixSucceeded: Boolean = false
    private var namespaceSuffixReturned: Boolean = false
    private var contextNamespaceEndpointReleased: Boolean = false
    private var contextNamespaceReturnFailed: Boolean = false
    private var contextNamespaceRetirementClaimed: Boolean = false

    init {
        if (terminalCleanupOrigin != null) {
            check(terminalCleanupConversion is GlTerminalCleanupConversion.Ready)
        }
        check((action == GlDestructionAction.ProgramNamespace || action == GlDestructionAction.ContextNamespace) ==
                (contextNamespace != null))
        check((action == GlDestructionAction.Program || action == GlDestructionAction.ProgramNamespace) ==
                (programFallbackNamespace != null))
    }

    private fun execute() {
        try {
            val success = when (action) {
                GlDestructionAction.RenderTarget -> owner.destroyRenderTarget(checkNotNull(renderState), installedRenderTargetDestruction, evidence)
                GlDestructionAction.Program -> owner.destroyProgram(evidence).also { destroyed ->
                    if (!destroyed && owner.contextIntegrity != ContextIntegrity.Intact) {
                        val namespace = checkNotNull(programFallbackNamespace)
                        if (owner.activateAndBindProgramContextNamespace(namespace)) {
                            evidence.contextNamespace = namespace
                        }
                    }
                }
                GlDestructionAction.ProgramNamespace -> {
                    val namespace = checkNotNull(contextNamespace)
                    check(namespace === programFallbackNamespace)
                    if (owner.activateAndBindProgramContextNamespace(namespace)) {
                        evidence.contextNamespace = namespace
                    }
                    evidence.contextIntegrity = namespace.integrityAtTransfer
                    evidence.result = GlOperationResult.InternalFailure
                    false
                }
                GlDestructionAction.Session -> owner.destroyHealthySession(checkNotNull(sessionTeardownOwner), evidence)
                GlDestructionAction.ContextNamespace -> executeContextNamespace()
            }
            owner.checkFatalFence()
            if (success) evidence.result = GlOperationResult.Success
            owner.checkFatalFence()
            owner.closeNormalResult(evidence)
            occurrence.publishNormalReturn()
            if (action == GlDestructionAction.ContextNamespace) owner.signalSettlement()
        } catch (exception: Exception) {
            owner.checkFatalFence()
            owner.markContextUnknown()
            evidence.throwable = exception
            evidence.result = GlOperationResult.InternalFailure
            evidence.contextIntegrity = ContextIntegrity.Unknown
            if ((action == GlDestructionAction.Program || action == GlDestructionAction.ProgramNamespace) &&
                evidence.contextNamespace == null
            ) {
                val namespace = checkNotNull(programFallbackNamespace)
                if (owner.activateAndBindProgramContextNamespace(namespace)) {
                    evidence.contextNamespace = namespace
                }
            }
            occurrence.publishThrownReturn(exception)
            if (action == GlDestructionAction.ContextNamespace) owner.signalSettlement()
        }
        if (action == GlDestructionAction.ContextNamespace) progressContextNamespaceSuffix()
    }

    private fun executeContextNamespace(): Boolean {
        val namespace = checkNotNull(contextNamespace)
        evidence.bindPhysicalRetirementCandidate(namespace, owner.context)
        val retired = owner.unbindAndDestroyContext(checkNotNull(sessionTeardownOwner), evidence)
        if (retired) {
            evidence.contextIntegrity = namespace.integrityAtTransfer
        }
        return retired
    }

    private fun progressContextNamespaceSuffix() {
        val succeeded = if (owner.contextUnbound) {
            owner.destroyPbufferAndReleaseThread()
        } else {
            owner.recordUnreachableSuffixResidue()
            false
        }
        if (!succeeded) {
            owner.glGate.withLock { namespaceSuffixReturned = true }
            owner.signalSettlement()
            return
        }
        owner.glGate.withLock {
            namespaceSuffixReturned = true
            namespaceSuffixSucceeded = true
            if (namespaceConsumed && !contextNamespaceReturnFailed) {
                check(owner.releaseTeardownLocked(checkNotNull(sessionTeardownOwner)))
            }
        }
        owner.signalSettlement()
    }

    override fun submit(): Boolean =
        (terminalCleanupConversion == null || terminalCleanupConversion is GlTerminalCleanupConversion.Ready) &&
        owner.submit(
            endpointOperation,
            teardownOwner = sessionTeardownOwner,
        ).isHandedOff

    internal fun claimSessionTeardown(): Boolean =
        sessionTeardownOwner?.let(owner::claimTeardown) == true

    override val deadlineWakeLink: ControlWakeLink = checkNotNull(occurrence.controlWakeLink)

    override fun claim(): GlClaimedDestructionFacts? {
        val facts = owner.claimDestruction(occurrence, claimedFacts)
        val returnedPhysicalFailure = action == GlDestructionAction.ContextNamespace && facts != null &&
                facts.returnOutcome is GlPhysicalResourceReturnOutcome.ContextNamespaceRetired &&
                (!facts.normalReturn || facts.returnThrowable != null || facts.throwable != null ||
                        facts.result != GlOperationResult.Success)
        if (returnedPhysicalFailure) {
            owner.glGate.withLock { contextNamespaceReturnFailed = true }
        }
        val endpointReleased = !returnedPhysicalFailure && owner.releaseSettledOperation(endpointOperation)
        if (action == GlDestructionAction.ContextNamespace && endpointReleased) {
            owner.glGate.withLock { contextNamespaceEndpointReleased = true }
        }
        if (facts == null) return null
        if (action == GlDestructionAction.RenderTarget && installedRenderTargetDestruction &&
            facts.result == GlOperationResult.Success && facts.receipt === evidence.receipt
        ) {
            owner.checkFatalFence()
            val exactState = checkNotNull(renderState)
            val cleared = owner.glGate.withLock {
                owner.checkFatalFence()
                if (owner.installedRenderTarget !== exactState) {
                    false
                } else {
                    owner.installedRenderTarget = null
                    owner.commitRenderCurrentnessMutationLocked()
                    true
                }
            }
            if (!cleared) return null
        }
        if (action == GlDestructionAction.RenderTarget && !installedRenderTargetDestruction &&
            facts.result == GlOperationResult.Success && facts.receipt === evidence.receipt
        ) {
            checkNotNull(renderState)
        }
        if (action == GlDestructionAction.Program &&
            facts.result == GlOperationResult.Success && facts.receipt === evidence.receipt &&
            !owner.clearProgramDestruction(this)
        ) {
            return null
        }
        if (action == GlDestructionAction.Program && facts.contextIntegrity != ContextIntegrity.Intact) {
            val namespaceOutcome = facts.returnOutcome as? GlPhysicalResourceReturnOutcome.ContextNamespaceRequired
                ?: return null
            if (namespaceOutcome.namespace !== programFallbackNamespace) return null
        }
        if (action == GlDestructionAction.ProgramNamespace) {
            val namespaceOutcome = facts.returnOutcome as? GlPhysicalResourceReturnOutcome.ContextNamespaceRequired
                ?: return null
            if (namespaceOutcome.namespace !== contextNamespace) return null
        }
        if (action == GlDestructionAction.ContextNamespace) {
            val outcome = facts.returnOutcome as? GlPhysicalResourceReturnOutcome.ContextNamespaceRetired
            if (outcome != null) {
                if (facts.receipt !== outcome.receipt || outcome.namespace !== contextNamespace) return null
                owner.glGate.withLock { contextNamespaceRetirementClaimed = true }
                if (!recordContextNamespaceConsumed()) return null
            }
            if (outcome == null && facts.receipt != null) return null
        }
        if (action == GlDestructionAction.Session &&
            facts.result == GlOperationResult.Success && facts.receipt === evidence.receipt
        ) {
            check(owner.releaseTeardown(checkNotNull(sessionTeardownOwner)))
        }
        return facts
    }

    private fun recordContextNamespaceConsumed(): Boolean = owner.glGate.withLock {
        check(!namespaceConsumed)
        if (!owner.consumePartialContextNamespaceLocked(
                expectedNamespace = checkNotNull(contextNamespace),
                authorizedPhysicalRetirementEvidence = evidence,
            )
        ) {
            return@withLock false
        }
        namespaceConsumed = true
        if (namespaceSuffixSucceeded && !contextNamespaceReturnFailed) {
            check(owner.releaseTeardownLocked(checkNotNull(sessionTeardownOwner)))
        }
        true
    }.also { consumed ->
        if (consumed) owner.signalSettlement()
    }

    internal fun retryClaimedContextNamespaceReduction(): Boolean {
        if (action != GlDestructionAction.ContextNamespace) return true
        if (owner.glGate.withLock { !contextNamespaceRetirementClaimed || namespaceConsumed }) return true
        return recordContextNamespaceConsumed()
    }

    internal fun releaseContextNamespaceEndpointAfterSuffix(): Boolean {
        if (action != GlDestructionAction.ContextNamespace ||
            !owner.glGate.withLock {
                namespaceConsumed && namespaceSuffixSucceeded && !contextNamespaceReturnFailed
            }
        ) {
            return false
        }
        if (owner.glGate.withLock { contextNamespaceEndpointReleased }) return true
        if (!owner.releaseSettledOperation(endpointOperation)) return false
        owner.glGate.withLock { contextNamespaceEndpointReleased = true }
        return true
    }

    internal fun unresolvedContextNamespaceProgress(): GlPhysicalRetirementProgress? {
        if (action != GlDestructionAction.ContextNamespace) return null
        val entryProgress = occurrence.settlementGate.withLock {
            if (occurrence.domain != OperationDomain.Cleanup ||
                occurrence.returnCell.disposition != OperationReturnDisposition.Empty
            ) {
                return@withLock null
            }
            when (occurrence.entryDisposition) {
                OperationEntryDisposition.Unentered -> cleanupUnenteredProgress
                OperationEntryDisposition.Entered -> enteredNonreturnProgress
                OperationEntryDisposition.Cancelled -> null
            }
        }
        if (entryProgress != null) return entryProgress
        return owner.glGate.withLock {
            when {
                contextNamespaceReturnFailed -> returnedSuffixFailureProgress
                !namespaceSuffixReturned -> quarantineProgress
                !namespaceSuffixSucceeded -> returnedSuffixFailureProgress
                !namespaceConsumed -> quarantineProgress
                else -> null
            }
        }
    }
}

internal class GlTargetScopeDestructionHandle internal constructor(
    private val owner: GlPipelineOwner,
    private val graph: TargetRetirement.TargetScopeDestructionGraph,
) : GlPipelineOwner.TargetScopeDestructionCommand, GlTeardownOwner {
    private val evidence: GlDestructionEvidence = graph.targetEvidence
    override val occurrence: OperationOccurrence<GlDestructionEvidence> = graph.targetOccurrence
    private val namespaceEvidence: GlDestructionEvidence = graph.namespaceEvidence
    override val namespaceOccurrence: OperationOccurrence<GlDestructionEvidence> = graph.namespaceOccurrence
    private val claimedFacts: GlClaimedDestructionFacts = GlClaimedDestructionFacts.precreate(evidence)
    private val namespaceClaimedFacts: GlClaimedDestructionFacts = GlClaimedDestructionFacts.precreate(namespaceEvidence)
    private val targetEndpointOperation = owner.endpointOperation(occurrence, Runnable { executeTargetScope() })
    private val namespaceEndpointOperation = owner.endpointOperation(namespaceOccurrence, Runnable { executeNamespace() })
    private var namespaceAdmitted: Boolean = false
    private var namespaceConsumed: Boolean = false
    private var namespaceSuffixSucceeded: Boolean = false
    private var namespaceSuffixReturned: Boolean = false
    private var namespaceEndpointReleased: Boolean = false

    private fun bindTransferredNamespace() {
        check(owner.bindContextNamespace(graph))
    }

    private fun executeTargetScope() {
        var oesPhaseEntered = false
        var remainingOesName = 0
        try {
            owner.checkFatalFence()
            val released = graph.withHandles { surfaceTexture, oesTextureName ->
                if (surfaceTexture != null) {
                    owner.outward { surfaceTexture.release() }
                    owner.checkFatalFence()
                    graph.recordSurfaceTextureRelease()
                }
                if (oesTextureName != 0) {
                    remainingOesName = oesTextureName
                    if (owner.contextIntegrity == ContextIntegrity.Intact) {
                        oesPhaseEntered = true
                        val deleted = owner.glesGroup(evidence) {
                            owner.outward { GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0) }
                            owner.generatedNames[0] = oesTextureName
                            owner.outward { GLES20.glDeleteTextures(1, owner.generatedNames, 0) }
                            true
                        }
                        if (deleted) {
                            owner.checkFatalFence()
                            graph.recordTargetReceipt()
                        } else if (owner.contextIntegrity != ContextIntegrity.Intact) {
                            owner.checkFatalFence()
                            graph.recordNamespaceTransfer()
                            owner.checkFatalFence()
                            bindTransferredNamespace()
                        }
                    } else {
                        owner.checkFatalFence()
                        graph.recordNamespaceTransfer()
                        owner.checkFatalFence()
                        bindTransferredNamespace()
                    }
                }
            }
            if (released != TargetPortUseOutcome.BodyReturned) {
                owner.checkFatalFence()
                evidence.result = GlOperationResult.InternalFailure
                evidence.contextIntegrity = owner.contextIntegrity
            } else if (graph.isTargetReceiptSelected()) {
                evidence.result = GlOperationResult.Success
                evidence.contextIntegrity = ContextIntegrity.Intact
            } else if (graph.isNamespaceTransferSelected()) {
                evidence.result =
                    if (owner.contextIntegrity == ContextIntegrity.PoisonedByOutOfMemory) {
                        GlOperationResult.ResourceExhausted
                    } else {
                        GlOperationResult.InternalFailure
                    }
                evidence.contextIntegrity = owner.contextIntegrity
            } else {
                evidence.result = GlOperationResult.Success
                evidence.contextIntegrity = ContextIntegrity.Intact
                graph.recordTargetReceipt()
            }
            owner.checkFatalFence()
            graph.freeze()
            owner.checkFatalFence()
            owner.closeNormalResult(evidence)
            occurrence.publishNormalReturn()
        } catch (exception: Exception) {
            owner.checkFatalFence()
            if (oesPhaseEntered && remainingOesName != 0 && owner.contextIntegrity != ContextIntegrity.Intact &&
                !graph.isTargetReceiptSelected() && !graph.isNamespaceTransferSelected()
            ) {
                graph.recordNamespaceTransfer()
                owner.checkFatalFence()
                bindTransferredNamespace()
            }
            evidence.throwable = exception
            evidence.result = GlOperationResult.InternalFailure
            evidence.contextIntegrity = owner.contextIntegrity
            owner.checkFatalFence()
            graph.freeze()
            owner.checkFatalFence()
            occurrence.publishThrownReturn(exception)
        }
    }

    private fun executeNamespace() {
        try {
            if (owner.unbindAndDestroyContext(this, namespaceEvidence)) {
                owner.checkFatalFence()
                namespaceEvidence.result = GlOperationResult.Success
                namespaceEvidence.contextIntegrity = owner.contextIntegrity
            }
            owner.checkFatalFence()
            owner.closeNormalResult(namespaceEvidence)
            namespaceOccurrence.publishNormalReturn()
            owner.checkFatalFence()
            owner.signalSettlement()
        } catch (exception: Exception) {
            owner.checkFatalFence()
            owner.markContextUnknown()
            namespaceEvidence.throwable = exception
            namespaceEvidence.result = GlOperationResult.InternalFailure
            namespaceEvidence.contextIntegrity = ContextIntegrity.Unknown
            namespaceOccurrence.publishThrownReturn(exception)
            owner.checkFatalFence()
            owner.signalSettlement()
        }
        val suffixSucceeded = if (owner.contextUnbound) {
            owner.destroyPbufferAndReleaseThread()
        } else {
            owner.recordUnreachableSuffixResidue()
            false
        }
        recordNamespaceSuffixReturn(suffixSucceeded)
    }

    override fun submit(): Boolean =
        owner.submit(targetEndpointOperation).isHandedOff

    override fun claim(): GlClaimedDestructionFacts? {
        val facts = owner.claimDestruction(occurrence, claimedFacts)
        owner.releaseSettledOperation(targetEndpointOperation)
        if (facts == null) return null
        owner.checkFatalFence()
        val namespaceSelected = graph.isNamespaceTransferSelected()
        if (namespaceSelected && (!owner.claimTeardown(this) || !owner.retainTargetScopeNamespaceHandle(this))) {
            if (owner.ownsTeardown(this)) owner.releaseTeardown(this)
            return null
        }
        if (!graph.applyTargetProjection()) {
            if (namespaceSelected) {
                owner.releaseTargetScopeNamespaceHandle(this)
                owner.releaseTeardown(this)
            }
            return null
        }
        namespaceAdmitted = graph.isNamespaceTransferSelected()
        if (!namespaceAdmitted) namespaceOccurrence.arbitrateTerminal(mandatoryCleanup = false)
        return facts
    }

    override fun submitNamespaceRetirement(): Boolean =
        namespaceAdmitted &&
                owner.submit(namespaceEndpointOperation, this).isHandedOff

    override fun claimNamespaceRetirement(): GlClaimedDestructionFacts? {
        val facts = owner.claimDestruction(namespaceOccurrence, namespaceClaimedFacts)
        if (owner.releaseSettledOperation(namespaceEndpointOperation)) {
            owner.glGate.withLock { namespaceEndpointReleased = true }
        }
        if (facts == null) return null
        owner.checkFatalFence()
        if (!namespaceAdmitted || !graph.applyNamespaceProjection()) return null
        if (!recordNamespaceConsumed()) return null
        return facts
    }

    private fun recordNamespaceConsumed(): Boolean {
        val consumed = owner.glGate.withLock {
            owner.checkFatalLocked()
            check(!namespaceConsumed)
            if (namespaceSuffixSucceeded && !owner.ownsTeardownLocked(this)) {
                return@withLock false
            }
            if (!owner.consumeContextNamespaceLocked(graph)) return@withLock false
            namespaceConsumed = true
            if (namespaceSuffixSucceeded) {
                check(owner.releaseTeardownLocked(this))
            }
            true
        }
        owner.signalSettlement()
        return consumed
    }

    private fun recordNamespaceSuffixReturn(succeeded: Boolean) {
        owner.glGate.withLock {
            owner.checkFatalLocked()
            check(!namespaceSuffixReturned)
            if (succeeded && namespaceConsumed && !owner.ownsTeardownLocked(this)) {
                return@withLock
            }
            namespaceSuffixReturned = true
            namespaceSuffixSucceeded = succeeded
            if (succeeded && namespaceConsumed) {
                check(owner.releaseTeardownLocked(this))
            }
        }
        owner.signalSettlement()
    }

    internal fun unresolvedNamespaceEndpoint(): Boolean = owner.glGate.withLock {
        !namespaceAdmitted || !namespaceConsumed || !namespaceSuffixReturned || !namespaceSuffixSucceeded
    }

    internal fun releaseNamespaceEndpointAfterSuffix(): Boolean {
        if (owner.glGate.withLock {
                !namespaceAdmitted || !namespaceConsumed || !namespaceSuffixSucceeded
            }
        ) {
            return false
        }
        if (owner.glGate.withLock { namespaceEndpointReleased }) return true
        if (!owner.releaseSettledOperation(namespaceEndpointOperation)) return false
        owner.glGate.withLock { namespaceEndpointReleased = true }
        return true
    }

    override val deadlineWakeLink: ControlWakeLink = checkNotNull(occurrence.controlWakeLink)

    override val namespaceDeadlineWakeLink: ControlWakeLink =
        checkNotNull(namespaceOccurrence.controlWakeLink)

    init {
        check(deadlineWakeLink !== namespaceDeadlineWakeLink)
    }
}

internal class GlClaimArbitrator internal constructor(
    private val owner: GlPipelineOwner,
) {
    internal fun claimOperation(
        occurrence: OperationOccurrence<GlOperationEvidence>,
        facts: GlClaimedOperationFacts,
        colorActionFacts: GlColorActionFacts? = null,
        stateProbeFacts: GlProbeFacts? = null,
        drawReadProbeFacts: GlProbeFacts? = null,
    ): GlClaimedOperationFacts? {
        owner.checkFatalFence()
        val readbackTimingFact = deriveReadbackTimingFactBeforeClaim(
            occurrence = occurrence,
            facts = facts,
            drawReadProbeFacts = drawReadProbeFacts,
        )
        owner.checkFatalFence()
        val arbitration = occurrence.arbitrate()
        owner.checkFatalFence()
        val claimed = when (arbitration) {
            OperationArbitration.TimelyNormal -> GlClaimedOperationFacts.freeze(
                facts,
                occurrence.returnCell.evidence,
                normal = true,
                timely = true,
                colorActionFacts,
                stateProbeFacts,
                drawReadProbeFacts,
                readbackTimingFact,
            )

            OperationArbitration.TimelyThrown -> GlClaimedOperationFacts.freeze(
                facts,
                occurrence.returnCell.evidence,
                normal = false,
                timely = true,
                colorActionFacts,
                stateProbeFacts,
                drawReadProbeFacts,
            )

            OperationArbitration.ExpiredNormal,
            OperationArbitration.CleanupNormal,
                -> GlClaimedOperationFacts.freeze(
                facts,
                occurrence.returnCell.evidence,
                normal = true,
                timely = false,
                colorActionFacts,
                stateProbeFacts,
                drawReadProbeFacts,
            )

            OperationArbitration.ExpiredThrown,
            OperationArbitration.CleanupThrown,
                -> GlClaimedOperationFacts.freeze(
                facts,
                occurrence.returnCell.evidence,
                normal = false,
                timely = false,
                colorActionFacts,
                stateProbeFacts,
                drawReadProbeFacts,
            )

            else -> null
        }
        owner.checkFatalFence()
        return claimed
    }

    private fun deriveReadbackTimingFactBeforeClaim(
        occurrence: OperationOccurrence<GlOperationEvidence>,
        facts: GlClaimedOperationFacts,
        drawReadProbeFacts: GlProbeFacts?,
    ): GlReadbackTimingFact? {
        var durationNanos = NO_DURATION
        occurrence.settlementGate.withLock {
            val evidence = occurrence.returnCell.evidence
            val deadline = occurrence.deadlineOccurrence ?: return@withLock
            val startNanos = deadline.startNanos
            val settlementNanos = occurrence.returnCell.settlementNanos
            if (occurrence.identity != facts.operationIdentity ||
                evidence.operationIdentity != facts.operationIdentity ||
                facts.operationKind != GlOperationKind.Frame ||
                evidence.operationKind != GlOperationKind.Frame ||
                occurrence.domain != OperationDomain.Active ||
                occurrence.disposition != OperationDisposition.Pending ||
                occurrence.returnCell.disposition != OperationReturnDisposition.Normal ||
                occurrence.returnCell.use != OperationReturnUse.Unclaimed ||
                evidence.result != GlOperationResult.Success ||
                evidence.contextIntegrity != ContextIntegrity.Intact ||
                deadline.disposition != DeadlineDisposition.Armed ||
                drawReadProbeFacts == null ||
                !drawReadProbeFacts.preprobePresent ||
                drawReadProbeFacts.preprobeErrorCode != GLES20.GL_NO_ERROR ||
                !drawReadProbeFacts.postprobePresent ||
                drawReadProbeFacts.postprobeErrorCode != GLES20.GL_NO_ERROR ||
                startNanos < 0L ||
                settlementNanos < startNanos ||
                settlementNanos >= deadline.deadlineNanos
            ) {
                return@withLock
            }
            durationNanos = settlementNanos - startNanos
        }
        if (durationNanos < 0L) return null
        // Allocate the immutable derived value only after settlement/gate release and before arbitrate() makes
        // the return-use claim irreversible.
        return GlClaimedOperationFacts.createReadbackTimingFact(facts, durationNanos)
    }

    internal fun claimDestruction(occurrence: OperationOccurrence<GlDestructionEvidence>, facts: GlClaimedDestructionFacts): GlClaimedDestructionFacts? {
        val evidence = occurrence.returnCell.evidence
        val durableNamespaceRetirement = evidence.durablyRetiredContextNamespaceOrNull() != null
        if (!durableNamespaceRetirement) owner.checkFatalFence()
        val arbitration = occurrence.arbitrate()
        if (!durableNamespaceRetirement) owner.checkFatalFence()
        val returnThrowable = occurrence.returnCell.throwable
        val claimed = when (arbitration) {
            OperationArbitration.TimelyNormal ->
                GlClaimedDestructionFacts.freeze(
                    facts, evidence, normal = true, timely = true, returnThrowable = returnThrowable,
                )

            OperationArbitration.TimelyThrown ->
                GlClaimedDestructionFacts.freeze(
                    facts, evidence, normal = false, timely = true, returnThrowable = returnThrowable,
                )

            OperationArbitration.ExpiredNormal,
            OperationArbitration.CleanupNormal,
                -> GlClaimedDestructionFacts.freeze(
                    facts, evidence, normal = true, timely = false, returnThrowable = returnThrowable,
                )

            OperationArbitration.ExpiredThrown,
            OperationArbitration.CleanupThrown,
                -> GlClaimedDestructionFacts.freeze(
                    facts, evidence, normal = false, timely = false, returnThrowable = returnThrowable,
                )

            else -> null
        }
        if (!durableNamespaceRetirement) owner.checkFatalFence()
        return claimed
    }

    private companion object {
        private const val NO_DURATION: Long = -1L
    }
}
