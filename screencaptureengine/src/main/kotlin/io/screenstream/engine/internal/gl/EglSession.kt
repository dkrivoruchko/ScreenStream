package io.screenstream.engine.internal.gl

import android.opengl.EGL14
import android.opengl.GLES20
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.isHandedOff
import kotlin.concurrent.withLock

internal enum class EglStepApplicability {
    Pending,
    Applicable,
    Inapplicable,
}

internal enum class EglStepOutcome {
    Pending,
    ReturnedTrue,
    ReturnedFalse,
    Thrown,
}

internal class EglStepCell internal constructor() {
    internal var applicability: EglStepApplicability = EglStepApplicability.Pending
    internal var outcome: EglStepOutcome = EglStepOutcome.Pending
    internal var errorCode: Int = 0
    internal var errorCodePresent: Boolean = false
    internal var throwable: Throwable? = null
    internal var receiptPresent: Boolean = false
    internal var residueRetained: Boolean = false
    private var frozen: Boolean = false

    internal fun freeze() {
        if (!frozen) frozen = true
    }

    internal fun requireMutable() {
        check(!frozen)
    }
}

internal class GlSessionConstructionHandle internal constructor(
    private val owner: GlPipelineOwner,
    identity: GlFiniteOperationIdentity,
    partialCleanupIdentity: GlFiniteOperationIdentity,
) : GlPipelineOwner.SessionConstructionCommand, GlTeardownOwner {
    private val evidence: GlOperationEvidence =
        GlOperationEvidence(
            identity.operationIdentity,
            GlOperationKind.SessionConstruction
        ).apply {
            returnedOwner = owner
        }
    private val ownerBag: GlOwnerBag = GlOwnerBag(owner)
    private val occurrence: OperationOccurrence<GlOperationEvidence> = owner.operationOccurrence(identity, evidence, ownerBag)
    private val claimedFacts: GlClaimedOperationFacts = GlClaimedOperationFacts.precreate(evidence)
    private val endpointOperation = owner.endpointOperation(occurrence, Runnable { execute() })
    private val partialCleanupNamespace: ContextNamespace = ContextNamespace.createForPartialStartup(
        owner = owner,
        triggerOperationIdentity = partialCleanupIdentity.operationIdentity,
    )
    private val partialCleanupRetirementReceipt: GlDestructionSuccessReceipt = GlDestructionSuccessReceipt(
        operationIdentity = partialCleanupIdentity.operationIdentity,
        destructionKind = GlDestructionKind.ContextNamespace,
    )
    private val partialCleanupRetiredOutcome: GlPhysicalResourceReturnOutcome.ContextNamespaceRetired =
        GlPhysicalResourceReturnOutcome.ContextNamespaceRetired(
            namespace = partialCleanupNamespace,
            receipt = partialCleanupRetirementReceipt,
        )
    private val partialCleanupEvidence: GlDestructionEvidence = GlDestructionEvidence(
        operationIdentity = partialCleanupIdentity.operationIdentity,
        destructionKind = GlDestructionKind.ContextNamespace,
        precreatedPhysicalRetirementReceipt = partialCleanupRetirementReceipt,
        physicalRetirementReceiptInitiallyAuthorized = false,
        precreatedContextNamespaceRetired = partialCleanupRetiredOutcome,
    )
    private val partialCleanupOwnerBag: GlOwnerBag = GlOwnerBag(owner)
    private val partialCleanupOccurrence: OperationOccurrence<GlDestructionEvidence> =
        owner.destructionOccurrence(partialCleanupIdentity, partialCleanupEvidence, partialCleanupOwnerBag)
    private val partialCleanupClaimedFacts: GlClaimedDestructionFacts = GlClaimedDestructionFacts.precreate(partialCleanupEvidence)
    private val partialCleanupEndpointOperation =
        owner.endpointOperation(partialCleanupOccurrence, Runnable { executePartialCleanup() })
    private var partialCleanupReady: Boolean = false
    private var primaryEndpointReleased: Boolean = false
    private var partialCleanupNamespaceConsumed: Boolean = false
    private var partialCleanupSuffixSucceeded: Boolean = false
    private var partialCleanupSuffixReturned: Boolean = false
    private var partialCleanupEndpointReleased: Boolean = false
    private var partialCleanupReturnFailed: Boolean = false
    private var partialCleanupPhysicalRetirementClaimed: Boolean = false
    private val partialCleanupWork: GlTerminalCleanupWork = GlTerminalCleanupWork.create(
        partialCleanupOccurrence.identity,
        GlTerminalCleanupOrigin.TerminalConverted,
    )
    private var partialCleanupConversion: GlTerminalCleanupConversion? = null

    private fun setPartialCleanupReady(ready: Boolean) = owner.glGate.withLock {
        partialCleanupReady = ready
    }

    private fun isPartialCleanupReady(): Boolean = owner.glGate.withLock { partialCleanupReady }

    private fun releasePrimaryEndpoint(): Boolean {
        if (owner.glGate.withLock { primaryEndpointReleased }) return true
        if (!owner.releaseSettledOperation(endpointOperation)) return false
        owner.glGate.withLock { primaryEndpointReleased = true }
        return true
    }

    private fun execute() {
        try {
            if (owner.constructSession(evidence)) {
                owner.checkFatalFence()
                ownerBag.returnedOwner = owner
                evidence.returnedOwner = owner
                evidence.result = GlOperationResult.Success
                evidence.contextIntegrity = ContextIntegrity.Intact
            }
            owner.checkFatalFence()
            setPartialCleanupReady(evidence.result != GlOperationResult.Success && owner.sessionEglStarted)
            owner.checkFatalFence()
            owner.closeNormalResult(evidence)
            occurrence.publishNormalReturn()
            if (!isPartialCleanupReady()) closeUnusedPartialCleanup()
        } catch (exception: Exception) {
            owner.checkFatalFence()
            owner.markContextUnknown()
            evidence.throwable = exception
            evidence.result = GlOperationResult.InternalFailure
            evidence.contextIntegrity = ContextIntegrity.Unknown
            owner.checkFatalFence()
            setPartialCleanupReady(owner.sessionEglStarted)
            owner.checkFatalFence()
            occurrence.publishThrownReturn(exception)
            if (!isPartialCleanupReady()) closeUnusedPartialCleanup()
        }
    }

    override fun submit(): Boolean {
        val result = owner.submit(endpointOperation)
        if (!result.isHandedOff) closeUnusedPartialCleanup()
        return result.isHandedOff
    }

    private fun closeUnusedPartialCleanup() {
        partialCleanupOccurrence.arbitrateTerminal(mandatoryCleanup = false)
        owner.signalSettlement()
    }

    private fun executePartialCleanup() {
        try {
            val structurallyNoContext = owner.context == EGL14.EGL_NO_CONTEXT
            val contextRetired = if (structurallyNoContext) {
                owner.checkFatalFence()
                owner.unbindStep.requireMutable()
                owner.unbindStep.applicability = EglStepApplicability.Inapplicable
                owner.unbindStep.freeze()
                owner.inverseCurrentStep.requireMutable()
                owner.inverseCurrentStep.applicability = EglStepApplicability.Inapplicable
                owner.inverseCurrentStep.freeze()
                owner.contextDestructionStep.requireMutable()
                owner.contextDestructionStep.applicability = EglStepApplicability.Inapplicable
                owner.contextDestructionStep.freeze()
                owner.glGate.withLock {
                    if (!owner.contextUnbound || !owner.contextDestroyed) {
                        owner.contextUnbound = true
                        owner.contextDestroyed = true
                        owner.commitRenderCurrentnessMutationLocked()
                    }
                }
                false
            } else {
                check(owner.bindPartialStartupContextNamespace(partialCleanupNamespace))
                partialCleanupEvidence.bindPhysicalRetirementCandidate(partialCleanupNamespace, owner.context)
                owner.unbindAndDestroyContext(this, partialCleanupEvidence)
            }
            if (contextRetired) {
                owner.checkFatalFence()
            } else if (structurallyNoContext) {
                owner.checkFatalFence()
                partialCleanupEvidence.result = GlOperationResult.StructurallyNoContext
                partialCleanupEvidence.contextIntegrity = owner.contextIntegrity
            }
            owner.checkFatalFence()
            owner.closeNormalResult(partialCleanupEvidence)
            partialCleanupOccurrence.publishNormalReturn()
            owner.checkFatalFence()
            owner.signalSettlement()
        } catch (exception: Exception) {
            owner.checkFatalFence()
            partialCleanupEvidence.throwable = exception
            partialCleanupEvidence.result = GlOperationResult.InternalFailure
            partialCleanupEvidence.contextIntegrity = ContextIntegrity.Unknown
            owner.checkFatalFence()
            partialCleanupOccurrence.publishThrownReturn(exception)
            owner.checkFatalFence()
            owner.signalSettlement()
        }
        val suffixSucceeded = if (owner.contextUnbound) {
            owner.destroyPbufferAndReleaseThread()
        } else {
            owner.recordUnreachableSuffixResidue()
            false
        }
        recordPartialCleanupSuffixReturn(suffixSucceeded)
    }

    override fun submitPartialCleanup(): Boolean {
        if (!isPartialCleanupReady()) return false
        val conversion = partialCleanupConversion ?: partialCleanupOccurrence
            .toGlTerminalCleanup(partialCleanupWork)
            .also { partialCleanupConversion = it }
        if (conversion !is GlTerminalCleanupConversion.Ready) return false
        if (!releasePrimaryEndpoint() || !owner.claimTeardown(this)) {
            return false
        }
        if (!owner.retainPartialStartupCleanupHandle(this)) {
            owner.releaseTeardown(this)
            return false
        }
        val result = owner.submit(partialCleanupEndpointOperation, this)
        return result.isHandedOff
    }

    override fun claimPartialCleanup(): GlClaimedDestructionFacts? {
        if (!isPartialCleanupReady()) return null
        val facts = owner.claimDestruction(partialCleanupOccurrence, partialCleanupClaimedFacts)
        val returnedPhysicalFailure = facts != null && facts.returnOutcome is
                GlPhysicalResourceReturnOutcome.ContextNamespaceRetired &&
                (!facts.normalReturn || facts.returnThrowable != null || facts.throwable != null ||
                        facts.result != GlOperationResult.Success)
        if (returnedPhysicalFailure) owner.glGate.withLock { partialCleanupReturnFailed = true }
        when (val outcome = facts?.returnOutcome) {
            is GlPhysicalResourceReturnOutcome.ContextNamespaceRetired -> {
                if (outcome.namespace !== partialCleanupNamespace ||
                    facts.receipt !== partialCleanupEvidence.physicalRetirementReceiptOrNull()
                ) {
                    return null
                }
                owner.glGate.withLock { partialCleanupPhysicalRetirementClaimed = true }
                if (!recordPartialCleanupNamespaceConsumed()) return null
            }

            is GlPhysicalResourceReturnOutcome.StructurallyNoContext -> {
                if (facts.result != GlOperationResult.StructurallyNoContext || facts.receipt != null ||
                    outcome.operationIdentity != partialCleanupOccurrence.identity ||
                    !recordPartialCleanupStructuralReduction()
                ) {
                    return null
                }
            }

            else -> if (facts?.result == GlOperationResult.Success ||
                facts?.result == GlOperationResult.StructurallyNoContext
            ) {
                return null
            }
        }
        if (!returnedPhysicalFailure && owner.releaseSettledOperation(partialCleanupEndpointOperation)) {
            owner.glGate.withLock { partialCleanupEndpointReleased = true }
        }
        return facts
    }

    private fun recordPartialCleanupNamespaceConsumed(): Boolean {
        val consumed = owner.glGate.withLock {
            if (!partialCleanupReturnFailed) owner.checkFatalLocked()
            check(!partialCleanupNamespaceConsumed)
            if (partialCleanupSuffixSucceeded && !owner.ownsTeardownLocked(this)) {
                return@withLock false
            }
            if (!owner.consumePartialContextNamespaceLocked(
                    expectedNamespace = partialCleanupNamespace,
                    authorizedPhysicalRetirementEvidence = partialCleanupEvidence,
                )
            ) {
                return@withLock false
            }
            partialCleanupNamespaceConsumed = true
            if (partialCleanupSuffixSucceeded && !partialCleanupReturnFailed) {
                check(owner.releaseTeardownLocked(this))
            }
            true
        }
        owner.signalSettlement()
        return consumed
    }

    internal fun retryClaimedPartialCleanupReduction(): Boolean {
        if (owner.glGate.withLock {
                !partialCleanupPhysicalRetirementClaimed || partialCleanupNamespaceConsumed
            }
        ) {
            return true
        }
        return recordPartialCleanupNamespaceConsumed()
    }

    private fun recordPartialCleanupStructuralReduction(): Boolean {
        val reduced = owner.glGate.withLock {
            owner.checkFatalLocked()
            check(!partialCleanupNamespaceConsumed)
            if (partialCleanupSuffixSucceeded && !owner.ownsTeardownLocked(this)) {
                return@withLock false
            }
            if (!owner.reduceStructurallyAbsentPartialContextLocked()) return@withLock false
            partialCleanupNamespaceConsumed = true
            if (partialCleanupSuffixSucceeded) {
                check(owner.releaseTeardownLocked(this))
            }
            true
        }
        owner.signalSettlement()
        return reduced
    }

    private fun recordPartialCleanupSuffixReturn(succeeded: Boolean) {
        owner.glGate.withLock {
            owner.checkFatalLocked()
            check(!partialCleanupSuffixReturned)
            if (succeeded && !partialCleanupReturnFailed && partialCleanupNamespaceConsumed &&
                !owner.ownsTeardownLocked(this)
            ) {
                return@withLock
            }
            partialCleanupSuffixReturned = true
            partialCleanupSuffixSucceeded = succeeded
            if (succeeded && !partialCleanupReturnFailed && partialCleanupNamespaceConsumed) {
                check(owner.releaseTeardownLocked(this))
            }
        }
        owner.signalSettlement()
    }

    internal fun unresolvedPartialCleanupEndpoint(): Boolean = owner.glGate.withLock {
        partialCleanupReturnFailed || !partialCleanupNamespaceConsumed ||
                !partialCleanupSuffixReturned || !partialCleanupSuffixSucceeded
    }

    internal fun releasePartialCleanupEndpointAfterSuffix(): Boolean {
        if (owner.glGate.withLock {
                partialCleanupReturnFailed || !partialCleanupNamespaceConsumed || !partialCleanupSuffixSucceeded
            }
        ) {
            return false
        }
        if (owner.glGate.withLock { partialCleanupEndpointReleased }) return true
        if (!owner.releaseSettledOperation(partialCleanupEndpointOperation)) return false
        owner.glGate.withLock { partialCleanupEndpointReleased = true }
        return true
    }

    override val deadlineWakeLink: ControlWakeLink = checkNotNull(occurrence.controlWakeLink)

    override val partialCleanupDeadlineWakeLink: ControlWakeLink =
        checkNotNull(partialCleanupOccurrence.controlWakeLink)

    init {
        check(deadlineWakeLink !== partialCleanupDeadlineWakeLink)
    }

    override fun claim(): GlClaimedOperationFacts? {
        val facts = owner.claimOperation(occurrence, claimedFacts)
        releasePrimaryEndpoint()
        if (facts == null && !owner.glGate.withLock { owner.sessionEglStarted }) {
            closeUnusedPartialCleanup()
        }
        return facts
    }
}

