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
import io.screenstream.engine.internal.JpegRuntimeProduct
import io.screenstream.engine.internal.RgbaCarrierLease
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.PreparedTarget
import io.screenstream.engine.internal.target.TargetPorts
import io.screenstream.engine.internal.target.TargetRetirement
import java.util.concurrent.ScheduledExecutorService
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
        fun submit(): Boolean
        fun submitPartialCleanup(): Boolean
        fun claimPartialCleanup(): GlClaimedDestructionFacts?
        fun submitRequestedPartialCleanupDeadlineWake(scheduler: ScheduledExecutorService): Boolean
        fun performRequestedPartialCleanupDeadlineCancellation(): Boolean
        fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean
        fun performRequestedDeadlineCancellation(): Boolean
        fun claim(): GlClaimedOperationFacts?
    }

    internal interface SurfaceReleaseCommand {
        fun submit(): Boolean
        fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean
        fun performRequestedDeadlineCancellation(): Boolean
        fun claim(): TargetSurfaceReleaseClaim?
    }

    internal interface TargetConstructionCommand {
        fun submit(): Boolean
        fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean
        fun performRequestedDeadlineCancellation(): Boolean
        fun retireAfterTargetArbitration()
    }

    internal interface RenderTargetConstructionCommand {
        fun submit(): Boolean
        fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean
        fun performRequestedDeadlineCancellation(): Boolean
        fun claim(): GlClaimedOperationFacts?
        fun prepareCleanupDestruction(): DestructionCommand?
    }

    internal interface FrameCommand {
        fun submit(): Boolean
        fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean
        fun performRequestedDeadlineCancellation(): Boolean
        fun claim(): GlClaimedOperationFacts?
    }

    internal interface DestructionCommand {
        fun submit(): Boolean
        fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean
        fun performRequestedDeadlineCancellation(): Boolean
        fun claim(): GlClaimedDestructionFacts?
    }

    internal interface TargetScopeDestructionCommand {
        fun submit(): Boolean
        fun claim(): GlClaimedDestructionFacts?
        fun submitNamespaceRetirement(): Boolean
        fun claimNamespaceRetirement(): GlClaimedDestructionFacts?
        fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean
        fun submitRequestedNamespaceDeadlineWake(scheduler: ScheduledExecutorService): Boolean
        fun performRequestedDeadlineCancellation(): Boolean
        fun performRequestedNamespaceDeadlineCancellation(): Boolean
    }

    internal val glGate: ReentrantLock = ReentrantLock(false)
    internal var installedRenderTarget: GlRenderTargetState? = null
    internal var contextRenderTarget: GlRenderTargetState? = null

    internal var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    internal var config: EGLConfig? = null
    internal var context: EGLContext = EGL14.EGL_NO_CONTEXT
    internal var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    internal var contextIntegrity: ContextIntegrity = ContextIntegrity.Unknown
    internal var lastGroupCleanPostprobe: Boolean = false
    private var capabilities: GlCapabilityFacts? = null
    private val capabilityCandidate: GlCapabilityFacts = GlCapabilityFacts()
    private var stagedPrecision: GlFragmentPrecision? = null
    private val program: GlProgram = GlProgram(this)
    private val framePipeline: GlFramePipeline = GlFramePipeline(this)
    private val eglRuntime: GlEglRuntime = GlEglRuntime(this)
    private val claimArbitrator: GlClaimArbitrator = GlClaimArbitrator(this)
    internal var contextNamespaceTransferredTarget: TargetRetirement.TargetScopeDestructionGraph? = null
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

    private val maxTextureSize: IntArray = IntArray(1)
    private val maxViewportDims: IntArray = IntArray(2)
    private val highFloatRange: IntArray = IntArray(2)
    private val highFloatPrecision: IntArray = IntArray(1)
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

    internal fun isInstalledRenderState(state: GlRenderTargetState): Boolean = glGate.withLock { installedRenderTarget === state }

    internal val capabilityFacts: GlCapabilityFacts?
        get() = capabilities

    internal val observedFatalError: Error?
        get() = laneRuntime.observedFatalError

    internal val isExecutorTerminated: Boolean
        get() = laneRuntime.isExecutorTerminated

    private val orderlyShutdownCapability: GlOrderlyShutdownCapability = GlOrderlyShutdownCapability(this)
    internal val laneRuntime: GlLaneRuntime = GlLaneRuntime(glGate, settlementSignal, threadName)

    internal fun prepareOrderlyShutdown(): OrderlyShutdownCommand? = glGate.withLock {
        if (installedRenderTarget != null || contextRenderTarget != null ||
            program.current != null || context != EGL14.EGL_NO_CONTEXT ||
            pbuffer != EGL14.EGL_NO_SURFACE || contextNamespaceTransferredTarget != null ||
            display != EGL14.EGL_NO_DISPLAY || config != null || program.hasOwnedNames ||
            laneRuntime.observedFatalError != null
        ) {
            return@withLock null
        }
        if (!laneRuntime.prepareShutdownLocked()) return@withLock null
        orderlyShutdownCapability
    }

    internal fun requestOrderlyShutdown(command: OrderlyShutdownCommand): Boolean {
        val capability = command as? GlOrderlyShutdownCapability ?: return false
        if (!capability.claimFor(this)) return false
        val stillEligible = glGate.withLock {
            installedRenderTarget == null && contextRenderTarget == null &&
                    program.current == null && context == EGL14.EGL_NO_CONTEXT &&
                    pbuffer == EGL14.EGL_NO_SURFACE && contextNamespaceTransferredTarget == null &&
                    display == EGL14.EGL_NO_DISPLAY && config == null && !program.hasOwnedNames &&
                    laneRuntime.observedFatalError == null
        }
        if (!stillEligible) return false
        return laneRuntime.requestShutdown()
    }

    private fun publishFatal(error: Error) = laneRuntime.publishFatal(error)

    internal fun checkFatalFence() = laneRuntime.checkFatal()

    internal fun checkFatalLocked() = laneRuntime.checkFatalLocked()

    internal fun checkFatalForPrepared(ticket: GlLaneTicket) = laneRuntime.checkFatalForPrepared(ticket)

    internal fun signalSettlement() = settlementSignal.signal()

    internal fun submit(ticket: GlLaneTicket, occurrence: OperationOccurrence<*>, runnable: Runnable): Boolean =
        laneRuntime.submit(ticket, occurrence, runnable)

    internal fun fatalBoundary(body: () -> Unit): Runnable = laneRuntime.fatalBoundary(body)

    internal inline fun <T> outward(block: () -> T): T = laneRuntime.outward(block)

    internal inline fun <T> outwardAdopt(adopt: (T) -> Unit, block: () -> T): T = laneRuntime.outwardAdopt(adopt, block)

    internal fun enter(ticket: GlLaneTicket, occurrence: OperationOccurrence<*>): Boolean = laneRuntime.enter(ticket, occurrence)

    internal fun finishLaneReturn(ticket: GlLaneTicket, cleanupDependent: Boolean = false, signal: Boolean = true) =
        laneRuntime.finishReturn(ticket, cleanupDependent, signal)

    internal fun reserveTeardown(ticket: GlLaneTicket, allowDormantRender: Boolean = false): Boolean =
        laneRuntime.reserveTeardown(ticket, allowDormantRender)

    internal fun issueTeardown(ticket: GlLaneTicket, allowDormantRender: Boolean = false): Boolean = laneRuntime.issueTeardown(ticket, allowDormantRender)

    internal fun completeTeardownReservation(ticket: GlLaneTicket): Boolean = laneRuntime.completeTeardownReservation(ticket)

    internal fun completeTeardownReservationLocked(ticket: GlLaneTicket): Boolean = laneRuntime.completeTeardownReservationLocked(ticket)

    internal fun cancelUnusedTeardown(ticket: GlLaneTicket): Boolean = laneRuntime.cancelUnusedTeardown(ticket)

    internal fun ownsTeardownReservation(ticket: GlLaneTicket): Boolean = laneRuntime.ownsTeardownReservation(ticket)

    internal fun ownsTeardownReservationLocked(ticket: GlLaneTicket): Boolean = laneRuntime.ownsTeardownReservationLocked(ticket)

    internal fun retireReturnedLane(ticket: GlLaneTicket) = laneRuntime.retireReturned(ticket)

    internal fun retireMechanicallyCompleteLane(ticket: GlLaneTicket, occurrence: OperationOccurrence<*>, rejectionRetainsDependency: Boolean = false) =
        laneRuntime.retireMechanicallyComplete(ticket, occurrence, rejectionRetainsDependency)

    internal fun requireCurrent(evidence: GlOperationEvidence): Boolean = eglRuntime.requireCurrent(evidence)

    internal fun glesGroup(evidence: GlOperationEvidence, commands: () -> Boolean): Boolean = eglRuntime.glesGroup(evidence, commands)

    internal fun glesGroupAfterCurrent(evidence: GlOperationEvidence, probeFacts: GlProbeFacts? = null, commands: () -> Boolean): Boolean =
        eglRuntime.glesGroupAfterCurrent(evidence, probeFacts, commands)

    internal fun glesGroup(evidence: GlDestructionEvidence, commands: () -> Boolean): Boolean = eglRuntime.glesGroup(evidence, commands)

    internal fun markContextUnknown() {
        contextIntegrity = ContextIntegrity.Unknown
    }

    internal fun bindContextNamespace(graph: TargetRetirement.TargetScopeDestructionGraph): Boolean = glGate.withLock {
        checkFatalLocked()
        if (contextNamespaceTransferredTarget != null && contextNamespaceTransferredTarget !== graph) {
            return@withLock false
        }
        contextNamespaceTransferredTarget = graph
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
        GlSessionConstructionHandle(this, identity, partialCleanupIdentity).also {
            check(it.admitLane())
        }

    internal fun constructSession(evidence: GlOperationEvidence): Boolean {
        if (!eglRuntime.construct(evidence)) return false
        val precision = capabilities?.fragmentPrecision?.selectedPrecision ?: return false
        return program.construct(evidence, precision)
    }

    internal fun queryCapabilities(evidence: GlOperationEvidence): Boolean {
        stagedPrecision = null
        val clean = glesGroup(evidence) {
            outward { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0) }
            outward { GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewportDims, 0) }
            outward { GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, highFloatRange, 0, highFloatPrecision, 0) }
            val precision = when {
                highFloatRange[0] > 0 && highFloatRange[1] > 0 && highFloatPrecision[0] > 0 -> GlFragmentPrecision.Highp
                highFloatRange[0] == 0 && highFloatRange[1] == 0 && highFloatPrecision[0] == 0 -> GlFragmentPrecision.Mediump
                else -> return@glesGroup false
            }
            if (maxTextureSize[0] <= 0 || maxViewportDims[0] <= 0 || maxViewportDims[1] <= 0) {
                return@glesGroup false
            }
            stagedPrecision = precision
            true
        }
        if (!clean) {
            stagedPrecision = null
            return false
        }
        checkFatalFence()
        val precision = checkNotNull(stagedPrecision)
        check(
            capabilityCandidate.freeze(
                maxTextureSize = maxTextureSize[0],
                maxViewportWidth = maxViewportDims[0],
                maxViewportHeight = maxViewportDims[1],
                rangeLow = highFloatRange[0],
                rangeHigh = highFloatRange[1],
                precisionBits = highFloatPrecision[0],
                selectedPrecision = precision,
            )
        )
        capabilities = capabilityCandidate
        stagedPrecision = null
        return true
    }

    internal fun prepareSurfaceRelease(target: CurrentTarget): SurfaceReleaseCommand? =
        prepareSurfaceReleaseAccess(InstalledSurfaceReleaseAccess(target))

    internal fun prepareCleanupSurfaceRelease(target: PreparedTarget): SurfaceReleaseCommand? =
        prepareSurfaceReleaseAccess(CleanupSurfaceReleaseAccess(target))

    private fun prepareSurfaceReleaseAccess(access: GlSurfaceReleaseAccess): SurfaceReleaseCommand? {
        checkFatalFence()
        val port = access.detachedPort() ?: return null
        checkFatalFence()
        val handle = GlSurfaceReleaseHandle(this, access, port)
        if (!laneRuntime.issue(handle.laneTicket)) return null
        checkFatalForPrepared(handle.laneTicket)
        if (access.commitPort(port)) return handle
        checkFatalFence()
        laneRuntime.cancelWithoutExecutorCall(handle.laneTicket)
        return null
    }

    internal fun prepareTargetConstruction(preparedTarget: PreparedTarget): TargetConstructionCommand =
        GlTargetConstructionHandle(this, preparedTarget).also {
            check(it.admitLane())
        }

    internal fun constructTarget(preparedTarget: PreparedTarget, localEvidence: GlOperationEvidence): Boolean {
        val plan = preparedTarget.plan
        val facts = capabilities ?: return false
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
        reconciliationFacts: GlFrameReconciliationFacts,
    ): RenderTargetConstructionCommand? {
        val facts = capabilities ?: return null
        val size = compatibilityFacts.imageSize
        if (size.widthPx > facts.maxTextureSize || size.heightPx > facts.maxTextureSize ||
            size.widthPx > facts.maxViewportWidth || size.heightPx > facts.maxViewportHeight
        ) {
            return null
        }
        if (glGate.withLock { installedRenderTarget != null } || laneRuntime.observedFatalError != null) {
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
        return handle.takeIf { it.admitLane() }
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
        val renderState = glGate.withLock { installedRenderTarget } ?: return null
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
        if (!handle.admitLane()) return null
        handle.checkPreparedFatal()
        if (!lease.retainForOperation(product)) {
            checkFatalFence()
            handle.abandonDetachedLane()
            return null
        }
        try {
            checkFatalFence()
        } catch (error: Error) {
            check(lease.releaseFromOperation())
            handle.abandonDetachedLane()
            throw error
        }
        if (!target.commitGlFramePort(targetPort)) {
            checkFatalFence()
            check(lease.releaseFromOperation())
            checkFatalFence()
            handle.abandonDetachedLane()
            return null
        }
        return handle
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

    internal fun prepareProgramDestruction(identity: GlFiniteOperationIdentity): DestructionCommand? =
        if (program.hasOwnedNames) {
            GlDestructionHandle(
                this,
                identity,
                GlDestructionAction.Program,
                null,
                null,
                installedRenderTargetDestruction = false,
            ).takeIf { it.admitLane() }
        } else {
            null
        }

    internal fun destroyProgram(evidence: GlDestructionEvidence): Boolean = program.destroy(evidence)

    internal fun prepareHealthySessionDestruction(identity: GlFiniteOperationIdentity): DestructionCommand? {
        val handle = GlDestructionHandle(
            this,
            identity,
            GlDestructionAction.Session,
            null,
            null,
            installedRenderTargetDestruction = false,
        )
        return glGate.withLock {
            if (contextIntegrity != ContextIntegrity.Intact || installedRenderTarget != null ||
                contextRenderTarget != null || program.hasOwnedNames || contextNamespaceTransferredTarget != null ||
                display == EGL14.EGL_NO_DISPLAY || config == null || context == EGL14.EGL_NO_CONTEXT ||
                pbuffer == EGL14.EGL_NO_SURFACE || contextUnbound || contextDestroyed ||
                pbufferDestroyed || threadReleased || !handle.admitHealthyLaneLocked()
            ) {
                return@withLock null
            }
            handle
        }
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
            }
        }
        return deleted
    }

    internal fun destroyHealthySession(ticket: GlLaneTicket, evidence: GlDestructionEvidence): Boolean {
        val contextRetired = unbindAndDestroyContext(ticket, evidence)
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
        val handle = GlTargetScopeDestructionHandle(this, graph)
        return handle.takeIf { it.admitLane() }
    }

    internal fun prepareCleanupTargetScopeDestruction(
        target: PreparedTarget,
        targetIdentity: GlFiniteOperationIdentity,
        namespaceIdentity: GlFiniteOperationIdentity,
    ): TargetScopeDestructionCommand? {
        val graph = target.prepareTargetScopeDestructionGraph(targetIdentity, namespaceIdentity) ?: return null
        val handle = GlTargetScopeDestructionHandle(this, graph)
        return handle.takeIf { it.admitLane() }
    }

    internal fun consumeContextNamespaceLocked(graph: TargetRetirement.TargetScopeDestructionGraph): Boolean {
        check(glGate.isHeldByCurrentThread)
        checkFatalLocked()
        if (contextNamespaceTransferredTarget !== graph || context != EGL14.EGL_NO_CONTEXT || !contextDestroyed) {
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
        contextNamespaceTransferredTarget = null
        return true
    }

    internal fun consumePartialContextNamespaceLocked(): Boolean {
        check(glGate.isHeldByCurrentThread)
        checkFatalLocked()
        if (context != EGL14.EGL_NO_CONTEXT || !contextDestroyed || contextNamespaceTransferredTarget != null) {
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
        return true
    }

    internal fun unbindAndDestroyContext(ticket: GlLaneTicket, evidence: GlDestructionEvidence): Boolean = eglRuntime.unbindAndDestroyContext(ticket, evidence)

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
