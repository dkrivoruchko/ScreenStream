package io.screenstream.engine.internal.capture

import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.screenstream.engine.ScreenCaptureProblem

/**
 * Typed, bounded exchange with the future Control lane. Implementations only admit commands and enqueue facts/results;
 * Capture never calls Session policy synchronously through this port.
 */
internal interface CaptureLaneExchange : ProjectionCallbackSink, CaptureSourceSink, CaptureColorFactSink {
    fun tryEnter(command: OpenCapture): Boolean
    fun tryEnter(command: ApplyPlan): Boolean
    fun tryEnter(command: ReadFrame): Boolean
    fun tryEnter(command: CloseCapture): Boolean

    fun onOpenCutoff(command: OpenCapture)
    fun onCloseCutoff(command: CloseCapture)
    fun onCommandException(command: CaptureCommand, failure: Exception)
    fun onFatal(command: CaptureCommand, fatal: Throwable)
    fun onResult(result: OpenCaptureResult)
    fun onResult(result: ApplyPlanResult)
    fun onResult(result: ReadFrameResult)
    fun onResult(result: CloseCaptureResult)
}

internal object AndroidCaptureClock : CaptureClock {
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

internal class CaptureRetirementOutcome internal constructor(
    internal val cleanupFailure: Throwable?,
    internal val residue: Throwable?,
)

/** The sole physical Capture lane for the Full-target + Direct-carrier vertical slice. */
internal class CaptureLane internal constructor(
    private val captureHandler: Handler,
    private val controlHandler: Handler,
    private val exchange: CaptureLaneExchange,
    private val clock: CaptureClock = AndroidCaptureClock,
) : CaptureOwner, CaptureSourceSink, CaptureColorFactSink {
    private var projectionOwner: ProjectionOwner? = null
    private var eglOwner: EglOwner? = null
    private var targetOwner: TargetOwner? = null
    private var renderer: GlRenderer? = null
    private var openedProjection: MediaProjection? = null
    private var installedPlan: CapturePlan? = null
    private var installedRevision = 0L
    private var closing = false
    private var claimedOpen: OpenCapture? = null
    private var retainedResidue: Throwable? = null

    override val mediaProjection: MediaProjection
        get() = checkNotNull(openedProjection)

    override val source: CaptureSourceToken
        get() = checkNotNull(targetOwner).source

    /** Control calls this before capsule adoption/dispatch so partial physical ownership is durably rooted. */
    internal fun claim(command: OpenCapture): CaptureOwner {
        check(claimedOpen == null && openedProjection == null && projectionOwner == null && !closing)
        claimedOpen = command
        openedProjection = command.mediaProjection
        projectionOwner = ProjectionOwner(command.mediaProjection, controlHandler, exchange, clock)
        return this
    }

    internal fun post(command: OpenCapture): Boolean {
        check(claimedOpen === command && openedProjection === command.mediaProjection)
        return captureHandler.post { runCommand(command) }
    }

    internal fun post(command: ApplyPlan): Boolean = captureHandler.post { runCommand(command) }

    internal fun post(command: ReadFrame): Boolean = captureHandler.post { runCommand(command) }

    internal fun post(command: CloseCapture): Boolean = captureHandler.post { runCommand(command) }

    private fun runCommand(command: CaptureCommand) {
        try {
            when (command) {
                is OpenCapture -> enter(command)
                is ApplyPlan -> enter(command)
                is ReadFrame -> enter(command)
                is CloseCapture -> enter(command)
            }
        } catch (failure: Exception) {
            reportCommandExceptionWithinBoundary(command, failure)
        } catch (fatal: Throwable) {
            propagateFatalAfterNotification(command, fatal)
        }
    }

    private fun enter(command: OpenCapture) {
        requireCaptureHandler()
        if (!exchange.tryEnter(command)) {
            exchange.onOpenCutoff(command)
            return
        }
        if (claimedOpen !== command || projectionOwner == null || closing) {
            exchange.onResult(
                OpenFailed(
                    command,
                    ScreenCaptureProblem.InternalFailure,
                    CapturePhysicalException("Capture ownership claim does not match OpenCapture"),
                    OpenFailureRetirement.RetainedLocally(this, CapturePhysicalException("Open claim remains owned")),
                ),
            )
            return
        }
        val projection = checkNotNull(projectionOwner)
        try {
            projection.registerCallback().requireSuccess()
            val egl = EglOwner(clock)
            eglOwner = egl
            val capabilities = egl.open()
            egl.validateTargetAndOutput(command.plan)
            val target = TargetOwner(captureHandler, egl, this, clock)
            targetOwner = target
            target.construct(command.plan)
            target.installListener()
            val glRenderer = GlRenderer(egl, target, capabilities.fragmentPrecision, this, clock)
            renderer = glRenderer
            glRenderer.construct(command.plan)
            when (val creation = projection.createVirtualDisplay(command.plan, target.producerSurface)) {
                is VirtualDisplayCreationResult.Created -> target.recordDisplayCreation(returnedNonNull = true)
                VirtualDisplayCreationResult.ReturnedNull -> {
                    target.recordDisplayCreation(returnedNonNull = false)
                    throw CaptureBoundaryFailure(
                        ScreenCaptureProblem.CaptureUnavailable,
                        CapturePhysicalException("MediaProjection.createVirtualDisplay returned null"),
                    )
                }

                is VirtualDisplayCreationResult.Failed ->
                    throw CaptureBoundaryFailure(creation.problem, creation.cause)
            }
            installedPlan = command.plan
            installedRevision = command.configRevision
            exchange.onResult(Opened(command, this, projection.firstResizeDeadlineNanos))
        } catch (failure: CaptureBoundaryFailure) {
            val primary = failure.physicalCause ?: failure
            retirePhysical(command) { outcome ->
                mergeFailure(primary, outcome.cleanupFailure)
                exchange.onResult(
                    OpenFailed(command, failure.problem, primary, openFailureRetirement(primary, outcome.residue)),
                )
            }
        } catch (failure: Exception) {
            retirePhysical(command) { outcome ->
                mergeFailure(failure, outcome.cleanupFailure)
                exchange.onResult(
                    OpenFailed(
                        command,
                        ScreenCaptureProblem.InternalFailure,
                        failure,
                        openFailureRetirement(failure, outcome.residue),
                    ),
                )
            }
        }
    }

    private fun enter(command: ApplyPlan) {
        requireCaptureHandler()
        if (!exchange.tryEnter(command)) {
            exchange.onResult(ApplySkippedBeforeEntry(command))
            return
        }
        val oldPlan = installedPlan
        val oldTarget = targetOwner
        val projection = projectionOwner
        val egl = eglOwner
        val glRenderer = renderer
        if (closing || oldPlan == null || oldTarget == null || projection == null || egl == null || glRenderer == null) {
            exchange.onResult(
                ApplyFailed(
                    command,
                    ApplyUnsafeFailure(
                        ScreenCaptureProblem.CaptureUnavailable,
                        CapturePhysicalException("Capture is not open"),
                    ),
                ),
            )
            return
        }
        try {
            egl.validateTargetAndOutput(command.plan)
        } catch (failure: CaptureBoundaryFailure) {
            val cause = failure.physicalCause ?: failure
            exchange.onResult(
                if (failure.problem == ScreenCaptureProblem.ResourceExhausted) {
                    ApplyFailed(command, ApplyPreflightResourceDenied(cause))
                } else {
                    ApplyFailed(command, ApplyUnsafeFailure(failure.problem, cause))
                },
            )
            return
        } catch (failure: Exception) {
            exchange.onResult(
                ApplyFailed(command, ApplyUnsafeFailure(ScreenCaptureProblem.InternalFailure, failure)),
            )
            return
        }
        try {
            glRenderer.applyAfterPreflight(command.plan)
            val replacingTarget = requiresTargetReplacement(oldPlan, oldTarget, command.plan)
            if (!replacingTarget) {
                projection.resizeIfChanged(command.plan).requireSuccess()
                installedPlan = command.plan
                installedRevision = command.configRevision
                exchange.onResult(Applied(command, oldTarget.source))
                return
            }
            oldTarget.fenceAndRemoveListener { markerFailure ->
                continueCommand(command) {
                    if (markerFailure != null) {
                        exchange.onResult(
                            ApplyFailed(
                                command,
                                ApplyUnsafeFailure(ScreenCaptureProblem.InternalFailure, markerFailure),
                            ),
                        )
                    } else {
                        continueTargetReplacement(command, oldTarget, projection, egl, glRenderer)
                    }
                }
            }
        } catch (failure: CaptureBoundaryFailure) {
            exchange.onResult(
                ApplyFailed(command, ApplyUnsafeFailure(failure.problem, failure.physicalCause ?: failure)),
            )
        } catch (failure: Exception) {
            exchange.onResult(
                ApplyFailed(command, ApplyUnsafeFailure(ScreenCaptureProblem.InternalFailure, failure)),
            )
        }
    }

    private fun continueTargetReplacement(
        command: ApplyPlan,
        oldTarget: TargetOwner,
        projection: ProjectionOwner,
        egl: EglOwner,
        glRenderer: GlRenderer,
    ) {
        requireCaptureHandler()
        try {
            projection.detachSurface().requireSuccess()
            oldTarget.recordSurfaceDetached()
            val oldTargetRelease = oldTarget.releaseAndroidAndOes()
            if (oldTargetRelease.cleanupFailure != null) {
                throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, oldTargetRelease.cleanupFailure)
            }
            targetOwner = null
            val replacement = TargetOwner(captureHandler, egl, this, clock)
            targetOwner = replacement
            replacement.construct(command.plan)
            replacement.installListener()
            projection.resizeIfChanged(command.plan).requireSuccess()
            projection.attachSurface(replacement.producerSurface).requireSuccess()
            replacement.recordSurfaceAttached()
            glRenderer.replaceTarget(replacement)
            installedPlan = command.plan
            installedRevision = command.configRevision
            exchange.onResult(Applied(command, replacement.source))
        } catch (failure: CaptureBoundaryFailure) {
            exchange.onResult(
                ApplyFailed(command, ApplyUnsafeFailure(failure.problem, failure.physicalCause ?: failure)),
            )
        } catch (failure: Exception) {
            exchange.onResult(
                ApplyFailed(command, ApplyUnsafeFailure(ScreenCaptureProblem.InternalFailure, failure)),
            )
        }
    }

