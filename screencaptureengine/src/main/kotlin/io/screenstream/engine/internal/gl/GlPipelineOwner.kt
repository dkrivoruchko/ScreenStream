package io.screenstream.engine.internal.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import io.screenstream.engine.internal.jpeg.JpegRuntimeProduct
import io.screenstream.engine.internal.jpeg.RgbaCarrierLease
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.PrivateExecutorOperation
import io.screenstream.engine.internal.settlement.PrivateExecutorSubmissionResult
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorTerminationReceipt
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.PreparedTarget
import io.screenstream.engine.internal.target.TargetFrameEntryResult
import io.screenstream.engine.internal.target.TargetPorts
import io.screenstream.engine.internal.target.TargetRetainedGlAdmittedFact
import io.screenstream.engine.internal.target.TargetRetirement
import io.screenstream.engine.internal.target.TargetSurfaceReleaseEvidence
import io.screenstream.engine.internal.target.TargetSurfaceReleaseReadyFact
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class GlPipelineOwner(
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
    threadName: String,
) : OperationReturnedOwner {
    internal sealed interface GlRenderTargetOwner : OperationReturnedOwner {
        val renderGeneration: Long
        val compatibilityFacts: GlRenderTargetCompatibilityFacts
    }

    internal interface OrderlyShutdownCommand

    internal interface SessionConstructionCommand {
        val deadlineWakeLink: ControlWakeLink
        val partialCleanupDeadlineWakeLink: ControlWakeLink
        fun submit(): Boolean
        fun submitPartialCleanup(): Boolean
        fun claimPartialCleanup(): GlClaimedDestructionFacts?
        fun claim(): GlClaimedOperationFacts?
    }

    internal interface SurfaceReleaseCommand {
        val occurrence: OperationOccurrence<TargetSurfaceReleaseEvidence>
        val deadlineWakeLink: ControlWakeLink
        fun submit(): Boolean
        fun claim(): TargetSurfaceReleaseClaim?
    }

    internal interface TargetConstructionCommand {
        val occurrence: OperationOccurrence<GlOperationEvidence>
        val deadlineWakeLink: ControlWakeLink
        fun submit(): Boolean
        fun retireAfterTargetArbitration()
    }

    internal interface RenderTargetConstructionCommand {
        val occurrence: OperationOccurrence<GlOperationEvidence>
        val deadlineWakeLink: ControlWakeLink
        fun submit(): Boolean
        fun claim(): GlRenderTargetConstructionClaim?
        fun commitInstallation(claim: GlRenderTargetConstructionClaim): GlRenderTargetOwner?
        fun claimCleanupDestruction(claim: GlRenderTargetConstructionClaim): GlRenderTargetCleanupClaim?
    }

    internal interface FrameCommand {
        val deadlineWakeLink: ControlWakeLink
        val targetFrameEntryResult: TargetFrameEntryResult?
        fun submit(): Boolean
        fun claim(): GlClaimedOperationFacts?
    }

    internal interface DestructionCommand {
        val occurrence: OperationOccurrence<GlDestructionEvidence>
        val deadlineWakeLink: ControlWakeLink
        fun submit(): Boolean
        fun claim(): GlClaimedDestructionFacts?
    }

    internal interface TargetScopeDestructionCommand {
        val occurrence: OperationOccurrence<GlDestructionEvidence>
        val namespaceOccurrence: OperationOccurrence<GlDestructionEvidence>
        val deadlineWakeLink: ControlWakeLink
        val namespaceDeadlineWakeLink: ControlWakeLink
        fun submit(): Boolean
        fun claim(): GlClaimedDestructionFacts?
        fun submitNamespaceRetirement(): Boolean
        fun claimNamespaceRetirement(): GlClaimedDestructionFacts?
    }

    internal val glGate: ReentrantLock = ReentrantLock(false)
    internal var installedRenderTarget: GlRenderTargetState? = null
    internal var contextRenderTarget: GlRenderTargetState? = null
    private val currentnessReporter: GlCurrentnessReporter = GlCurrentnessReporter(this)

    internal var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    internal var config: EGLConfig? = null
    internal var context: EGLContext = EGL14.EGL_NO_CONTEXT
    internal var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    internal var contextIntegrity: ContextIntegrity = ContextIntegrity.Unknown
        private set
    private val capabilityOwner: GlCapabilityOwner = GlCapabilityOwner(this)
    private val program: GlProgram = GlProgram(this)
    private var programDestructionHandle: GlDestructionHandle? = null
    private var contextNamespaceDestructionHandle: GlDestructionHandle? = null
    private var targetScopeNamespaceDestructionHandle: GlTargetScopeDestructionHandle? = null
    private var partialStartupCleanupHandle: GlSessionConstructionHandle? = null
    private val framePipeline: GlFramePipeline = GlFramePipeline(this)
    private val eglRuntime: GlEglRuntime = GlEglRuntime(this)
    private val claimArbitrator: GlClaimArbitrator = GlClaimArbitrator(this)
    internal var contextNamespaceTransferredTarget: TargetRetirement.TargetScopeDestructionGraph? = null
    internal var contextNamespace: ContextNamespace? = null
        private set
    internal var contextUnbound: Boolean = false
    internal var contextDestroyed: Boolean = false
    internal var pbufferDestroyed: Boolean = false
    internal var threadReleased: Boolean = false
    internal var sessionEglStarted: Boolean = false
    internal val unbindStep: EglStepCell = EglStepCell()
    internal val inverseCurrentStep: EglStepCell = EglStepCell()
    internal val contextDestructionStep: EglStepCell = EglStepCell()
    internal val pbufferSuffix: EglStepCell = EglStepCell()
    internal val releaseThreadSuffix: EglStepCell = EglStepCell()

    internal val generatedNames: IntArray = IntArray(1)
    internal val eglMajor: IntArray = IntArray(1)
    internal val eglMinor: IntArray = IntArray(1)
    internal val eglConfigCount: IntArray = IntArray(1)
    internal val eglConfigs: Array<EGLConfig?> = arrayOfNulls(1)
    internal val eglConfigAttributes: IntArray = intArrayOf(
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_CONFORMANT, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_DEPTH_SIZE, 0,
        EGL14.EGL_STENCIL_SIZE, 0,
        EGL14.EGL_NONE,
    )
    internal val eglContextAttributes: IntArray = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
    internal val eglPbufferAttributes: IntArray = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
    internal val currentProgram: GlProgram.State?
        get() = program.current

    internal fun renderCurrentnessFact(expected: GlRenderTargetOwner): GlRenderCurrentnessFact? =
        currentnessReporter.snapshot(expected)

    internal fun isRenderCurrentnessVersion(fact: GlRenderCurrentnessFact): Boolean =
        currentnessReporter.stillMatches(fact)

    internal val isRenderCurrentnessVersionExhausted: Boolean
        get() = currentnessReporter.isExhausted

    internal fun commitRenderCurrentnessMutationLocked() {
        currentnessReporter.recordMutationLocked()
    }

    internal fun commitRenderCurrentnessMutation() {
        glGate.withLock { commitRenderCurrentnessMutationLocked() }
    }

    internal fun renderPipelineCompleteLocked(state: GlRenderTargetState): Boolean {
        check(glGate.isHeldByCurrentThread)
        return installedRenderTarget === state && state.textureName != 0 && state.framebufferName != 0 &&
                program.current != null && sessionEglStarted && context != EGL14.EGL_NO_CONTEXT &&
                pbuffer != EGL14.EGL_NO_SURFACE && !contextUnbound && !contextDestroyed
    }

    internal fun frameReconciliationPipelineClosedLocked(state: GlRenderTargetState): Boolean {
        check(glGate.isHeldByCurrentThread)
        return contextIntegrity != ContextIntegrity.Intact || state.destructionClaimedLocked(this) ||
                !renderPipelineCompleteLocked(state) || currentnessReporter.isExhaustedLocked()
    }

    internal fun frameReconciliationEndpointPoisonedLocked(): Boolean {
        check(glGate.isHeldByCurrentThread)
        val poisoned = laneRuntime.isPoisoned
        checkFatalLocked()
        return poisoned
    }

    internal fun frameActualStateSnapshot(state: GlRenderTargetState): GlFrameActualState? = glGate.withLock {
        check(installedRenderTarget === state)
        state.actualStateLocked(this)
    }

    internal fun isChangedColorAction(
        state: GlRenderTargetState,
        actualState: GlFrameActualState?,
        action: GlColorActionFacts,
    ): Boolean = glGate.withLock {
        check(installedRenderTarget === state)
        check(state.actualStateLocked(this) === actualState)
        state.lastColorAction !== action
    }

    internal fun recordColorAction(
        state: GlRenderTargetState,
        actualState: GlFrameActualState?,
        action: GlColorActionFacts,
    ): Boolean = glGate.withLock {
        if (installedRenderTarget !== state || state.actualStateLocked(this) !== actualState) {
            return@withLock false
        }
        state.lastColorAction = action
        true
    }

    internal val capabilityFacts: GlCapabilityFacts?
        get() = capabilityOwner.facts

    internal val observedFatal: Throwable?
        get() = laneRuntime.observedFatal

    internal val laneTerminationReceipt: PrivateExecutorTerminationReceipt?
        get() = laneRuntime.terminationReceipt

    internal val laneStartupState: PrivateExecutorStartupDisposition
        get() = laneRuntime.startupState

    internal val laneStartupFailure: Throwable?
        get() = laneRuntime.startupFailure

    internal fun prestartLane(): PrivateExecutorStartupDisposition = laneRuntime.prestart()

    internal fun acceptsLaneTerminationReceipt(receipt: PrivateExecutorTerminationReceipt): Boolean =
        laneRuntime.acceptsTerminationReceipt(receipt)

    private val orderlyShutdownCapability: GlOrderlyShutdownCapability = GlOrderlyShutdownCapability(this)
    internal val laneRuntime: GlLaneRuntime = GlLaneRuntime(glGate, settlementSignal, threadName)

    internal fun prepareOrderlyShutdown(): OrderlyShutdownCommand? {
        partialStartupCleanupHandle?.let { handle ->
            if (!handle.retryClaimedPartialCleanupReduction()) return null
            if (handle.unresolvedPartialCleanupEndpoint()) return null
            if (handle.releasePartialCleanupEndpointAfterSuffix()) {
                glGate.withLock {
                    if (partialStartupCleanupHandle === handle) partialStartupCleanupHandle = null
                }
            }
        }
        targetScopeNamespaceDestructionHandle?.let { handle ->
            if (handle.unresolvedNamespaceEndpoint()) return null
            if (handle.releaseNamespaceEndpointAfterSuffix()) {
                glGate.withLock {
                    if (targetScopeNamespaceDestructionHandle === handle) {
                        targetScopeNamespaceDestructionHandle = null
                    }
                }
            }
        }
        contextNamespaceDestructionHandle?.let { handle ->
            if (!handle.retryClaimedContextNamespaceReduction()) return null
            if (handle.unresolvedContextNamespaceProgress() != null) return null
            if (handle.releaseContextNamespaceEndpointAfterSuffix()) {
                glGate.withLock {
                    if (contextNamespaceDestructionHandle === handle) contextNamespaceDestructionHandle = null
                }
            }
        }
        return glGate.withLock {
            if (installedRenderTarget != null || contextRenderTarget != null ||
                program.current != null || context != EGL14.EGL_NO_CONTEXT ||
                pbuffer != EGL14.EGL_NO_SURFACE || contextNamespace != null ||
                contextNamespaceTransferredTarget != null ||
                partialStartupCleanupHandle != null || targetScopeNamespaceDestructionHandle != null ||
                contextNamespaceDestructionHandle != null ||
                display != EGL14.EGL_NO_DISPLAY || config != null || program.hasOwnedNames ||
                laneRuntime.observedFatal != null || laneRuntime.hasUnsettledOperation ||
                laneRuntime.hasActiveTeardownLocked()
            ) {
                return@withLock null
            }
            orderlyShutdownCapability
        }
    }

    internal fun requestOrderlyShutdown(command: OrderlyShutdownCommand): Boolean {
        val capability = command as? GlOrderlyShutdownCapability ?: return false
        if (!capability.claimFor(this)) return false
        val stillEligible = glGate.withLock {
            installedRenderTarget == null && contextRenderTarget == null &&
                    program.current == null && context == EGL14.EGL_NO_CONTEXT &&
                    pbuffer == EGL14.EGL_NO_SURFACE && contextNamespace == null &&
                    contextNamespaceTransferredTarget == null &&
                    partialStartupCleanupHandle == null && targetScopeNamespaceDestructionHandle == null &&
                    contextNamespaceDestructionHandle == null &&
                    display == EGL14.EGL_NO_DISPLAY && config == null && !program.hasOwnedNames &&
                    laneRuntime.observedFatal == null && !laneRuntime.hasUnsettledOperation &&
                    !laneRuntime.hasActiveTeardownLocked()
        }
        if (!stillEligible) return false
        return laneRuntime.requestShutdown()
    }

    internal fun checkFatalFence() = laneRuntime.checkFatal()

    internal fun checkFatalLocked() = laneRuntime.checkFatalLocked()

    internal fun signalSettlement() = settlementSignal.signal()

    internal fun <R : OperationEvidence> endpointOperation(
        occurrence: OperationOccurrence<R>,
        runnable: Runnable,
    ): PrivateExecutorOperation<R> = laneRuntime.operation(occurrence, runnable)

    internal fun submit(
        operation: PrivateExecutorOperation<*>,
        teardownOwner: GlTeardownOwner? = null,
    ): PrivateExecutorSubmissionResult = laneRuntime.submit(operation, teardownOwner)

    internal fun releaseSettledOperation(operation: PrivateExecutorOperation<*>): Boolean =
        laneRuntime.releaseSettledOperation(operation)

    internal inline fun <T> outward(block: () -> T): T = laneRuntime.outward(block)

    internal inline fun <T> outwardAdopt(adopt: (T) -> Unit, block: () -> T): T = laneRuntime.outwardAdopt(adopt, block)

    internal fun claimTeardown(owner: GlTeardownOwner): Boolean = laneRuntime.claimTeardown(owner)

    internal fun ownsTeardown(owner: GlTeardownOwner): Boolean = laneRuntime.ownsTeardown(owner)

    internal fun ownsTeardownLocked(owner: GlTeardownOwner): Boolean = laneRuntime.ownsTeardownLocked(owner)

    internal fun releaseTeardown(owner: GlTeardownOwner): Boolean = laneRuntime.releaseTeardown(owner)

    internal fun releaseTeardownLocked(owner: GlTeardownOwner): Boolean = laneRuntime.releaseTeardownLocked(owner)

    internal fun requireCurrent(evidence: GlOperationEvidence): Boolean = eglRuntime.requireCurrent(evidence)

    internal fun glesGroup(evidence: GlOperationEvidence, commands: () -> Boolean): Boolean = eglRuntime.glesGroup(evidence, commands)

    internal fun glesGroupCompletion(
        evidence: GlOperationEvidence,
        commands: () -> Boolean,
    ): GlGlesGroupCompletion = eglRuntime.glesGroupCompletion(evidence, commands)

    internal fun glesGroupAfterCurrent(evidence: GlOperationEvidence, probeFacts: GlProbeFacts? = null, commands: () -> Boolean): Boolean =
        eglRuntime.glesGroupAfterCurrent(evidence, probeFacts, commands)

    internal fun glesGroup(evidence: GlDestructionEvidence, commands: () -> Boolean): Boolean = eglRuntime.glesGroup(evidence, commands)

    internal fun markContextUnknown() {
        recordContextIntegrity(ContextIntegrity.Unknown)
    }

    internal fun recordContextIntegrity(integrity: ContextIntegrity) {
        glGate.withLock {
            if (contextIntegrity == ContextIntegrity.PoisonedByOutOfMemory) return@withLock
            if (contextIntegrity == integrity) return@withLock
            contextIntegrity = integrity
            commitRenderCurrentnessMutationLocked()
        }
    }

    internal fun bindContextNamespace(graph: TargetRetirement.TargetScopeDestructionGraph): Boolean = glGate.withLock {
        checkFatalLocked()
        if (contextNamespace != null ||
            contextNamespaceTransferredTarget != null && contextNamespaceTransferredTarget !== graph
        ) {
            return@withLock false
        }
        contextNamespaceTransferredTarget = graph
        true
    }

    internal fun bindPartialStartupContextNamespace(namespace: ContextNamespace): Boolean = glGate.withLock {
        checkFatalLocked()
        if (!namespace.matches(this) || namespace.triggerKind != GlDestructionKind.ContextNamespace ||
            context == EGL14.EGL_NO_CONTEXT || contextNamespaceTransferredTarget != null ||
            contextNamespace != null && contextNamespace !== namespace
        ) {
            return@withLock false
        }
        contextNamespace = namespace
        true
    }

    internal fun retainTargetScopeNamespaceHandle(handle: GlTargetScopeDestructionHandle): Boolean = glGate.withLock {
        val existing = targetScopeNamespaceDestructionHandle
        if (partialStartupCleanupHandle != null || contextNamespaceDestructionHandle != null ||
            existing != null && existing !== handle
        ) {
            return@withLock false
        }
        targetScopeNamespaceDestructionHandle = handle
        true
    }

    internal fun releaseTargetScopeNamespaceHandle(handle: GlTargetScopeDestructionHandle): Boolean = glGate.withLock {
        if (targetScopeNamespaceDestructionHandle !== handle) return@withLock false
        targetScopeNamespaceDestructionHandle = null
        true
    }

    internal fun retainPartialStartupCleanupHandle(handle: GlSessionConstructionHandle): Boolean = glGate.withLock {
        val existing = partialStartupCleanupHandle
        if (targetScopeNamespaceDestructionHandle != null || contextNamespaceDestructionHandle != null ||
            existing != null && existing !== handle
        ) {
            return@withLock false
        }
        partialStartupCleanupHandle = handle
        true
    }

    internal fun activateAndBindProgramContextNamespace(namespace: ContextNamespace): Boolean = glGate.withLock {
        checkFatalLocked()
        if (!namespace.matches(this)) return@withLock false
        if (!ContextNamespace.activateProgramLocked(namespace, contextIntegrity)) return@withLock false
        val existing = contextNamespace
        if (existing != null && existing !== namespace) return@withLock false
        contextNamespace = namespace
        true
    }

    internal fun closeNormalResult(evidence: GlOperationEvidence) {
        if (evidence.result != null) return
        evidence.result = GlOperationResult.InternalFailure
        evidence.contextIntegrity = contextIntegrity
    }

    internal fun closeNormalResult(evidence: GlDestructionEvidence) {
        if (evidence.result != null) return
        evidence.result = GlOperationResult.InternalFailure
        evidence.contextIntegrity = contextIntegrity
    }

    internal fun prepareSessionConstruction(
        identity: GlFiniteOperationIdentity,
        partialCleanupIdentity: GlFiniteOperationIdentity,
    ): SessionConstructionCommand =
        GlSessionConstructionHandle(this, identity, partialCleanupIdentity)

    internal fun constructSession(evidence: GlOperationEvidence): Boolean {
        if (!eglRuntime.construct(evidence)) return false
        val precision = capabilityOwner.facts?.fragmentPrecision?.selectedPrecision ?: return false
        val constructed = program.construct(evidence, precision)
        if (constructed) commitRenderCurrentnessMutation()
        return constructed
    }

    internal fun queryCapabilities(evidence: GlOperationEvidence): Boolean =
        capabilityOwner.query(evidence)

    internal fun prepareSurfaceRelease(readinessFact: TargetSurfaceReleaseReadyFact): SurfaceReleaseCommand? {
        checkFatalFence()
        val target = readinessFact.targetIdentity.target
        val operation = target.prepareSurfaceReleaseOperation(readinessFact) ?: return null
        return GlSurfaceReleaseHandle(this, operation)
    }

    internal fun prepareCleanupSurfaceRelease(target: PreparedTarget): SurfaceReleaseCommand? {
        checkFatalFence()
        val operation = target.prepareCleanupSurfaceReleaseOperation() ?: return null
        return GlSurfaceReleaseHandle(this, operation)
    }

    internal fun prepareTargetConstruction(preparedTarget: PreparedTarget): TargetConstructionCommand =
        GlTargetConstructionHandle(this, preparedTarget)

    internal fun constructTarget(preparedTarget: PreparedTarget, localEvidence: GlOperationEvidence): Boolean {
        val plan = preparedTarget.plan
        val facts = capabilityOwner.facts ?: return false
        if (plan.targetWidthPx > facts.maxTextureSize || plan.targetHeightPx > facts.maxTextureSize ||
            plan.targetWidthPx > facts.maxViewportWidth || plan.targetHeightPx > facts.maxViewportHeight
        ) {
            return false
        }
        val oesConstructed = glesGroup(localEvidence) {
            checkFatalFence()
            GLES20.glGenTextures(1, generatedNames, 0)
            val oesTextureName = generatedNames[0]
            if (oesTextureName != 0) {
                check(preparedTarget.adoptOesTextureName(oesTextureName))
            }
            checkFatalFence()
            if (oesTextureName == 0) {
                return@glesGroup false
            }
            outward { GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureName) }
            outward { GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR) }
            outward { GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR) }
            outward { GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE) }
            outward { GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE) }
            true
        }
        if (!oesConstructed || localEvidence.contextIntegrity != ContextIntegrity.Intact) return false
        checkFatalFence()
        val surfaceTexture = outwardAdopt(adopt = { adopted -> check(preparedTarget.adoptSurfaceTexture(adopted)) }) {
            SurfaceTexture(generatedNames[0], false)
        }
        outward { surfaceTexture.setDefaultBufferSize(plan.targetWidthPx, plan.targetHeightPx) }
        checkFatalFence()
        check(preparedTarget.recordDefaultBufferSizeApplied())
        outwardAdopt(adopt = { adopted -> check(preparedTarget.adoptSurface(adopted)) }) {
            Surface(surfaceTexture)
        }
        return true
    }

    internal fun prepareRenderTargetConstruction(
        identity: GlFiniteOperationIdentity,
        destructionIdentity: GlFiniteOperationIdentity,
        renderGeneration: Long,
        compatibilityFacts: GlRenderTargetCompatibilityFacts,
        targetGeneration: Long,
        reconciliationFacts: GlFrameDesiredState,
    ): RenderTargetConstructionCommand? {
        val facts = capabilityOwner.facts ?: return null
        val size = compatibilityFacts.imageSize
        if (size.widthPx > facts.maxTextureSize || size.heightPx > facts.maxTextureSize ||
            size.widthPx > facts.maxViewportWidth || size.heightPx > facts.maxViewportHeight
        ) {
            return null
        }
        if (glGate.withLock { installedRenderTarget != null || contextRenderTarget != null } || laneRuntime.observedFatal != null) {
            return null
        }
        val candidate = GlRenderTargetOwnerImpl(renderGeneration, compatibilityFacts)
        val state = GlRenderTargetState.create(candidate, targetGeneration, reconciliationFacts)
        val destructionHandle = GlDestructionHandle(
            owner = this,
            identity = destructionIdentity,
            action = GlDestructionAction.RenderTarget,
            renderTarget = candidate,
            renderState = state,
            installedRenderTargetDestruction = false,
        )
        state.bindDestructionHandle(destructionHandle)
        val handle = GlRenderTargetConstructionHandle(this, identity, candidate, state)
        return handle.takeIf { it.claimContextOwnership() }
    }

    internal fun constructRenderTarget(state: GlRenderTargetState, evidence: GlOperationEvidence): Boolean = glesGroup(evidence) {
        if (glGate.withLock { installedRenderTarget != null }) return@glesGroup false
        val candidate = state.owner
        val size = candidate.compatibilityFacts.imageSize
        checkFatalFence()
        GLES20.glGenTextures(1, generatedNames, 0)
        val texture = generatedNames[0]
        if (texture != 0) state.textureName = texture
        checkFatalFence()
        if (texture == 0) return@glesGroup false
        outward { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture) }
        outward { GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST) }
        outward { GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST) }
        outward { GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE) }
        outward { GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE) }
        outward { GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size.widthPx, size.heightPx, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null) }
        checkFatalFence()
        GLES20.glGenFramebuffers(1, generatedNames, 0)
        val framebuffer = generatedNames[0]
        if (framebuffer != 0) state.framebufferName = framebuffer
        checkFatalFence()
        if (framebuffer == 0) return@glesGroup false
        outward { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer) }
        outward { GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0) }
        if (outward { GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) } != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            return@glesGroup false
        }
        true
    }

    internal fun prepareFrame(
        identity: GlFiniteOperationIdentity,
        target: CurrentTarget,
        renderTarget: GlRenderTargetOwner,
        product: JpegRuntimeProduct,
        lease: RgbaCarrierLease,
    ): FrameCommand? {
        val renderState = glGate.withLock {
            val state = installedRenderTarget ?: return@withLock null
            if (state.targetGeneration != target.generation) return@withLock null
            val actualState = state.actualStateLocked(this)
            if (actualState != null &&
                (actualState.targetIdentity !== target.identity || actualState.targetGeneration != target.generation)
            ) {
                return@withLock null
            }
            state
        } ?: return null
        checkFatalFence()
        if (renderState.owner !== renderTarget || product.carrier.byteCount != renderTarget.compatibilityFacts.rgbaByteCount ||
            lease.carrier !== product.carrier
        ) {
            return null
        }
        checkFatalFence()
        val targetPort = target.detachedGlFramePort(identity.operationIdentity) ?: return null
        val handle = GlFrameHandle(
            owner = this,
            identity = identity,
            target = target,
            targetPort = targetPort,
            renderTarget = renderTarget,
            renderState = renderState,
            lease = lease,
        )
        if (!lease.retainForOperation(product)) {
            checkFatalFence()
            return null
        }
        checkFatalFence()
        if (!target.commitGlFramePort(targetPort)) {
            checkFatalFence()
            check(lease.releaseFromOperation())
            checkFatalFence()
            return null
        }
        return handle
    }

    internal fun applyFrameReconciliation(
        command: GlFrameReconciliationApplyCommand,
    ): GlFrameReconciliationResult = command.invoke(this)

    internal fun applyAdmittedFrameReconciliation(
        command: GlFrameReconciliationApplyCommand,
        admittedFact: TargetRetainedGlAdmittedFact,
    ): GlFrameReconciliationResult = glGate.withLock {
        val state = installedRenderTarget
        if (state == null) {
            frameReconciliationEndpointPoisonedLocked()
            return@withLock command.rejectedResult(GlFrameReconciliationRejectionReason.PipelineClosed)
        }
        state.applyFrameReconciliationLocked(this, command, admittedFact)
    }

    internal fun renderFrame(
        targetPort: TargetPorts.GlFramePort,
        renderState: GlRenderTargetState,
        lease: RgbaCarrierLease,
        evidence: GlOperationEvidence,
        stateProbeFacts: GlProbeFacts,
        drawReadProbeFacts: GlProbeFacts,
        colorActionFacts: GlColorActionCell,
    ): Boolean = framePipeline.render(targetPort, renderState, lease, evidence, stateProbeFacts, drawReadProbeFacts, colorActionFacts)

    internal fun prepareRenderTargetDestruction(renderTarget: GlRenderTargetOwner): DestructionCommand? = glGate.withLock {
        val state = installedRenderTarget ?: return@withLock null
        if (state.owner !== renderTarget) return@withLock null
        state.claimDestruction(this, installed = true)
    }

    internal fun prepareProgramDestruction(identity: GlFiniteOperationIdentity): DestructionCommand? {
        glGate.withLock {
            if (!program.hasOwnedNames) return null
            programDestructionHandle?.let { return it }
        }
        val integrity = glGate.withLock { contextIntegrity }
        val namespaceCandidate = ContextNamespace.createForProgram(this, identity.operationIdentity)
        val namespace = if (integrity == ContextIntegrity.Intact) {
            null
        } else {
            namespaceCandidate
        }
        val handle = GlDestructionHandle(
            owner = this,
            identity = identity,
            action = if (namespace == null) GlDestructionAction.Program else GlDestructionAction.ProgramNamespace,
            renderTarget = null,
            renderState = null,
            installedRenderTargetDestruction = false,
            contextNamespace = namespace,
            programFallbackNamespace = namespaceCandidate,
            terminalCleanupOrigin = GlTerminalCleanupOrigin.TerminalOrigin,
        )
        return glGate.withLock {
            val existing = programDestructionHandle
            if (existing != null) return@withLock existing
            if (!program.hasOwnedNames || contextIntegrity != integrity ||
                namespace == null && contextNamespace != null ||
                namespace != null && contextNamespace != null && contextNamespace !== namespace
            ) {
                return@withLock null
            }
            if (namespace != null) {
                if (!ContextNamespace.activateProgramLocked(namespace, integrity)) return@withLock null
                contextNamespace = namespace
            }
            handle.also { programDestructionHandle = it }
        }
    }

    internal fun clearProgramDestruction(handle: GlDestructionHandle): Boolean = glGate.withLock {
        if (programDestructionHandle !== handle || program.hasOwnedNames) return@withLock false
        programDestructionHandle = null
        true
    }

    internal fun destroyProgram(evidence: GlDestructionEvidence): Boolean {
        val hadCurrentProgram = glGate.withLock { program.current != null }
        val destroyed = program.destroy(evidence)
        if (destroyed && hadCurrentProgram) commitRenderCurrentnessMutation()
        return destroyed
    }

    internal fun prepareHealthySessionDestruction(identity: GlFiniteOperationIdentity): DestructionCommand? {
        val namespace = glGate.withLock { contextNamespace }
        val handle = GlDestructionHandle(
            owner = this,
            identity = identity,
            action = if (namespace == null) GlDestructionAction.Session else GlDestructionAction.ContextNamespace,
            renderTarget = null,
            renderState = null,
            installedRenderTargetDestruction = false,
            contextNamespace = namespace,
            terminalCleanupOrigin = GlTerminalCleanupOrigin.TerminalOrigin,
        )
        return glGate.withLock {
            val healthyEligible = namespace == null && contextIntegrity == ContextIntegrity.Intact && !program.hasOwnedNames
            val namespaceEligible = namespace != null && contextNamespace === namespace &&
                    namespace.matches(this)
            if ((!healthyEligible && !namespaceEligible) ||
                healthyEligible && (installedRenderTarget != null || contextRenderTarget != null) ||
                contextNamespaceTransferredTarget != null ||
                display == EGL14.EGL_NO_DISPLAY || config == null || context == EGL14.EGL_NO_CONTEXT ||
                pbuffer == EGL14.EGL_NO_SURFACE || contextUnbound || contextDestroyed ||
                pbufferDestroyed || threadReleased
            ) {
                return@withLock false
            }
            if (!handle.claimSessionTeardown()) return@withLock false
            if (namespace != null) contextNamespaceDestructionHandle = handle
            true
        }.let { claimed -> handle.takeIf { claimed } }
    }

    internal fun destroyRenderTarget(state: GlRenderTargetState, installed: Boolean, evidence: GlDestructionEvidence): Boolean {
        val currentState = glGate.withLock { installedRenderTarget }
        if (installed && currentState !== state || !installed && currentState === state) return false
        val deleted = glesGroup(evidence) {
            if (state.framebufferName != 0) {
                outward { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) }
                generatedNames[0] = state.framebufferName
                outward { GLES20.glDeleteFramebuffers(1, generatedNames, 0) }
            }
            if (state.textureName != 0) {
                outward { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0) }
                generatedNames[0] = state.textureName
                outward { GLES20.glDeleteTextures(1, generatedNames, 0) }
            }
            true
        }
        if (deleted) {
            glGate.withLock {
                checkFatalLocked()
                state.framebufferName = 0
                state.textureName = 0
                if (contextRenderTarget === state) contextRenderTarget = null
                commitRenderCurrentnessMutationLocked()
            }
        }
        return deleted
    }

    internal fun destroyHealthySession(teardownOwner: GlTeardownOwner, evidence: GlDestructionEvidence): Boolean {
        val contextRetired = unbindAndDestroyContext(teardownOwner, evidence)
        val suffixRetired = if (contextUnbound) {
            destroyPbufferAndReleaseThread()
        } else {
            recordUnreachableSuffixResidue()
            false
        }
        if (contextRetired && !suffixRetired) {
            evidence.result = GlOperationResult.InternalFailure
            evidence.contextIntegrity = contextIntegrity
        } else if (contextRetired) {
            evidence.contextIntegrity = ContextIntegrity.Intact
        }
        return contextRetired && suffixRetired
    }

    internal fun prepareTargetScopeDestruction(
        target: CurrentTarget,
        targetIdentity: GlFiniteOperationIdentity,
        namespaceIdentity: GlFiniteOperationIdentity,
    ): TargetScopeDestructionCommand? {
        val graph = target.prepareTargetScopeDestructionGraph(targetIdentity, namespaceIdentity) ?: return null
        return GlTargetScopeDestructionHandle(this, graph)
    }

    internal fun prepareCleanupTargetScopeDestruction(
        target: PreparedTarget,
        targetIdentity: GlFiniteOperationIdentity,
        namespaceIdentity: GlFiniteOperationIdentity,
    ): TargetScopeDestructionCommand? {
        val graph = target.prepareTargetScopeDestructionGraph(targetIdentity, namespaceIdentity) ?: return null
        return GlTargetScopeDestructionHandle(this, graph)
    }

    internal fun consumeContextNamespaceLocked(graph: TargetRetirement.TargetScopeDestructionGraph): Boolean {
        check(glGate.isHeldByCurrentThread)
        checkFatalLocked()
        if (contextNamespace != null || contextNamespaceTransferredTarget !== graph ||
            context != EGL14.EGL_NO_CONTEXT || !contextDestroyed
        ) {
            return false
        }
        val contextState = contextRenderTarget
        val installedState = installedRenderTarget
        if (contextState != null && !contextState.canConsumeNamespaceLocked(this)) return false
        if (installedState != null && installedState !== contextState && !installedState.canConsumeNamespaceLocked(this)) {
            return false
        }
        if (contextState != null && !contextState.consumeNamespaceLocked(this)) return false
        if (installedState != null && installedState !== contextState && !installedState.consumeNamespaceLocked(this)) {
            return false
        }
        installedRenderTarget = null
        contextRenderTarget = null
        program.consumeNamespaceLocked()
        programDestructionHandle = null
        contextNamespaceTransferredTarget = null
        contextNamespace = null
        commitRenderCurrentnessMutationLocked()
        return true
    }

    internal fun consumePartialContextNamespaceLocked(
        expectedNamespace: ContextNamespace? = null,
        authorizedPhysicalRetirementEvidence: GlDestructionEvidence? = null,
    ): Boolean {
        check(glGate.isHeldByCurrentThread)
        val authorizedPhysicalRetirement = if (authorizedPhysicalRetirementEvidence != null) {
            val exactNamespace = expectedNamespace ?: return false
            if (!authorizedPhysicalRetirementEvidence.canonicalizeRetiredOwnerContextLocked(this, exactNamespace)) {
                return false
            }
            true
        } else {
            false
        }
        if (!authorizedPhysicalRetirement) checkFatalLocked()
        if (authorizedPhysicalRetirement && expectedNamespace != null && contextNamespace == null) {
            return installedRenderTarget == null && contextRenderTarget == null &&
                    program.current == null && !program.hasOwnedNames && programDestructionHandle == null &&
                    context == EGL14.EGL_NO_CONTEXT && contextDestroyed &&
                    contextNamespaceTransferredTarget == null
        }
        if (context != EGL14.EGL_NO_CONTEXT || !contextDestroyed || contextNamespaceTransferredTarget != null) {
            return false
        }
        if (expectedNamespace != null && contextNamespace !== expectedNamespace ||
            expectedNamespace == null && contextNamespace != null
        ) {
            return false
        }
        val contextState = contextRenderTarget
        val installedState = installedRenderTarget
        if (contextState != null && !contextState.canConsumeNamespaceLocked(this)) return false
        if (installedState != null && installedState !== contextState && !installedState.canConsumeNamespaceLocked(this)) {
            return false
        }
        if (contextState != null && !contextState.consumeNamespaceLocked(this, authorizedPhysicalRetirement)) return false
        if (installedState != null && installedState !== contextState &&
            !installedState.consumeNamespaceLocked(this, authorizedPhysicalRetirement)
        ) {
            return false
        }
        installedRenderTarget = null
        contextRenderTarget = null
        program.consumeNamespaceLocked(authorizedPhysicalRetirement)
        programDestructionHandle = null
        commitRenderCurrentnessMutationLocked()
        contextNamespace = null
        return true
    }

    internal fun reduceStructurallyAbsentPartialContextLocked(): Boolean {
        check(glGate.isHeldByCurrentThread)
        checkFatalLocked()
        if (context != EGL14.EGL_NO_CONTEXT || pbuffer != EGL14.EGL_NO_SURFACE ||
            !contextDestroyed || !contextUnbound ||
            contextNamespace != null || contextNamespaceTransferredTarget != null ||
            installedRenderTarget != null || contextRenderTarget != null ||
            program.current != null || program.hasOwnedNames
        ) {
            return false
        }
        return true
    }

    internal fun unbindAndDestroyContext(teardownOwner: GlTeardownOwner, evidence: GlDestructionEvidence): Boolean =
        eglRuntime.unbindAndDestroyContext(teardownOwner, evidence)

    internal fun destroyPbufferAndReleaseThread(): Boolean = eglRuntime.destroyPbufferAndReleaseThread()

    internal fun recordUnreachableSuffixResidue() = eglRuntime.recordUnreachableSuffixResidue()

    internal fun claimOperation(
        occurrence: OperationOccurrence<GlOperationEvidence>,
        facts: GlClaimedOperationFacts,
        colorActionFacts: GlColorActionFacts? = null,
        stateProbeFacts: GlProbeFacts? = null,
        drawReadProbeFacts: GlProbeFacts? = null,
    ): GlClaimedOperationFacts? = claimArbitrator.claimOperation(occurrence, facts, colorActionFacts, stateProbeFacts, drawReadProbeFacts)

    internal fun claimDestruction(occurrence: OperationOccurrence<GlDestructionEvidence>, facts: GlClaimedDestructionFacts): GlClaimedDestructionFacts? =
        claimArbitrator.claimDestruction(occurrence, facts)

    internal fun operationOccurrence(
        identity: GlFiniteOperationIdentity,
        evidence: GlOperationEvidence,
        ownerBag: GlOwnerBag,
    ): OperationOccurrence<GlOperationEvidence> = OperationOccurrence(
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

    internal fun destructionOccurrence(
        identity: GlFiniteOperationIdentity,
        evidence: GlDestructionEvidence,
        ownerBag: GlOwnerBag,
    ): OperationOccurrence<GlDestructionEvidence> = OperationOccurrence(
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
}