internal class GlEglRuntime internal constructor(
    private val owner: GlPipelineOwner,
) {
    internal fun glesGroup(evidence: GlOperationEvidence, commands: () -> Boolean): Boolean =
        glesGroupCompletion(evidence, commands).commandsSucceeded

    internal fun glesGroupCompletion(
        evidence: GlOperationEvidence,
        commands: () -> Boolean,
    ): GlGlesGroupCompletion {
        try {
            if (!requireCurrent(evidence)) return GlGlesGroupCompletion.FailedClosed
        } catch (exception: Exception) {
            owner.markContextUnknown()
            evidence.throwable = exception
            evidence.result = GlOperationResult.InternalFailure
            evidence.contextIntegrity = ContextIntegrity.Unknown
            throw exception
        }
        return glesGroupAfterCurrentCompletion(evidence, commands = commands)
    }

    internal fun glesGroupAfterCurrent(
        evidence: GlOperationEvidence,
        probeFacts: GlProbeFacts? = null,
        commands: () -> Boolean,
    ): Boolean = glesGroupAfterCurrentCompletion(evidence, probeFacts, commands).commandsSucceeded

    private fun glesGroupAfterCurrentCompletion(
        evidence: GlOperationEvidence,
        probeFacts: GlProbeFacts? = null,
        commands: () -> Boolean,
    ): GlGlesGroupCompletion {
        val preprobe = owner.outward { GLES20.glGetError() }
        owner.checkFatalFence()
        probeFacts?.recordPreprobe(preprobe)
        evidence.preprobeErrorCode = preprobe
        evidence.preprobeErrorCodePresent = true
        if (preprobe != GLES20.GL_NO_ERROR) {
            probeFacts?.freezeAfterPreprobe()
            recordGlError(evidence, preprobe, preprobe = true)
            return GlGlesGroupCompletion.FailedClosed
        }
        val logicalSuccess = try {
            commands()
        } catch (exception: Exception) {
            owner.markContextUnknown()
            evidence.throwable = exception
            evidence.result = GlOperationResult.InternalFailure
            evidence.contextIntegrity = ContextIntegrity.Unknown
            throw exception
        }
        val postprobe = owner.outward { GLES20.glGetError() }
        owner.checkFatalFence()
        probeFacts?.recordPostprobe(postprobe)
        evidence.postprobeErrorCode = postprobe
        evidence.postprobeErrorCodePresent = true
        if (postprobe != GLES20.GL_NO_ERROR) {
            recordGlError(evidence, postprobe, preprobe = false)
            return GlGlesGroupCompletion.FailedClosed
        }
        if (!logicalSuccess) {
            evidence.contextIntegrity = ContextIntegrity.Intact
            evidence.result = GlOperationResult.InternalFailure
            return GlGlesGroupCompletion.LogicalFailureAfterCleanPostprobe
        }
        evidence.contextIntegrity = ContextIntegrity.Intact
        return GlGlesGroupCompletion.Success
    }

    internal fun glesGroup(evidence: GlDestructionEvidence, commands: () -> Boolean): Boolean {
        try {
            if (!requireCurrent(evidence)) return false
        } catch (exception: Exception) {
            owner.markContextUnknown()
            evidence.throwable = exception
            evidence.result = GlOperationResult.InternalFailure
            evidence.contextIntegrity = ContextIntegrity.Unknown
            throw exception
        }
        val preprobe = owner.outward { GLES20.glGetError() }
        owner.checkFatalFence()
        evidence.preprobeErrorCode = preprobe
        evidence.preprobeErrorCodePresent = true
        if (preprobe != GLES20.GL_NO_ERROR) {
            recordGlError(evidence, preprobe, preprobe = true)
            return false
        }
        val logicalSuccess = try {
            commands()
        } catch (exception: Exception) {
            owner.markContextUnknown()
            evidence.throwable = exception
            evidence.result = GlOperationResult.InternalFailure
            evidence.contextIntegrity = ContextIntegrity.Unknown
            throw exception
        }
        val postprobe = owner.outward { GLES20.glGetError() }
        owner.checkFatalFence()
        evidence.postprobeErrorCode = postprobe
        evidence.postprobeErrorCodePresent = true
        if (postprobe != GLES20.GL_NO_ERROR) {
            recordGlError(evidence, postprobe, preprobe = false)
            return false
        }
        if (!logicalSuccess) {
            evidence.contextIntegrity = ContextIntegrity.Intact
            evidence.result = GlOperationResult.InternalFailure
            return false
        }
        evidence.contextIntegrity = ContextIntegrity.Intact
        return true
    }

    internal fun requireCurrent(evidence: GlOperationEvidence): Boolean {
        if (owner.contextIntegrity != ContextIntegrity.Intact || owner.context == EGL14.EGL_NO_CONTEXT) {
            owner.checkFatalFence()
            evidence.contextIntegrity = owner.contextIntegrity
            evidence.result = GlOperationResult.InternalFailure
            return false
        }
        val current = owner.outward { EGL14.eglGetCurrentContext() }
        if (current == owner.context) return true
        val error = owner.outward { EGL14.eglGetError() }
        owner.checkFatalFence()
        evidence.preprobeErrorCode = error
        evidence.preprobeErrorCodePresent = true
        evidence.contextIntegrity = ContextIntegrity.Unknown
        evidence.result = GlOperationResult.InternalFailure
        owner.markContextUnknown()
        return false
    }

    private fun requireCurrent(evidence: GlDestructionEvidence): Boolean {
        if (owner.contextIntegrity != ContextIntegrity.Intact || owner.context == EGL14.EGL_NO_CONTEXT) {
            owner.checkFatalFence()
            evidence.contextIntegrity = owner.contextIntegrity
            evidence.result = GlOperationResult.InternalFailure
            return false
        }
        val current = owner.outward { EGL14.eglGetCurrentContext() }
        if (current == owner.context) return true
        val error = owner.outward { EGL14.eglGetError() }
        owner.checkFatalFence()
        evidence.preprobeErrorCode = error
        evidence.preprobeErrorCodePresent = true
        evidence.contextIntegrity = ContextIntegrity.Unknown
        evidence.result = GlOperationResult.InternalFailure
        owner.markContextUnknown()
        return false
    }

    internal fun construct(evidence: GlOperationEvidence): Boolean {
        if (owner.display != EGL14.EGL_NO_DISPLAY || owner.context != EGL14.EGL_NO_CONTEXT || owner.pbuffer != EGL14.EGL_NO_SURFACE) {
            evidence.result = GlOperationResult.InternalFailure
            return false
        }
        owner.checkFatalFence()
        owner.glGate.withLock {
            check(!owner.sessionEglStarted)
            owner.sessionEglStarted = true
            owner.commitRenderCurrentnessMutationLocked()
        }
        val candidateDisplay = owner.outwardAdopt(
            adopt = { candidate -> if (candidate != EGL14.EGL_NO_DISPLAY) owner.display = candidate },
        ) {
            EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        }
        if (candidateDisplay == EGL14.EGL_NO_DISPLAY) {
            recordEglFailure(evidence, owner.outward { EGL14.eglGetError() })
            return false
        }
        if (!owner.outward { EGL14.eglInitialize(owner.display, owner.eglMajor, 0, owner.eglMinor, 0) }) {
            recordEglFailure(evidence, owner.outward { EGL14.eglGetError() })
            return false
        }
        val configChosen = owner.outward {
            EGL14.eglChooseConfig(owner.display, owner.eglConfigAttributes, 0, owner.eglConfigs, 0, 1, owner.eglConfigCount, 0)
        }
        val selectedConfig = owner.eglConfigs[0]
        if (configChosen && owner.eglConfigCount[0] == 1 && selectedConfig != null) {
            owner.config = selectedConfig
        }
        owner.checkFatalFence()
        if (!configChosen) {
            recordEglFailure(evidence, owner.outward { EGL14.eglGetError() })
            return false
        }
        if (owner.eglConfigCount[0] != 1 || selectedConfig == null) {
            evidence.result = GlOperationResult.InternalFailure
            evidence.contextIntegrity = ContextIntegrity.Unknown
            return false
        }
        val candidateContext = owner.outwardAdopt(adopt = { candidate ->
            if (candidate != EGL14.EGL_NO_CONTEXT) {
                owner.glGate.withLock {
                    owner.context = candidate
                    owner.commitRenderCurrentnessMutationLocked()
                }
            }
        }) {
            EGL14.eglCreateContext(owner.display, selectedConfig, EGL14.EGL_NO_CONTEXT, owner.eglContextAttributes, 0)
        }
        if (candidateContext == EGL14.EGL_NO_CONTEXT) {
            recordEglFailure(evidence, owner.outward { EGL14.eglGetError() })
            return false
        }
        val candidatePbuffer = owner.outwardAdopt(adopt = { candidate ->
            if (candidate != EGL14.EGL_NO_SURFACE) {
                owner.glGate.withLock {
                    owner.pbuffer = candidate
                    owner.commitRenderCurrentnessMutationLocked()
                }
            }
        }) {
            EGL14.eglCreatePbufferSurface(owner.display, selectedConfig, owner.eglPbufferAttributes, 0)
        }
        if (candidatePbuffer == EGL14.EGL_NO_SURFACE) {
            recordEglFailure(evidence, owner.outward { EGL14.eglGetError() })
            return false
        }
        if (!owner.outward { EGL14.eglMakeCurrent(owner.display, owner.pbuffer, owner.pbuffer, owner.context) }) {
            recordEglFailure(evidence, owner.outward { EGL14.eglGetError() })
            return false
        }
        if (owner.outward { EGL14.eglGetCurrentContext() } != owner.context) {
            recordInitialCurrentMismatch(evidence, owner.outward { EGL14.eglGetError() })
            return false
        }
        owner.checkFatalFence()
        owner.recordContextIntegrity(ContextIntegrity.Intact)
        return owner.queryCapabilities(evidence)
    }

    internal fun unbindAndDestroyContext(teardownOwner: GlTeardownOwner, evidence: GlDestructionEvidence): Boolean {
        if (owner.context == EGL14.EGL_NO_CONTEXT || owner.display == EGL14.EGL_NO_DISPLAY) {
            return false
        }
        if (!owner.ownsTeardown(teardownOwner)) return false
        if (!owner.contextUnbound) {
            owner.unbindStep.requireMutable()
            owner.unbindStep.applicability = EglStepApplicability.Applicable
            val unbound = try {
                owner.checkFatalFence()
                val returned = EGL14.eglMakeCurrent(owner.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                owner.unbindStep.outcome = if (returned) EglStepOutcome.ReturnedTrue else EglStepOutcome.ReturnedFalse
                owner.unbindStep.receiptPresent = returned
                owner.checkFatalFence()
                returned
            } catch (exception: Exception) {
                owner.unbindStep.outcome = EglStepOutcome.Thrown
                owner.unbindStep.throwable = exception
                owner.unbindStep.residueRetained = true
                owner.unbindStep.freeze()
                recordEglThrowable(evidence, exception)
                return false
            }
            if (!unbound) {
                if (!recordReturnedFalseError(owner.unbindStep, evidence)) return false
                return false
            }
            owner.unbindStep.freeze()
            owner.inverseCurrentStep.requireMutable()
            owner.inverseCurrentStep.applicability = EglStepApplicability.Applicable
            val inverseCurrent = try {
                owner.checkFatalFence()
                val current = EGL14.eglGetCurrentContext()
                val returned = current == EGL14.EGL_NO_CONTEXT
                owner.inverseCurrentStep.outcome = if (returned) EglStepOutcome.ReturnedTrue else EglStepOutcome.ReturnedFalse
                owner.inverseCurrentStep.receiptPresent = returned
                owner.checkFatalFence()
                returned
            } catch (exception: Exception) {
                owner.inverseCurrentStep.outcome = EglStepOutcome.Thrown
                owner.inverseCurrentStep.throwable = exception
                owner.inverseCurrentStep.residueRetained = true
                owner.inverseCurrentStep.freeze()
                recordEglThrowable(evidence, exception)
                return false
            }
            if (!inverseCurrent) {
                if (!recordReturnedFalseError(owner.inverseCurrentStep, evidence)) return false
                return false
            }
            owner.inverseCurrentStep.freeze()
            owner.checkFatalFence()
            owner.glGate.withLock {
                if (!owner.contextUnbound) {
                    owner.contextUnbound = true
                    owner.commitRenderCurrentnessMutationLocked()
                }
            }
        }
        if (!owner.contextDestroyed) {
            owner.contextDestructionStep.requireMutable()
            owner.contextDestructionStep.applicability = EglStepApplicability.Applicable
            val destroyed = try {
                owner.checkFatalFence()
                val returned = EGL14.eglDestroyContext(owner.display, owner.context)
                if (returned) {
                    evidence.recordPrecreatedPhysicalRetirementSuccess(owner.contextIntegrity)
                }
                owner.contextDestructionStep.outcome = if (returned) EglStepOutcome.ReturnedTrue else EglStepOutcome.ReturnedFalse
                owner.contextDestructionStep.receiptPresent = returned
                if (returned) {
                    owner.glGate.withLock {
                        owner.contextDestroyed = true
                        owner.context = EGL14.EGL_NO_CONTEXT
                        owner.commitRenderCurrentnessMutationLocked()
                    }
                }
                owner.checkFatalFence()
                returned
            } catch (exception: Exception) {
                owner.contextDestructionStep.outcome = EglStepOutcome.Thrown
                owner.contextDestructionStep.throwable = exception
                owner.contextDestructionStep.residueRetained = true
                owner.contextDestructionStep.freeze()
                recordEglThrowable(evidence, exception)
                return false
            }
            if (!destroyed) {
                if (!recordReturnedFalseError(owner.contextDestructionStep, evidence)) return false
                return false
            }
            owner.contextDestructionStep.freeze()
        }
        return true
    }

    internal fun destroyPbufferAndReleaseThread(): Boolean {
        if (!owner.pbufferDestroyed && owner.pbuffer != EGL14.EGL_NO_SURFACE) {
            owner.pbufferSuffix.requireMutable()
            owner.pbufferSuffix.applicability = EglStepApplicability.Applicable
            try {
                owner.checkFatalFence()
                val returned = EGL14.eglDestroySurface(owner.display, owner.pbuffer)
                owner.pbufferSuffix.outcome = if (returned) EglStepOutcome.ReturnedTrue else EglStepOutcome.ReturnedFalse
                owner.pbufferSuffix.receiptPresent = returned
                if (returned) {
                    owner.glGate.withLock {
                        owner.pbufferDestroyed = true
                        owner.pbuffer = EGL14.EGL_NO_SURFACE
                        owner.commitRenderCurrentnessMutationLocked()
                    }
                }
                owner.checkFatalFence()
            } catch (exception: Exception) {
                owner.pbufferSuffix.outcome = EglStepOutcome.Thrown
                owner.pbufferSuffix.throwable = exception
                owner.pbufferSuffix.residueRetained = true
            }
            if (owner.pbufferSuffix.outcome == EglStepOutcome.ReturnedFalse) {
                recordSuffixReturnedFalse(owner.pbufferSuffix)
            }
        } else if (!owner.pbufferDestroyed) {
            owner.pbufferSuffix.requireMutable()
            owner.pbufferSuffix.applicability = EglStepApplicability.Inapplicable
            owner.glGate.withLock {
                if (!owner.pbufferDestroyed) {
                    owner.pbufferDestroyed = true
                    owner.commitRenderCurrentnessMutationLocked()
                }
            }
        }
        owner.pbufferSuffix.freeze()
        if (!owner.threadReleased) {
            owner.releaseThreadSuffix.requireMutable()
            owner.releaseThreadSuffix.applicability = EglStepApplicability.Applicable
            try {
                owner.checkFatalFence()
                val returned = EGL14.eglReleaseThread()
                owner.releaseThreadSuffix.outcome = if (returned) EglStepOutcome.ReturnedTrue else EglStepOutcome.ReturnedFalse
                owner.releaseThreadSuffix.receiptPresent = returned
                if (returned) {
                    owner.threadReleased = true
                }
                owner.checkFatalFence()
            } catch (exception: Exception) {
                owner.releaseThreadSuffix.outcome = EglStepOutcome.Thrown
                owner.releaseThreadSuffix.throwable = exception
                owner.releaseThreadSuffix.residueRetained = true
            }
            if (owner.releaseThreadSuffix.outcome == EglStepOutcome.ReturnedFalse) {
                recordSuffixReturnedFalse(owner.releaseThreadSuffix)
            }
        }
        owner.releaseThreadSuffix.freeze()
        if (owner.context == EGL14.EGL_NO_CONTEXT && owner.pbufferDestroyed && owner.threadReleased) {
            owner.checkFatalFence()
            owner.config = null
            owner.display = EGL14.EGL_NO_DISPLAY
            return true
        }
        return false
    }

    private fun recordSuffixReturnedFalse(step: EglStepCell) {
        check(step.outcome == EglStepOutcome.ReturnedFalse)
        try {
            owner.checkFatalFence()
            step.errorCode = EGL14.eglGetError()
            step.errorCodePresent = true
            step.residueRetained = true
            owner.checkFatalFence()
        } catch (exception: Exception) {
            step.throwable = exception
            step.residueRetained = true
        }
    }

    internal fun recordUnreachableSuffixResidue() {
        owner.checkFatalFence()
        owner.pbufferSuffix.requireMutable()
        owner.releaseThreadSuffix.requireMutable()
        if (!owner.pbufferDestroyed) {
            owner.pbufferSuffix.applicability =
                if (owner.pbuffer == EGL14.EGL_NO_SURFACE) {
                    EglStepApplicability.Inapplicable
                } else {
                    EglStepApplicability.Applicable
                }
            owner.pbufferSuffix.residueRetained = owner.pbuffer != EGL14.EGL_NO_SURFACE
        }
        if (!owner.threadReleased) {
            owner.releaseThreadSuffix.applicability = EglStepApplicability.Applicable
            owner.releaseThreadSuffix.residueRetained = true
        }
        owner.pbufferSuffix.freeze()
        owner.releaseThreadSuffix.freeze()
    }

    private fun recordReturnedFalseError(step: EglStepCell, evidence: GlDestructionEvidence): Boolean {
        check(step.outcome == EglStepOutcome.ReturnedFalse)
        return try {
            owner.checkFatalFence()
            val error = EGL14.eglGetError()
            step.errorCode = error
            step.errorCodePresent = true
            step.residueRetained = true
            step.freeze()
            owner.checkFatalFence()
            recordEglFailure(evidence, error)
            true
        } catch (exception: Exception) {
            step.throwable = exception
            step.residueRetained = true
            step.freeze()
            recordEglThrowable(evidence, exception)
            false
        }
    }

    private fun recordEglThrowable(evidence: GlDestructionEvidence, exception: Exception) {
        owner.markContextUnknown()
        evidence.throwable = exception
        evidence.result = GlOperationResult.InternalFailure
        evidence.contextIntegrity = ContextIntegrity.Unknown
    }

    private fun recordGlError(evidence: GlOperationEvidence, error: Int, preprobe: Boolean) {
        owner.checkFatalFence()
        if (preprobe) {
            evidence.preprobeErrorCode = error
            evidence.preprobeErrorCodePresent = true
        } else {
            evidence.postprobeErrorCode = error
            evidence.postprobeErrorCodePresent = true
        }
        owner.recordContextIntegrity(
            if (error == GLES20.GL_OUT_OF_MEMORY) {
                ContextIntegrity.PoisonedByOutOfMemory
            } else {
                ContextIntegrity.Unknown
            },
        )
        evidence.contextIntegrity = owner.contextIntegrity
        evidence.result = if (error == GLES20.GL_OUT_OF_MEMORY) {
            GlOperationResult.ResourceExhausted
        } else {
            GlOperationResult.InternalFailure
        }
    }

    private fun recordGlError(evidence: GlDestructionEvidence, error: Int, preprobe: Boolean) {
        owner.checkFatalFence()
        if (preprobe) {
            evidence.preprobeErrorCode = error
            evidence.preprobeErrorCodePresent = true
        } else {
            evidence.postprobeErrorCode = error
            evidence.postprobeErrorCodePresent = true
        }
        owner.recordContextIntegrity(
            if (error == GLES20.GL_OUT_OF_MEMORY) {
                ContextIntegrity.PoisonedByOutOfMemory
            } else {
                ContextIntegrity.Unknown
            },
        )
        evidence.contextIntegrity = owner.contextIntegrity
        evidence.result = if (error == GLES20.GL_OUT_OF_MEMORY) {
            GlOperationResult.ResourceExhausted
        } else {
            GlOperationResult.InternalFailure
        }
    }

    private fun recordEglFailure(evidence: GlOperationEvidence, error: Int) {
        owner.checkFatalFence()
        evidence.preprobeErrorCode = error
        evidence.preprobeErrorCodePresent = true
        evidence.result = if (error == EGL14.EGL_BAD_ALLOC) {
            GlOperationResult.ResourceExhausted
        } else {
            GlOperationResult.InternalFailure
        }
        evidence.contextIntegrity = ContextIntegrity.Unknown
        owner.markContextUnknown()
    }

    private fun recordInitialCurrentMismatch(evidence: GlOperationEvidence, error: Int) {
        owner.checkFatalFence()
        evidence.preprobeErrorCode = error
        evidence.preprobeErrorCodePresent = true
        evidence.result = GlOperationResult.InternalFailure
        evidence.contextIntegrity = ContextIntegrity.Unknown
        owner.markContextUnknown()
    }

    private fun recordEglFailure(evidence: GlDestructionEvidence, error: Int) {
        owner.checkFatalFence()
        owner.markContextUnknown()
        evidence.preprobeErrorCode = error
        evidence.preprobeErrorCodePresent = true
        evidence.result = GlOperationResult.InternalFailure
        evidence.contextIntegrity = ContextIntegrity.Unknown
    }
}