    private fun enter(command: ReadFrame) {
        requireCaptureHandler()
        if (!exchange.tryEnter(command)) {
            exchange.onResult(ReadSkippedBeforeEntry(command))
            return
        }
        val target = targetOwner
        val glRenderer = renderer
        if (closing || target == null || glRenderer == null || command.configRevision != installedRevision) {
            exchange.onResult(ReadSkippedBeforeEntry(command))
            return
        }
        if (!target.consumePendingSource()) {
            exchange.onResult(NoCurrentSource(command))
            return
        }
        try {
            exchange.onResult(FrameReady(command, glRenderer.readFrame(command.writableCarrier)))
        } catch (failure: CaptureBoundaryFailure) {
            exchange.onResult(ReadFailed(command, failure.problem, failure.physicalCause ?: failure))
        } catch (failure: Exception) {
            exchange.onResult(ReadFailed(command, ScreenCaptureProblem.InternalFailure, failure))
        }
    }

    private fun enter(command: CloseCapture) {
        requireCaptureHandler()
        if (!exchange.tryEnter(command)) {
            exchange.onCloseCutoff(command)
            return
        }
        val existingResidue = retainedResidue
        if (existingResidue != null) {
            exchange.onResult(CaptureRetainedLocally(command, existingResidue))
            return
        }
        if (closing) {
            exchange.onResult(CaptureRetainedLocally(command, CapturePhysicalException("Capture close is already active")))
            return
        }
        closing = true
        retirePhysical(command) { outcome ->
            exchange.onResult(
                if (outcome.residue == null) {
                    CaptureClosed(command, outcome.cleanupFailure)
                } else {
                    CaptureRetainedLocally(command, outcome.residue)
                },
            )
        }
    }

