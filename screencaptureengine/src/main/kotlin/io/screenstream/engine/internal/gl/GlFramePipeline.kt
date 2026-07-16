package io.screenstream.engine.internal.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import io.screenstream.engine.ColorMode
import io.screenstream.engine.internal.RgbaCarrierLease
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetPorts
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ScheduledExecutorService
import kotlin.concurrent.withLock

internal enum class GlColorClassification {
    Srgb,
    DisplayP3,
    Scrgb,
    HdrTransfer,
    NominalSdr,
}

internal class GlFrameHandle internal constructor(
    private val owner: GlPipelineOwner,
    identity: GlFiniteOperationIdentity,
    private val target: CurrentTarget,
    private val targetPort: TargetPorts.GlFramePort,
    private val renderTarget: GlPipelineOwner.GlRenderTargetOwner,
    private val renderState: GlRenderTargetState,
    private val lease: RgbaCarrierLease,
) : GlPipelineOwner.FrameCommand {
    private val evidence: GlOperationEvidence = GlOperationEvidence(identity.operationIdentity, GlOperationKind.Frame)
    private val ownerBag: GlOwnerBag = GlOwnerBag(renderTarget)
    private val occurrence: OperationOccurrence<GlOperationEvidence> = owner.operationOccurrence(identity, evidence, ownerBag)
    private val claimedFacts: GlClaimedOperationFacts = GlClaimedOperationFacts.precreate(evidence)
    private val runnable: Runnable = owner.fatalBoundary { execute() }
    private val laneTicket: GlLaneTicket = GlLaneTicket()
    private var leaseReleased: Boolean = false
    private val colorActionFacts: GlColorActionCell = GlColorActionCell()
    private val stateProbeFacts: GlProbeFacts = GlProbeFacts()
    private val drawReadProbeFacts: GlProbeFacts = GlProbeFacts()

    private fun execute() {
        if (!owner.enter(laneTicket, occurrence)) {
            releaseDependenciesAfterSettlement()
            owner.signalSettlement()
            return
        }
        try {
            if (owner.renderFrame(
                    targetPort = targetPort,
                    renderState = renderState,
                    lease = lease,
                    evidence = evidence,
                    stateProbeFacts = stateProbeFacts,
                    drawReadProbeFacts = drawReadProbeFacts,
                    colorActionFacts = colorActionFacts,
                )
            ) {
                owner.checkFatalFence()
                evidence.result = GlOperationResult.Success
            }
            owner.checkFatalFence()
            owner.closeNormalResult(evidence)
            if (!releaseCarrierLease()) {
                owner.finishLaneReturn(laneTicket)
                return
            }
            owner.checkFatalFence()
            occurrence.publishNormalReturn()
            target.retireGlFramePortAfterSettlement(targetPort, occurrence)
        } catch (exception: Exception) {
            owner.checkFatalFence()
            evidence.throwable = exception
            evidence.result = GlOperationResult.InternalFailure
            evidence.contextIntegrity = owner.contextIntegrity
            if (!releaseCarrierLease()) {
                owner.finishLaneReturn(laneTicket)
                return
            }
            owner.checkFatalFence()
            occurrence.publishThrownReturn(exception)
            target.retireGlFramePortAfterSettlement(targetPort, occurrence)
        }
        owner.finishLaneReturn(laneTicket)
    }

    private fun releaseCarrierLease(): Boolean {
        if (owner.glGate.withLock { leaseReleased }) return true
        if (!lease.releaseFromOperation()) return false
        owner.glGate.withLock {
            check(!leaseReleased)
            leaseReleased = true
        }
        return true
    }

    private fun releaseDependenciesAfterSettlement(): Boolean {
        val leaseSettled = releaseCarrierLease()
        val targetSettled = target.retireGlFramePortAfterSettlement(targetPort, occurrence)
        return leaseSettled && targetSettled
    }

    private fun releaseCancelledDependenciesAfterFatal(error: Error) {
        try {
            if (owner.laneRuntime.canReleaseCancelledDependenciesAfterFatal(laneTicket, error)) {
                releaseDependenciesAfterSettlement()
                owner.signalSettlement()
            }
        } catch (_: Throwable) {
            // The original fatal remains authoritative.
        }
    }

    override fun submit(): Boolean = owner.laneRuntime.submissionBoundary {
        val submitted = try {
            owner.submit(laneTicket, occurrence, runnable)
        } catch (error: Error) {
            releaseCancelledDependenciesAfterFatal(error)
            throw error
        }
        val releaseDependencies = !submitted && owner.glGate.withLock {
            owner.laneRuntime.isTerminalNoExecutorSubmissionLocked(laneTicket)
        }
        if (releaseDependencies) {
            releaseDependenciesAfterSettlement()
            owner.signalSettlement()
        }
        submitted
    }

    internal fun admitLane(): Boolean = owner.laneRuntime.issue(laneTicket)

    internal fun abandonDetachedLane() = owner.laneRuntime.cancelWithoutExecutorCall(laneTicket)

    internal fun checkPreparedFatal() = owner.checkFatalForPrepared(laneTicket)

    override fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean = occurrence.submitRequestedDeadlineWake(scheduler)

    override fun performRequestedDeadlineCancellation(): Boolean = occurrence.performRequestedDeadlineCancellation()

    override fun claim(): GlClaimedOperationFacts? {
        val facts = owner.claimOperation(
            occurrence = occurrence,
            facts = claimedFacts,
            colorActionFacts = colorActionFacts.value,
            stateProbeFacts = stateProbeFacts,
            drawReadProbeFacts = drawReadProbeFacts,
        )
        owner.retireMechanicallyCompleteLane(laneTicket, occurrence)
        return facts
    }
}

