package io.screenstream.capture.internal.capture

import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.isExactWritableRgbaCarrier
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import java.nio.ByteBuffer

/**
 * Sole physical owner of one session's projection, Capture lane, virtual display, target, EGL/GLES state, source
 * availability, direct readback, and retirement.
 *
 * Ordinary platform work is serialized on the Capture handler with at most one unresolved ordinary command. Posted
 * roots are installed before submission and retained until definite rejection or real entry. Results describe
 * owner-local physical settlement only; Session decides semantic currentness and lifecycle consequences after exact
 * Link correlation. Retirement fences new work but does not fabricate return of an entered or nonreturning read.
 */
internal class SessionCaptureOwner(
    private val captureThread: HandlerThread,
    private val captureHandler: Handler,
    private val controlHandler: Handler,
    private val handlerTaskPoster: HandlerTaskPoster,
    private val factPort: SessionCaptureFactPort,
    private val readbackClock: ElapsedRealtimeClock,
    private val platformSdkInt: Int,
    private val projectionPlatform: ProjectionPlatform,
    private val eglPlatform: EglPlatform,
    private val glesPlatform: GlesPlatform,
    private val targetPlatform: TargetPlatform,
) : ProjectionOwner.CallbackSink, TargetOwner.SourceSink, CaptureCallbackBoundary {
    private sealed interface Command {
        class Open(val plan: CapturePlan) : Command
        class Apply(val plan: CapturePlan) : Command

        class Read(
            val plan: CapturePlan,
            val sourceIdentity: CaptureSourceIdentity,
            val writableCarrier: ByteBuffer,
            val returnPort: CaptureReadReturnPort,
        ) : Command {
            var reservedSource: SourceCandidate? = null
            var sourceSettled = false
        }

        data object Retire : Command
    }

    private sealed interface CommandResult {
        class Open(val result: CaptureOpenResult) : CommandResult
        class Apply(val result: CaptureApplyResult) : CommandResult
        class Read(val returnPort: CaptureReadReturnPort, val result: CaptureReadResult) : CommandResult
    }

    private sealed interface RetirementState {
        data object Available : RetirementState
        data object Entered : RetirementState
        class Returned(val outcome: RetirementOutcome) : RetirementState
    }

    private class RetirementOutcome(
        val cleanupFailure: Throwable?,
        val unsafeResidue: Throwable?,
        val projectionResidue: Throwable?,
        val retainedProjectionOwner: ProjectionOwner?,
        val retainedProjectionIdentity: CaptureProjectionIdentity?,
    )

    private class CleanupAttempt<T>(val value: T?, val failure: Throwable?)

    private inner class PostedCommand(private val command: Command) : Runnable {
        override fun run() {
            val cutoff = synchronized(ownerGate) { retirementRequested && (command !is Command.Retire) }
            val result = if (cutoff) {
                when (command) {
                    is Command.Open -> {
                        check(activeCommand == null)
                        activeCommand = command
                        checkNotNull(retireOrReuse(command))
                        activeCommand = null
                        CommandResult.Open(CaptureOpenResult.CutoffInert)
                    }

                    is Command.Apply -> CommandResult.Apply(CaptureApplyResult.CutoffInert)
                    is Command.Read -> CommandResult.Read(command.returnPort, settleReadResult(command, CaptureReadResult.CutoffInert))
                    Command.Retire -> error("Retirement cannot be cutoff-inert")
                }
            } else {
                runCommand(command)
            }
            releasePostedRoot(this, command)
            result?.let(::publishResult)
        }
    }

    private val ownerGate = Any()
    private var ordinaryPost: PostedCommand? = null
    private var retirePost: PostedCommand? = null
    private var openSubmitted = false
    private var retirementRequested = false
    private var captureThreadQuitAttempted = false

    @Volatile
    private var projectionOwner: ProjectionOwner? = null

    @Volatile
    private var projectionIdentity: CaptureProjectionIdentity? = null
    private var eglOwner: EglOwner? = null
    private var targetOwner: TargetOwner? = null
    private var targetIdentity: CaptureSourceIdentity? = null
    private var targetCandidate: TargetOwner? = null
    private var targetCandidateIdentity: CaptureSourceIdentity? = null
    private var candidateAttachmentAttempted = false
    private var retiringTarget: TargetOwner? = null
    private var renderer: GLRenderer? = null
    private var installedPlan: CapturePlan? = null
    private var retirement: RetirementState = RetirementState.Available
    private var activeCommand: Command? = null

    internal fun adoptProjection(mediaProjection: MediaProjection) = synchronized(ownerGate) {
        check((projectionOwner == null) && (projectionIdentity == null)) { "Capture projection was already adopted" }
        check(!openSubmitted) { "Capture open was already submitted" }
        check(!retirementRequested) { "Capture is retiring" }
        val projection = ProjectionOwner(
            projection = mediaProjection,
            controlHandler = controlHandler,
            callbackSink = this,
            callbackBoundary = this,
            platform = projectionPlatform,
        )
        projectionOwner = projection
        projectionIdentity = CaptureProjectionIdentity(this, projection.token)
    }

    internal fun open(plan: CapturePlan): Boolean {
        synchronized(ownerGate) {
            check(!openSubmitted) { "Capture open was already submitted" }
            check(!retirementRequested) { "Capture is retiring" }
            check(projectionOwner != null) { "Capture projection was not adopted" }
            openSubmitted = true
        }
        val command = Command.Open(plan)
        return post(command)
    }

    internal fun apply(plan: CapturePlan): Boolean = post(Command.Apply(plan))

    internal fun read(
        plan: CapturePlan,
        sourceIdentity: CaptureSourceIdentity,
        writableCarrier: ByteBuffer,
        returnPort: CaptureReadReturnPort,
    ): Boolean {
        require(writableCarrier.isDirect && !writableCarrier.isReadOnly)
        require(writableCarrier.position() == 0)
        require(writableCarrier.limit() == writableCarrier.capacity())
        return post(Command.Read(plan, sourceIdentity, writableCarrier, returnPort))
    }

    internal fun retire() {
        synchronized(ownerGate) {
            if (retirementRequested) return
            retirementRequested = true
        }
        post(Command.Retire)
    }

    private fun post(command: Command): Boolean {
        val posted = PostedCommand(command)
        synchronized(ownerGate) {
            if (command is Command.Retire) {
                check(retirePost == null)
                retirePost = posted
            } else {
                check(!retirementRequested) { "Capture is retiring" }
                check(ordinaryPost == null) { "Capture command already unresolved" }
                ordinaryPost = posted
            }
        }
        val accepted = try {
            handlerTaskPoster.post(captureHandler, posted)
        } catch (failure: Exception) {
            releasePostedRoot(posted, command)
            throw failure
        }
        if (!accepted) releasePostedRoot(posted, command)
        return accepted
    }

    private fun releasePostedRoot(posted: PostedCommand, command: Command) {
        synchronized(ownerGate) {
            if (command is Command.Retire) {
                if (retirePost === posted) retirePost = null
            } else if (ordinaryPost === posted) {
                ordinaryPost = null
            }
        }
    }

    private fun runCommand(command: Command): CommandResult? {
        check(activeCommand == null)
        activeCommand = command
        val result = try {
            when (command) {
                is Command.Open -> CommandResult.Open(enter(command))
                is Command.Apply -> CommandResult.Apply(enter(command))
                is Command.Read -> CommandResult.Read(command.returnPort, settleReadResult(command, enter(command)))
                Command.Retire -> {
                    checkCaptureThread()
                    if (retireOrReuse(command) != null) requestCaptureThreadQuit()
                    null
                }
            }
        } catch (failure: Exception) {
            retireFailedCommand(command, failure)
        }
        activeCommand = null
        return result
    }

    private fun enter(command: Command.Open): CaptureOpenResult {
        checkCaptureThread()
        if ((projectionOwner == null) || (installedPlan != null) || (retirement !== RetirementState.Available)) {
            return CaptureOpenResult.Failed(
                problem = ScreenCaptureProblem.InternalFailure,
                cause = CapturePhysicalException("Capture is already open"),
            )
        }
        val projection = checkNotNull(projectionOwner)
        try {
            when (val registration = projection.registerCallback()) {
                is ProjectionOwner.ProjectionOperationResult.Failure -> throw CaptureBoundaryFailure(registration.problem, registration.cause)
                ProjectionOwner.ProjectionOperationResult.Success -> Unit
            }
            val egl = EglOwner(egl = eglPlatform, gl = glesPlatform)
            eglOwner = egl
            val fragmentPrecision = egl.open()
            egl.validateTargetAndOutput(command.plan)
            val target = TargetOwner(
                captureHandler = captureHandler,
                eglOwner = egl,
                sourceSink = this,
                callbackBoundary = this,
                platformSdkInt = platformSdkInt,
                platform = targetPlatform,
            )
            targetOwner = target
            targetIdentity = CaptureSourceIdentity(this, target.sourceCandidate)
            target.open(command.plan)
            target.installListener()
            val glRenderer = GLRenderer(egl, target, fragmentPrecision, readbackClock, platformSdkInt)
            renderer = glRenderer
            glRenderer.open(command.plan)
            when (val creation = projection.createVirtualDisplay(command.plan, target.producerSurface)) {
                ProjectionOwner.VirtualDisplayCreationResult.Created -> Unit
                ProjectionOwner.VirtualDisplayCreationResult.ReturnedNull -> throw CaptureBoundaryFailure(
                    problem = ScreenCaptureProblem.CaptureUnavailable,
                    physicalCause = CapturePhysicalException("MediaProjection.createVirtualDisplay returned null"),
                )

                is ProjectionOwner.VirtualDisplayCreationResult.Failed -> throw CaptureBoundaryFailure(creation.problem, creation.cause)
            }
            installedPlan = command.plan
            return CaptureOpenResult.Opened(
                plan = command.plan,
                sourceIdentity = checkNotNull(targetIdentity),
                projectionIdentity = checkNotNull(projectionIdentity),
            )
        } catch (failure: CaptureBoundaryFailure) {
            val primary = failure.physicalCause
            val outcome = checkNotNull(retireOrReuse(command))
            val retirementFailure = outcome.cleanupFailure ?: outcome.unsafeResidue
            val problem = if (retirementFailure == null) failure.problem else ScreenCaptureProblem.InternalFailure
            return CaptureOpenResult.Failed(problem, primary)
        } catch (failure: Exception) {
            retireOrReuse(command)
            return CaptureOpenResult.Failed(ScreenCaptureProblem.InternalFailure, failure)
        }
    }

    private fun enter(command: Command.Apply): CaptureApplyResult {
        checkCaptureThread()
        val oldPlan = installedPlan
        val oldTarget = targetOwner
        val projection = projectionOwner
        val egl = eglOwner
        val glRenderer = renderer
        if ((retirement !== RetirementState.Available) || (oldPlan == null) || (oldTarget == null) ||
            (projection == null) || (egl == null) || (glRenderer == null)
        ) {
            return CaptureApplyResult.Failed(
                problem = ScreenCaptureProblem.CaptureUnavailable,
                cause = CapturePhysicalException("Capture is not open"),
                scope = CaptureFailureScope.OwnerInvalidated,
            )
        }
        try {
            egl.validateTargetAndOutput(command.plan)
        } catch (failure: CaptureBoundaryFailure) {
            return CaptureApplyResult.Failed(
                problem = failure.problem,
                cause = failure.physicalCause,
                scope = egl.failureScope(),
            )
        } catch (failure: Exception) {
            return CaptureApplyResult.Failed(
                problem = ScreenCaptureProblem.InternalFailure,
                cause = failure,
                scope = CaptureFailureScope.OwnerInvalidated,
            )
        }
        try {
            val newPlan = command.plan
            val requiresSourceResize = (oldPlan.sourceWidthPx != newPlan.sourceWidthPx) ||
                    (oldPlan.sourceHeightPx != newPlan.sourceHeightPx)
            val requiresTargetReplacement = if (requiresSourceResize) {
                true
            } else {
                when (newPlan.targetMode) {
                    CaptureTargetMode.Full -> oldTarget.targetMode != CaptureTargetMode.Full
                    CaptureTargetMode.Downscaled -> (oldTarget.targetMode != CaptureTargetMode.Downscaled) ||
                            (oldTarget.targetWidthPx < newPlan.targetWidthPx) ||
                            (oldTarget.targetHeightPx < newPlan.targetHeightPx)
                }
            }
            if (!requiresTargetReplacement) {
                glRenderer.applyAfterPreflight(newPlan)
                when (val resize = projection.resizeIfChanged(command.plan)) {
                    is ProjectionOwner.ProjectionOperationResult.Failure -> throw CaptureBoundaryFailure(resize.problem, resize.cause)
                    ProjectionOwner.ProjectionOperationResult.Success -> Unit
                }
                installedPlan = command.plan
                return CaptureApplyResult.Applied(command.plan, checkNotNull(targetIdentity))
            }

            check((targetCandidate == null) && (retiringTarget == null))
            val replacement = TargetOwner(
                captureHandler = captureHandler,
                eglOwner = egl,
                sourceSink = this,
                callbackBoundary = this,
                platformSdkInt = platformSdkInt,
                platform = targetPlatform,
            )
            targetCandidate = replacement
            targetCandidateIdentity = CaptureSourceIdentity(this, replacement.sourceCandidate)
            candidateAttachmentAttempted = false
            try {
                replacement.open(newPlan)
            } catch (failure: CaptureBoundaryFailure) {
                val rollback = replacement.rollbackUnattached()
                if (rollback.residue == null) {
                    targetCandidate = null
                    targetCandidateIdentity = null
                    candidateAttachmentAttempted = false
                }
                val cause = failure.physicalCause
                val rollbackClean = (rollback.cleanupFailure == null) && (rollback.residue == null) && egl.isHealthy
                return if ((failure.problem == ScreenCaptureProblem.ResourceExhausted) && rollbackClean) {
                    CaptureApplyResult.ResourceDenied(cause)
                } else {
                    CaptureApplyResult.Failed(
                        problem = ScreenCaptureProblem.InternalFailure,
                        cause = cause,
                        scope = if (rollbackClean) CaptureFailureScope.OperationLocal else CaptureFailureScope.OwnerInvalidated,
                    )
                }
            } catch (failure: Exception) {
                val rollback = replacement.rollbackUnattached()
                if (rollback.residue == null) {
                    targetCandidate = null
                    targetCandidateIdentity = null
                    candidateAttachmentAttempted = false
                }
                val rollbackClean = (rollback.cleanupFailure == null) && (rollback.residue == null) && egl.isHealthy
                return CaptureApplyResult.Failed(
                    problem = ScreenCaptureProblem.InternalFailure,
                    cause = failure,
                    scope = if (rollbackClean) CaptureFailureScope.OperationLocal else CaptureFailureScope.OwnerInvalidated,
                )
            }

            glRenderer.applyAfterPreflight(newPlan)
            val listenerRemoval = oldTarget.fenceAndRemoveListener()
            listenerRemoval.failure?.let { throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, it) }
            when (val resize = projection.resizeIfChanged(newPlan)) {
                is ProjectionOwner.ProjectionOperationResult.Failure -> throw CaptureBoundaryFailure(resize.problem, resize.cause)
                ProjectionOwner.ProjectionOperationResult.Success -> Unit
            }
            replacement.installListener()
            candidateAttachmentAttempted = true
            val replacementReceipt = projection.replaceSurface(oldTarget.producerSurface, replacement.producerSurface)
            replacementReceipt.failure?.let { throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, it) }
            val surfaceReplacementProof = checkNotNull(replacementReceipt.proof)
            check(surfaceReplacementProof.namesNew(replacement.producerSurface))

            targetCandidate = null
            candidateAttachmentAttempted = false
            targetOwner = replacement
            targetIdentity = checkNotNull(targetCandidateIdentity)
            targetCandidateIdentity = null
            retiringTarget = oldTarget
            glRenderer.replaceTarget(replacement)

            val oldTargetRetirement = oldTarget.releaseAfterReplacement(listenerRemoval.proof, surfaceReplacementProof)
            if (oldTargetRetirement.residue == null) retiringTarget = null
            (oldTargetRetirement.cleanupFailure ?: oldTargetRetirement.residue)?.let { retirementFailure ->
                return CaptureApplyResult.Failed(
                    problem = ScreenCaptureProblem.InternalFailure,
                    cause = retirementFailure,
                    scope = CaptureFailureScope.OwnerInvalidated,
                )
            }
            installedPlan = newPlan
            return CaptureApplyResult.Applied(command.plan, checkNotNull(targetIdentity))
        } catch (failure: CaptureBoundaryFailure) {
            return CaptureApplyResult.Failed(
                problem = failure.problem,
                cause = failure.physicalCause,
                scope = CaptureFailureScope.OwnerInvalidated,
            )
        } catch (failure: Exception) {
            return CaptureApplyResult.Failed(
                problem = ScreenCaptureProblem.InternalFailure,
                cause = failure,
                scope = CaptureFailureScope.OwnerInvalidated,
            )
        }
    }

    private fun enter(command: Command.Read): CaptureReadResult {
        checkCaptureThread()
        val target = targetOwner
        val glRenderer = renderer
        val currentPlan = installedPlan
        val egl = eglOwner
        if ((retirement !== RetirementState.Available) || (target == null) || (glRenderer == null) ||
            (currentPlan == null) || (egl == null)
        ) {
            return CaptureReadResult.Failed(
                problem = ScreenCaptureProblem.InternalFailure,
                cause = CapturePhysicalException("Capture is not available for an entered read"),
                sourceConsumed = false,
                scope = CaptureFailureScope.OwnerInvalidated,
            )
        }
        if (!command.writableCarrier.isExactWritableRgbaCarrier(currentPlan.rgbaCarrierByteCount) ||
            command.plan !== currentPlan || !command.sourceIdentity.names(this, target.sourceCandidate)
        ) {
            return CaptureReadResult.Failed(
                problem = ScreenCaptureProblem.InternalFailure,
                cause = CapturePhysicalException("Entered Capture read does not match the installed plan/source owner"),
                sourceConsumed = false,
                scope = egl.failureScope(),
            )
        }
        if (!target.sourceCandidate.reserve()) {
            return CaptureReadResult.Failed(
                problem = ScreenCaptureProblem.InternalFailure,
                cause = CapturePhysicalException("Entered Capture read does not match the installed plan/source owner"),
                sourceConsumed = false,
                scope = egl.failureScope(),
            )
        }
        command.reservedSource = target.sourceCandidate
        return try {
            val readbackDurationNanos = glRenderer.readFrame(command.writableCarrier)
            CaptureReadResult.Filled(readbackDurationNanos)
        } catch (failure: CaptureBoundaryFailure) {
            CaptureReadResult.Failed(
                problem = failure.problem,
                cause = failure.physicalCause,
                sourceConsumed = !glRenderer.sourceRestorableAfterLastReadFailure,
                scope = egl.failureScope(),
            )
        } catch (failure: Exception) {
            CaptureReadResult.Failed(
                problem = ScreenCaptureProblem.InternalFailure,
                cause = failure,
                sourceConsumed = !glRenderer.sourceRestorableAfterLastReadFailure,
                scope = CaptureFailureScope.OwnerInvalidated,
            )
        }
    }

    private fun retireOrReuse(command: Command): RetirementOutcome? =
        when (val current = retirement) {
            RetirementState.Available -> retirePhysical(command).also(::enforceProjectionResidueRetention)
            RetirementState.Entered -> null
            is RetirementState.Returned -> current.outcome.also(::enforceProjectionResidueRetention)
        }

    private fun retirePhysical(command: Command): RetirementOutcome {
        checkCaptureThread()
        check(activeCommand === command)
        check(retirement === RetirementState.Available)
        retirement = RetirementState.Entered
        val projection = projectionOwner
        val projectionIdentityAtEntry = projectionIdentity
        val currentTarget = targetOwner
        val candidate = targetCandidate
        val oldTarget = retiringTarget
        projection?.fenceCallbacks()

        val currentListenerRemoval = attemptCleanup { currentTarget?.fenceAndRemoveListener() }
        val candidateListenerRemoval = attemptCleanup { candidate?.fenceAndRemoveListener() }
        val oldListenerRemoval = attemptCleanup { oldTarget?.fenceAndRemoveListener() }
        val displayRetirement = attemptCleanup { projection?.retireDisplay(currentTarget?.retirementSurface) }
        val rendererRetirement = attemptCleanup { renderer?.close() }

        val currentRetirement = attemptCleanup {
            currentTarget?.let { target ->
                val releaseProof = displayRetirement.value?.releaseProof
                if (releaseProof != null) {
                    target.releaseAfterDisplayRelease(currentListenerRemoval.value?.proof, releaseProof)
                } else {
                    target.releaseAndroidAndOes(currentListenerRemoval.value?.proof, displayRetirement.value?.detachProof)
                }
            }
        }
        val candidateRetirement = attemptCleanup {
            candidate?.let { target ->
                if (!candidateAttachmentAttempted) {
                    target.releaseKnownUnattached(candidateListenerRemoval.value?.proof)
                } else {
                    target.releaseAfterDisplayRelease(candidateListenerRemoval.value?.proof, displayRetirement.value?.releaseProof)
                }
            }
        }
        val oldTargetRetirement = attemptCleanup {
            oldTarget?.releaseAfterDisplayRelease(oldListenerRemoval.value?.proof, displayRetirement.value?.releaseProof)
        }

        val egl = eglOwner
        val blocksHealthyEglTeardown = (currentTarget?.blocksEglTeardown == true) || (candidate?.blocksEglTeardown == true) ||
                (oldTarget?.blocksEglTeardown == true) || (rendererRetirement.value?.residue != null)
        val eglRetirement = if ((egl != null) && (!blocksHealthyEglTeardown || !egl.isHealthy)) {
            attemptCleanup { egl.close() }
        } else {
            CleanupAttempt<EglOwner.EglRetirementOutcome>(null, null)
        }
        val namespaceDestroyedProof = eglRetirement.value?.namespaceDestroyedProof
        val rendererNamespaceRetired = namespaceDestroyedProof?.let { renderer?.retireGLNamesAfterContextDestroyed(it) } == true
        val currentNamespaceRetired = namespaceDestroyedProof?.let { currentTarget?.retireOesTextureNameAfterContextDestroyed(it) } == true
        val candidateNamespaceRetired = namespaceDestroyedProof?.let { candidate?.retireOesTextureNameAfterContextDestroyed(it) } == true
        val oldNamespaceRetired = namespaceDestroyedProof?.let { oldTarget?.retireOesTextureNameAfterContextDestroyed(it) } == true
        val projectionRetirement = attemptCleanup { projection?.retireCallbackAndProjection() }

        val cleanupFailure = currentListenerRemoval.failure
            ?: currentListenerRemoval.value?.failure
            ?: candidateListenerRemoval.failure
            ?: candidateListenerRemoval.value?.failure
            ?: oldListenerRemoval.failure
            ?: oldListenerRemoval.value?.failure
            ?: displayRetirement.failure
            ?: displayRetirement.value?.cleanupFailure
            ?: rendererRetirement.failure
            ?: rendererRetirement.value?.cleanupFailure
            ?: currentRetirement.failure
            ?: currentRetirement.value?.cleanupFailure
            ?: candidateRetirement.failure
            ?: candidateRetirement.value?.cleanupFailure
            ?: oldTargetRetirement.failure
            ?: oldTargetRetirement.value?.cleanupFailure
            ?: eglRetirement.failure
            ?: eglRetirement.value?.cleanupFailure
            ?: projectionRetirement.failure
            ?: projectionRetirement.value?.cleanupFailure

        val rendererResidue = rendererRetirement.failure ?: unresolvedGLNameResidue(
            residue = rendererRetirement.value?.residue,
            glNameResidue = rendererRetirement.value?.glNameResidue,
            namespaceRetired = rendererNamespaceRetired,
            proof = namespaceDestroyedProof,
        )
        val currentResidue = currentRetirement.failure ?: unresolvedGLNameResidue(
            residue = currentRetirement.value?.residue,
            glNameResidue = currentRetirement.value?.glNameResidue,
            namespaceRetired = currentNamespaceRetired,
            proof = namespaceDestroyedProof,
        )
        val candidateResidue = candidateRetirement.failure ?: unresolvedGLNameResidue(
            residue = candidateRetirement.value?.residue,
            glNameResidue = candidateRetirement.value?.glNameResidue,
            namespaceRetired = candidateNamespaceRetired,
            proof = namespaceDestroyedProof,
        )
        val oldResidue = oldTargetRetirement.failure ?: unresolvedGLNameResidue(
            residue = oldTargetRetirement.value?.residue,
            glNameResidue = oldTargetRetirement.value?.glNameResidue,
            namespaceRetired = oldNamespaceRetired,
            proof = namespaceDestroyedProof,
        )

        var unsafeResidue = displayRetirement.failure ?: displayRetirement.value?.let { retirement ->
            if (retirement.releaseProof == null) {
                retirement.residue ?: CapturePhysicalException("VirtualDisplay release remains unproved")
            } else {
                null
            }
        }
        unsafeResidue = unsafeResidue ?: currentResidue
        unsafeResidue = unsafeResidue ?: candidateResidue
        unsafeResidue = unsafeResidue ?: oldResidue
        unsafeResidue = unsafeResidue ?: rendererResidue
        if ((egl != null) && (eglRetirement.value == null) && (eglRetirement.failure == null)) {
            unsafeResidue = unsafeResidue ?: CapturePhysicalException("Healthy EGL retirement prerequisites remain unproved")
        } else if (egl != null) {
            unsafeResidue = unsafeResidue ?: eglRetirement.value?.residue
        }
        unsafeResidue = unsafeResidue ?: eglRetirement.failure
        val projectionResidue = projectionRetirement.failure ?: projectionRetirement.value?.residue
        unsafeResidue = unsafeResidue ?: projectionResidue

        if (unsafeResidue == null) {
            projectionOwner = null
            projectionIdentity = null
            targetOwner = null
            targetIdentity = null
            targetCandidate = null
            targetCandidateIdentity = null
            candidateAttachmentAttempted = false
            retiringTarget = null
            renderer = null
            eglOwner = null
            installedPlan = null
        }

        val outcome = RetirementOutcome(
            cleanupFailure = cleanupFailure,
            unsafeResidue = unsafeResidue,
            projectionResidue = projectionResidue,
            retainedProjectionOwner = projection.takeIf { projectionResidue != null },
            retainedProjectionIdentity = projectionIdentityAtEntry.takeIf { projectionResidue != null },
        )
        retirement = RetirementState.Returned(outcome)
        return outcome
    }

    private fun enforceProjectionResidueRetention(outcome: RetirementOutcome) {
        if (outcome.projectionResidue == null) return
        val retainedProjection = checkNotNull(outcome.retainedProjectionOwner) {
            "Projection retirement residue has no retained owner"
        }
        val retainedIdentity = checkNotNull(outcome.retainedProjectionIdentity) {
            "Projection retirement residue has no retained identity"
        }
        check(projectionOwner === retainedProjection) { "Projection retirement residue lost its exact owner" }
        check(projectionIdentity === retainedIdentity) { "Projection retirement residue lost its exact identity" }
        check(retainedIdentity.names(this, retainedProjection.token)) {
            "Retained projection identity does not match its owner"
        }
    }

    private inline fun <T> attemptCleanup(action: () -> T?): CleanupAttempt<T> = try {
        CleanupAttempt(action(), null)
    } catch (failure: Exception) {
        CleanupAttempt(null, failure)
    }

    private fun EglOwner.failureScope(): CaptureFailureScope =
        if (isHealthy) CaptureFailureScope.OperationLocal else CaptureFailureScope.OwnerInvalidated

    private fun unresolvedGLNameResidue(
        residue: Throwable?,
        glNameResidue: EglOwner.GLNameResidue?,
        namespaceRetired: Boolean,
        proof: EglOwner.GLNamespaceDestroyedProof?,
    ): Throwable? = if (namespaceRetired && (glNameResidue != null) && (proof?.retires(glNameResidue) == true)) {
        null
    } else {
        residue
    }

    private fun settleReadResult(command: Command.Read, result: CaptureReadResult): CaptureReadResult {
        val reservedSource = command.reservedSource
        if ((reservedSource != null) && !command.sourceSettled) {
            val consumed = when (result) {
                is CaptureReadResult.Filled -> true
                is CaptureReadResult.Failed -> result.sourceConsumed
                CaptureReadResult.CutoffInert -> false
            }
            reservedSource.settle(consumed)
            command.sourceSettled = true
        }
        return result
    }

    private fun publishResult(result: CommandResult) {
        when (result) {
            is CommandResult.Open -> publishFact { factPort.onOpenReturned(result.result) }
            is CommandResult.Apply -> publishFact { factPort.onApplyReturned(result.result) }
            is CommandResult.Read -> publishFact { result.returnPort.onReadReturned(result.result) }
        }
    }

    private inline fun publishFact(action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            try {
                factPort.onCaptureFailure(failure)
            } catch (_: Exception) {
            }
        }
    }

    private fun retireFailedCommand(command: Command, failure: Exception): CommandResult? {
        val sourceRestorable = renderer?.sourceRestorableAfterLastReadFailure != false
        retireOrReuse(command) ?: return null
        return when (command) {
            is Command.Open -> CommandResult.Open(CaptureOpenResult.Failed(ScreenCaptureProblem.InternalFailure, failure))
            is Command.Apply -> CommandResult.Apply(
                CaptureApplyResult.Failed(
                    problem = ScreenCaptureProblem.InternalFailure,
                    cause = failure,
                    scope = CaptureFailureScope.OwnerInvalidated,
                ),
            )

            is Command.Read -> CommandResult.Read(
                returnPort = command.returnPort,
                result = settleReadResult(
                    command,
                    CaptureReadResult.Failed(
                        problem = ScreenCaptureProblem.InternalFailure,
                        cause = failure,
                        sourceConsumed = !sourceRestorable,
                        scope = CaptureFailureScope.OwnerInvalidated,
                    ),
                ),
            )

            Command.Retire -> null
        }
    }

    override fun onSourceAvailable(candidate: SourceCandidate) {
        checkCaptureThread()
        if ((retirement === RetirementState.Available) && (candidate === targetOwner?.sourceCandidate) && candidate.markAvailable()) {
            val identity = checkNotNull(targetIdentity)
            publishFact { factPort.onSourceAvailable(identity) }
        }
    }

    override fun onProjectionStopped(token: ProjectionOwner.Token) {
        val identity = projectionIdentity?.takeIf { it.names(this, token) } ?: return
        projectionOwner?.fenceCallbacks()
        publishFact { factPort.onProjectionStopped(identity) }
        retire()
    }

    override fun onCapturedContentResize(token: ProjectionOwner.Token, widthPx: Int, heightPx: Int) {
        val identity = projectionIdentity?.takeIf { it.names(this, token) } ?: return
        publishFact { factPort.onCapturedContentResize(identity, widthPx, heightPx) }
    }

    override fun onCapturedContentVisibilityChanged(token: ProjectionOwner.Token, isVisible: Boolean) {
        val identity = projectionIdentity?.takeIf { it.names(this, token) } ?: return
        publishFact { factPort.onCapturedContentVisibilityChanged(identity, isVisible) }
    }

    override fun onCallbackException(identity: CaptureCallbackIdentity, failure: Exception) {
        val isCurrent = when (identity) {
            is CaptureCallbackIdentity.Projection -> projectionIdentity?.names(this, identity.token) == true
            is CaptureCallbackIdentity.Target -> targetOwner?.sourceCandidate?.token === identity.source
        }
        if (isCurrent) publishFact { factPort.onCaptureFailure(failure) }
    }

    private fun requestCaptureThreadQuit() {
        if (captureThreadQuitAttempted) return
        captureThreadQuitAttempted = true
        try {
            captureThread.quitSafely()
        } catch (_: Exception) {
        }
    }

    private fun checkCaptureThread() {
        check(Looper.myLooper() === captureHandler.looper) { "Physical capture operation escaped Capture Handler" }
    }
}