    private fun retirePhysical(command: CaptureCommand, after: (CaptureRetirementOutcome) -> Unit) {
        requireCaptureHandler()
        closing = true
        projectionOwner?.fenceCallbacks()
        val target = targetOwner
        if (target != null && target.canFenceListener) {
            try {
                target.fenceAndRemoveListener { markerFailure ->
                    continueCommand(command) { finishRetirement(after, markerFailure) }
                }
            } catch (failure: CaptureBoundaryFailure) {
                val residue = failure.physicalCause ?: failure
                retainedResidue = residue
                after(CaptureRetirementOutcome(residue, residue))
            }
        } else {
            finishRetirement(after, null)
        }
    }

    private fun finishRetirement(after: (CaptureRetirementOutcome) -> Unit, initialFailure: Throwable?) {
        requireCaptureHandler()
        val projection = projectionOwner
        var cleanupFailure: Throwable? = initialFailure
        var residue: Throwable? = null
        if (targetOwner?.blocksProducerRetirement == true) {
            val blockedTarget = targetOwner?.releaseAndroidAndOes()
            cleanupFailure = mergeFailure(cleanupFailure, blockedTarget?.cleanupFailure)
            val exactResidue = blockedTarget?.residue
                ?: CapturePhysicalException("Target listener ordering remains unresolved")
            retainedResidue = exactResidue
            after(CaptureRetirementOutcome(cleanupFailure, exactResidue))
            return
        }
        if (projection != null) {
            when (val release = projection.releaseDisplay()) {
                ProjectionOperationResult.Success -> targetOwner?.recordSurfaceDetached()
                is ProjectionOperationResult.Failure -> {
                    val failure = release.cause ?: CapturePhysicalException("VirtualDisplay release failed")
                    cleanupFailure = mergeFailure(cleanupFailure, failure)
                    residue = failure
                }
            }
        }

        cleanupFailure = mergeFailure(cleanupFailure, renderer?.close())
        if (projection?.hasOwnedDisplay == true) {
            val exactResidue = residue ?: CapturePhysicalException("VirtualDisplay remains owned")
            retainedResidue = exactResidue
            after(CaptureRetirementOutcome(cleanupFailure, exactResidue))
            return
        }

        val targetRelease = targetOwner?.releaseAndroidAndOes()
        cleanupFailure = mergeFailure(cleanupFailure, targetRelease?.cleanupFailure)
        if (targetOwner?.blocksEglTeardown == true) {
            residue = targetRelease?.residue ?: CapturePhysicalException("Target dependency chain remains owned")
            cleanupFailure = finishProjectionSuffix(projection, cleanupFailure) { projectionResidue ->
                residue = residue ?: projectionResidue
            }
            retainedResidue = residue
            after(CaptureRetirementOutcome(cleanupFailure, residue))
            return
        }
        renderer = null

        val eglFailure = eglOwner?.close()
        cleanupFailure = mergeFailure(cleanupFailure, eglFailure)
        if (eglFailure != null) {
            residue = eglFailure
        } else {
            eglOwner = null
            targetOwner = null
        }
        cleanupFailure = finishProjectionSuffix(projection, cleanupFailure) { projectionResidue ->
            residue = residue ?: projectionResidue
        }
        if (residue == null) {
            projectionOwner = null
            openedProjection = null
            claimedOpen = null
            installedPlan = null
            installedRevision = 0L
        } else {
            retainedResidue = residue
        }
        after(CaptureRetirementOutcome(cleanupFailure, residue))
    }