internal class GlColorActionFacts internal constructor(
    internal val targetGeneration: Long,
    internal val classification: GlColorClassification,
    internal val colorMode: ColorMode,
) {
    init {
        require(targetGeneration > 0L)
    }
}

internal class GlFrameReconciliationFacts internal constructor(
    logicalInverseTransform: FloatArray,
    internal val colorMode: ColorMode,
) {
    private val ownedLogicalInverseTransform: FloatArray = logicalInverseTransform.copyOf()

    init {
        require(ownedLogicalInverseTransform.size == 16)
        require(ownedLogicalInverseTransform.all(Float::isFinite))
    }

    internal fun copyLogicalInverseTransformTo(destination: FloatArray): Boolean {
        if (destination.size != ownedLogicalInverseTransform.size) return false
        ownedLogicalInverseTransform.copyInto(destination)
        return true
    }
}

internal class GlProbeFacts internal constructor() {
    internal var preprobeErrorCode: Int = 0
        private set
    internal var postprobeErrorCode: Int = 0
        private set
    internal var preprobePresent: Boolean = false
        private set
    internal var postprobePresent: Boolean = false
        private set
    private var frozen: Boolean = false

    internal fun recordPreprobe(error: Int) {
        check(!frozen && !preprobePresent)
        preprobeErrorCode = error
        preprobePresent = true
    }

    internal fun recordPostprobe(error: Int) {
        check(!frozen && preprobePresent && !postprobePresent)
        postprobeErrorCode = error
        postprobePresent = true
        frozen = true
    }

    internal fun freezeAfterPreprobe() {
        check(!frozen && preprobePresent)
        frozen = true
    }
}

internal class GlColorActionCell internal constructor() {
    internal var value: GlColorActionFacts? = null
}