    private fun finishProjectionSuffix(
        projection: ProjectionOwner?,
        initialFailure: Throwable?,
        recordResidue: (Throwable) -> Unit,
    ): Throwable? {
        if (projection == null) return initialFailure
        var cleanupFailure = initialFailure
        when (val unregister = projection.unregisterCallback()) {
            ProjectionOperationResult.Success -> Unit
            is ProjectionOperationResult.Failure -> {
                val failure = unregister.cause ?: CapturePhysicalException("Projection callback remains registered")
                cleanupFailure = mergeFailure(cleanupFailure, failure)
                recordResidue(failure)
            }
        }
        when (val stop = projection.stopProjection()) {
            ProjectionOperationResult.Success -> Unit
            is ProjectionOperationResult.Failure -> {
                val failure = stop.cause ?: CapturePhysicalException("MediaProjection remains owned")
                cleanupFailure = mergeFailure(cleanupFailure, failure)
                recordResidue(failure)
            }
        }
        return cleanupFailure
    }

    private fun mergeFailure(first: Throwable?, next: Throwable?): Throwable? {
        if (next == null) return first
        if (first == null) return next
        if (next !== first && first.suppressed.none { it === next }) first.addSuppressed(next)
        return first
    }

    override fun onSourceAvailable(fact: SourceAvailable) {
        requireCaptureHandler()
        if (!closing && fact.source === targetOwner?.source) exchange.onSourceAvailable(fact)
    }