internal class GlFramePipeline internal constructor(
    private val owner: GlPipelineOwner,
) {
    private val oesTransform: FloatArray = FloatArray(16)
    private val positionBuffer: FloatBuffer = directFloatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val textureCoordinateBuffer: FloatBuffer = directFloatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    internal fun render(
        targetPort: TargetPorts.GlFramePort,
        renderState: GlRenderTargetState,
        lease: RgbaCarrierLease,
        evidence: GlOperationEvidence,
        stateProbeFacts: GlProbeFacts,
        drawReadProbeFacts: GlProbeFacts,
        colorActionFacts: GlColorActionCell,
    ): Boolean {
        if (!owner.isInstalledRenderState(renderState)) return false
        val renderTarget = renderState.owner
        val program = owner.currentProgram ?: return false
        var targetHandlesEntered = false
        var exactRange: ByteBuffer? = null
        var rangeExited = false
        if (!owner.requireCurrent(evidence)) return false
        try {
            owner.checkFatalFence()
            val targetUpdated = targetPort.withHandles { surfaceTexture, oesTextureName ->
                targetHandlesEntered = true
                try {
                    owner.outward { surfaceTexture.updateTexImage() }
                } catch (exception: Exception) {
                    owner.markContextUnknown()
                    evidence.throwable = exception
                    evidence.result = GlOperationResult.InternalFailure
                    evidence.contextIntegrity = ContextIntegrity.Unknown
                    throw exception
                }
                owner.outward { surfaceTexture.getTransformMatrix(oesTransform) }
                for (index in oesTransform.indices) {
                    if (!oesTransform[index].isFinite()) {
                        evidence.result = GlOperationResult.InternalFailure
                        evidence.contextIntegrity = ContextIntegrity.Intact
                        return@withHandles
                    }
                }
                val dataSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    owner.outward { surfaceTexture.dataSpace }
                } else {
                    0
                }
                val colorAction = renderState.actionFor(dataSpace)
                val changedColorAction = renderState.lastColorAction !== colorAction
                if (!owner.glesGroupAfterCurrent(evidence, stateProbeFacts) {
                        owner.outward { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderState.framebufferName) }
                        owner.outward { GLES20.glUseProgram(program.programName) }
                        val size = renderTarget.compatibilityFacts.imageSize
                        owner.outward { GLES20.glViewport(0, 0, size.widthPx, size.heightPx) }
                        owner.outward { GLES20.glActiveTexture(GLES20.GL_TEXTURE0) }
                        owner.outward { GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureName) }
                        owner.outward { GLES20.glUniform1i(program.samplerLocation, 0) }
                        owner.outward { GLES20.glUniformMatrix4fv(program.oesMatrixLocation, 1, false, oesTransform, 0) }
                        owner.outward { GLES20.glUniformMatrix4fv(program.imageMatrixLocation, 1, false, renderState.logicalInverseTransform, 0) }
                        owner.outward {
                            GLES20.glUniform1f(
                                program.colorActionLocation,
                                if (colorAction.classification == GlColorClassification.DisplayP3) 1f else 0f
                            )
                        }
                        owner.outward { GLES20.glUniform1f(program.grayscaleLocation, if (colorAction.colorMode == ColorMode.Grayscale) 1f else 0f) }
                        positionBuffer.position(0)
                        textureCoordinateBuffer.position(0)
                        owner.outward { GLES20.glVertexAttribPointer(POSITION_ATTRIBUTE, 2, GLES20.GL_FLOAT, false, 0, positionBuffer) }
                        owner.outward { GLES20.glVertexAttribPointer(TEX_COORD_ATTRIBUTE, 2, GLES20.GL_FLOAT, false, 0, textureCoordinateBuffer) }
                        owner.outward { GLES20.glEnableVertexAttribArray(POSITION_ATTRIBUTE) }
                        owner.outward { GLES20.glEnableVertexAttribArray(TEX_COORD_ATTRIBUTE) }
                        owner.outward { GLES20.glColorMask(true, true, true, true) }
                        owner.outward { GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1) }
                        owner.outward { GLES20.glDisable(GLES20.GL_BLEND) }
                        owner.outward { GLES20.glDisable(GLES20.GL_DEPTH_TEST) }
                        owner.outward { GLES20.glDisable(GLES20.GL_STENCIL_TEST) }
                        owner.outward { GLES20.glDisable(GLES20.GL_SCISSOR_TEST) }
                        owner.outward { GLES20.glDisable(GLES20.GL_CULL_FACE) }
                        owner.outward { GLES20.glDisable(GLES20.GL_DITHER) }
                        true
                    }
                ) {
                    return@withHandles
                }
                owner.checkFatalFence()
                val carrier = lease.enterExactRange() ?: return@withHandles
                exactRange = carrier
                if (!owner.glesGroupAfterCurrent(evidence, drawReadProbeFacts) {
                        val size = renderTarget.compatibilityFacts.imageSize
                        owner.outward { GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4) }
                        owner.outward { GLES20.glReadPixels(0, 0, size.widthPx, size.heightPx, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, carrier) }
                        true
                    }
                ) {
                    owner.checkFatalFence()
                    check(lease.exitExactRange())
                    rangeExited = true
                    return@withHandles
                }
                owner.checkFatalFence()
                check(lease.exitExactRange())
                rangeExited = true
                if (changedColorAction) {
                    owner.checkFatalFence()
                    renderState.lastColorAction = colorAction
                    colorActionFacts.value = colorAction
                }
            }
            owner.checkFatalFence()
            return targetUpdated && targetHandlesEntered && exactRange != null && rangeExited && evidence.contextIntegrity == ContextIntegrity.Intact
        } catch (error: Error) {
            throw error
        } catch (exception: Exception) {
            if (exactRange != null && !rangeExited) {
                owner.checkFatalFence()
                check(lease.exitExactRange())
            }
            throw exception
        }
    }

    private companion object {
        private const val POSITION_ATTRIBUTE: Int = 0
        private const val TEX_COORD_ATTRIBUTE: Int = 1

        private fun directFloatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