    override fun onColorAction(fact: CaptureColorFact) {
        requireCaptureHandler()
        if (!closing) exchange.onColorAction(fact)
    }

    private fun ProjectionOperationResult.requireSuccess() {
        if (this is ProjectionOperationResult.Failure) {
            throw CaptureBoundaryFailure(problem, cause)
        }
    }

    private fun requiresTargetReplacement(
        oldPlan: CapturePlan,
        oldTarget: TargetOwner,
        newPlan: CapturePlan,
    ): Boolean {
        if (oldPlan.sourceWidthPx != newPlan.sourceWidthPx ||
            oldPlan.sourceHeightPx != newPlan.sourceHeightPx
        ) {
            return true
        }
        return when (newPlan.targetMode) {
            CaptureTargetMode.Full -> oldTarget.targetMode != CaptureTargetMode.Full
            CaptureTargetMode.Downscaled -> oldTarget.targetMode != CaptureTargetMode.Downscaled ||
                    oldTarget.targetWidthPx < newPlan.targetWidthPx ||
                    oldTarget.targetHeightPx < newPlan.targetHeightPx
        }
    }

    private fun continueCommand(command: CaptureCommand, continuation: () -> Unit) {
        try {
            continuation()
        } catch (failure: Exception) {
            reportCommandExceptionWithinBoundary(command, failure)
        } catch (fatal: Throwable) {
            propagateFatalAfterNotification(command, fatal)
        }
    }

    private fun reportCommandExceptionWithinBoundary(command: CaptureCommand, failure: Exception) {
        try {
            exchange.onCommandException(command, failure)
        } catch (_: Exception) {
            // Control has already rooted the original ordinary failure and contains ordinary selection failure.
        } catch (fatal: Throwable) {
            propagateFatalAfterNotification(command, fatal)
        }
    }

    private fun propagateFatalAfterNotification(command: CaptureCommand, fatal: Throwable): Nothing {
        try {
            exchange.onFatal(command, fatal)
        } catch (_: Throwable) {
            // The Capture-side fatal remains the exact propagated object.
        } finally {
            throw fatal
        }
    }

    private fun openFailureRetirement(primary: Throwable, residue: Throwable?): OpenFailureRetirement {
        if (residue == null) return OpenFailureRetirement.FullyRetired
        if (residue !== primary && primary.suppressed.none { it === residue }) primary.addSuppressed(residue)
        return OpenFailureRetirement.RetainedLocally(this, residue)
    }

    private fun requireCaptureHandler() {
        check(Looper.myLooper() === captureHandler.looper) { "Physical capture operation escaped Capture Handler" }
    }
}
