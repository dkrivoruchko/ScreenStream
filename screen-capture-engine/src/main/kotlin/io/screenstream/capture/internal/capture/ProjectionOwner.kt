package io.screenstream.capture.internal.capture

import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.view.Surface
import io.screenstream.capture.ScreenCaptureProblem
import java.util.concurrent.atomic.AtomicBoolean

internal class ProjectionOwner(
    private val projection: MediaProjection,
    private val controlHandler: Handler,
    private val callbackSink: CallbackSink,
    private val callbackBoundary: CaptureCallbackBoundary,
    private val platform: ProjectionPlatform = AndroidProjectionPlatform,
) {
    internal class Token

    internal interface CallbackSink {
        fun onProjectionStopped(token: Token)

        fun onCapturedContentResize(token: Token, widthPx: Int, heightPx: Int)

        fun onCapturedContentVisibilityChanged(token: Token, isVisible: Boolean)
    }

    internal sealed interface ProjectionOperationResult {
        data object Success : ProjectionOperationResult
        class Failure(internal val problem: ScreenCaptureProblem, internal val cause: Throwable) : ProjectionOperationResult
    }

    internal sealed interface VirtualDisplayCreationResult {
        data object Created : VirtualDisplayCreationResult
        data object ReturnedNull : VirtualDisplayCreationResult
        class Failed(internal val problem: ScreenCaptureProblem, internal val cause: Throwable) : VirtualDisplayCreationResult
    }

    internal class VirtualDisplayDetachProof(private val surface: Surface?) {
        internal fun matches(expectedSurface: Surface?): Boolean = surface === expectedSurface
    }

    private class VirtualDisplayDetachOutcome(val proof: VirtualDisplayDetachProof?, val failure: Throwable?)

    internal class VirtualDisplayReleaseProof

    internal class SurfaceReplacementProof(private val oldSurface: Surface, private val newSurface: Surface) {
        internal fun namesOld(expected: Surface?): Boolean = oldSurface === expected
        internal fun namesNew(expected: Surface?): Boolean = newSurface === expected
    }

    internal class SurfaceReplacementOutcome(internal val proof: SurfaceReplacementProof?, internal val failure: Throwable?)

    internal class VirtualDisplayRetirementOutcome(
        internal val detachProof: VirtualDisplayDetachProof?,
        internal val releaseProof: VirtualDisplayReleaseProof?,
        internal val cleanupFailure: Throwable?,
        internal val residue: Throwable?,
    )

    internal class ProjectionRetirementOutcome(internal val cleanupFailure: Throwable?, internal val residue: Throwable?)

    private enum class CallbackRegistration { Prepared, Attempted, Registered, UnregisterAttempted, Unregistered, }

    private enum class DisplayCreation { NotAttempted, Attempted, DeniedWithoutDisplay, ReturnedNull, Owned, ReleaseAttempted, Released, }

    private fun DisplayCreation.provesNoOwnedRoot(): Boolean = when (this) {
        DisplayCreation.NotAttempted,
        DisplayCreation.DeniedWithoutDisplay,
        DisplayCreation.ReturnedNull,
            -> true

        DisplayCreation.Attempted,
        DisplayCreation.Owned,
        DisplayCreation.ReleaseAttempted,
        DisplayCreation.Released,
            -> false
    }

    private sealed interface DisplayDetach {
        data object Eligible : DisplayDetach
        class Entered(val surface: Surface?) : DisplayDetach
        class Retained(val surface: Surface?, val failure: Throwable) : DisplayDetach
        class Proved(val proof: VirtualDisplayDetachProof) : DisplayDetach
    }

    private enum class ProjectionStop { Eligible, Attempted, Stopped, }

    internal val token: Token = Token()
    private val callbackIdentity = CaptureCallbackIdentity.Projection(token)
    private val callbackFence = AtomicBoolean(true)
    private var callbackRegistration = CallbackRegistration.Prepared
    private var displayCreation = DisplayCreation.NotAttempted
    private var virtualDisplay: VirtualDisplay? = null
    private var actualWidthPx = 0
    private var actualHeightPx = 0
    private var actualDensityDpi = 0
    private var attachedSurface: Surface? = null
    private var displayDetach: DisplayDetach = DisplayDetach.Eligible
    private var projectionStop = ProjectionStop.Eligible
    private var displayReleaseFailure: Throwable? = null
    private var callbackUnregisterFailure: Throwable? = null
    private var projectionStopFailure: Throwable? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            runCaptureCallback(callbackBoundary, callbackIdentity) {
                if (!callbackFence.get()) return@runCaptureCallback
                callbackSink.onProjectionStopped(token)
            }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            runCaptureCallback(callbackBoundary, callbackIdentity) {
                if (!callbackFence.get()) return@runCaptureCallback
                callbackSink.onCapturedContentResize(token, width, height)
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            runCaptureCallback(callbackBoundary, callbackIdentity) {
                if (!callbackFence.get()) return@runCaptureCallback
                callbackSink.onCapturedContentVisibilityChanged(token, isVisible)
            }
        }
    }

    internal fun registerCallback(): ProjectionOperationResult {
        check(callbackRegistration == CallbackRegistration.Prepared)
        return try {
            callbackRegistration = CallbackRegistration.Attempted
            platform.registerCallback(projection, callback, controlHandler)
            callbackRegistration = CallbackRegistration.Registered
            ProjectionOperationResult.Success
        } catch (failure: Exception) {
            ProjectionOperationResult.Failure(ScreenCaptureProblem.InternalFailure, failure)
        }
    }

    internal fun createVirtualDisplay(plan: CapturePlan, surface: Surface): VirtualDisplayCreationResult {
        check(displayCreation == DisplayCreation.NotAttempted)
        return try {
            displayCreation = DisplayCreation.Attempted
            val returned = platform.createVirtualDisplay(
                projection = projection,
                widthPx = plan.sourceWidthPx,
                heightPx = plan.sourceHeightPx,
                densityDpi = plan.densityDpi,
                surface = surface,
            )
            if (returned == null) {
                displayCreation = DisplayCreation.ReturnedNull
                VirtualDisplayCreationResult.ReturnedNull
            } else {
                virtualDisplay = returned
                displayCreation = DisplayCreation.Owned
                attachedSurface = surface
                actualWidthPx = plan.sourceWidthPx
                actualHeightPx = plan.sourceHeightPx
                actualDensityDpi = plan.densityDpi
                VirtualDisplayCreationResult.Created
            }
        } catch (failure: SecurityException) {
            displayCreation = DisplayCreation.DeniedWithoutDisplay
            VirtualDisplayCreationResult.Failed(problem = ScreenCaptureProblem.CaptureUnavailable, cause = failure)
        } catch (failure: Exception) {
            VirtualDisplayCreationResult.Failed(problem = ScreenCaptureProblem.InternalFailure, cause = failure)
        }
    }

    internal fun resizeIfChanged(plan: CapturePlan): ProjectionOperationResult {
        val display = virtualDisplay
            ?: return ProjectionOperationResult.Failure(
                problem = ScreenCaptureProblem.CaptureUnavailable,
                cause = CapturePhysicalException("VirtualDisplay is unavailable"),
            )
        if ((actualWidthPx == plan.sourceWidthPx) && (actualHeightPx == plan.sourceHeightPx) && (actualDensityDpi == plan.densityDpi)) {
            return ProjectionOperationResult.Success
        }
        return try {
            platform.resize(display, plan.sourceWidthPx, plan.sourceHeightPx, plan.densityDpi)
            actualWidthPx = plan.sourceWidthPx
            actualHeightPx = plan.sourceHeightPx
            actualDensityDpi = plan.densityDpi
            ProjectionOperationResult.Success
        } catch (failure: Exception) {
            ProjectionOperationResult.Failure(problem = ScreenCaptureProblem.InternalFailure, cause = failure)
        }
    }

    internal fun replaceSurface(expectedOld: Surface, replacement: Surface): SurfaceReplacementOutcome {
        val display = virtualDisplay ?: return SurfaceReplacementOutcome(
            proof = null,
            failure = CapturePhysicalException("VirtualDisplay is unavailable"),
        )
        if ((attachedSurface !== expectedOld) || (displayDetach !is DisplayDetach.Eligible)) {
            return SurfaceReplacementOutcome(
                proof = null,
                failure = CapturePhysicalException("VirtualDisplay replacement does not name the current Surface"),
            )
        }
        val proof = SurfaceReplacementProof(expectedOld, replacement)
        return try {
            displayDetach = DisplayDetach.Entered(expectedOld)
            platform.setSurface(display, replacement)
            attachedSurface = replacement
            displayDetach = DisplayDetach.Eligible
            SurfaceReplacementOutcome(proof = proof, failure = null)
        } catch (failure: Exception) {
            displayDetach = DisplayDetach.Retained(expectedOld, failure)
            SurfaceReplacementOutcome(proof = null, failure = failure)
        }
    }

    private fun detachSurface(expectedSurface: Surface?): VirtualDisplayDetachOutcome {
        when (val state = displayDetach) {
            DisplayDetach.Eligible -> Unit

            is DisplayDetach.Entered -> {
                val failure = CapturePhysicalException("VirtualDisplay detach has not returned")
                return VirtualDisplayDetachOutcome(proof = null, failure = failure)
            }

            is DisplayDetach.Retained -> {
                val failure = if (state.surface === expectedSurface) {
                    state.failure
                } else {
                    CapturePhysicalException("VirtualDisplay detach belongs to a different Surface")
                }
                return VirtualDisplayDetachOutcome(proof = null, failure = failure)
            }

            is DisplayDetach.Proved -> {
                if (state.proof.matches(expectedSurface)) {
                    return VirtualDisplayDetachOutcome(proof = state.proof, failure = null)
                }
                if (attachedSurface != null) {
                    val failure = CapturePhysicalException("VirtualDisplay detach proof belongs to a different Surface")
                    return VirtualDisplayDetachOutcome(proof = null, failure = failure)
                }
                displayDetach = DisplayDetach.Eligible
            }
        }
        val display = virtualDisplay ?: return if (displayCreation.provesNoOwnedRoot() || (displayCreation == DisplayCreation.Released)) {
            VirtualDisplayDetachOutcome(proof = VirtualDisplayDetachProof(expectedSurface), failure = null)
        } else {
            val failure = displayReleaseFailure ?: CapturePhysicalException("VirtualDisplay ownership is ambiguous")
            VirtualDisplayDetachOutcome(proof = null, failure = failure)
        }
        if (attachedSurface == null) {
            val proof = VirtualDisplayDetachProof(expectedSurface)
            displayDetach = DisplayDetach.Proved(proof)
            return VirtualDisplayDetachOutcome(proof = proof, failure = null)
        }
        if (attachedSurface !== expectedSurface) {
            val failure = CapturePhysicalException("VirtualDisplay is attached to a different Surface")
            return VirtualDisplayDetachOutcome(proof = null, failure = failure)
        }
        val proof = VirtualDisplayDetachProof(expectedSurface)
        return try {
            displayDetach = DisplayDetach.Entered(expectedSurface)
            platform.setSurface(display = display, surface = null)
            attachedSurface = null
            displayDetach = DisplayDetach.Proved(proof)
            VirtualDisplayDetachOutcome(proof = proof, failure = null)
        } catch (failure: Exception) {
            val returnedProof = (displayDetach as? DisplayDetach.Proved)?.proof
            if (returnedProof == null) displayDetach = DisplayDetach.Retained(expectedSurface, failure)
            VirtualDisplayDetachOutcome(proof = returnedProof, failure = failure)
        }
    }

    internal fun fenceCallbacks() {
        callbackFence.set(false)
    }

    internal fun retireDisplay(expectedSurface: Surface?): VirtualDisplayRetirementOutcome {
        val detach = detachSurface(expectedSurface)
        var cleanupFailure = detach.failure
        val display = virtualDisplay
        if (display == null) {
            val definiteAbsence = displayCreation.provesNoOwnedRoot() || (displayCreation == DisplayCreation.Released)
            val residue = if (definiteAbsence) null else {
                cleanupFailure ?: CapturePhysicalException("VirtualDisplay root cannot be retired")
            }
            return VirtualDisplayRetirementOutcome(
                detachProof = detach.proof,
                releaseProof = if (definiteAbsence) VirtualDisplayReleaseProof() else null,
                cleanupFailure = cleanupFailure,
                residue = residue,
            )
        }

        if (displayCreation == DisplayCreation.ReleaseAttempted) {
            val failure = displayReleaseFailure
                ?: CapturePhysicalException("VirtualDisplay release returned without retiring its root")
            cleanupFailure = cleanupFailure ?: failure
            return VirtualDisplayRetirementOutcome(detach.proof, null, cleanupFailure, failure)
        }

        val releaseFailure = try {
            displayCreation = DisplayCreation.ReleaseAttempted
            platform.release(display)
            virtualDisplay = null
            attachedSurface = null
            displayCreation = DisplayCreation.Released
            displayDetach = DisplayDetach.Proved(VirtualDisplayDetachProof(expectedSurface))
            null
        } catch (failure: Exception) {
            displayReleaseFailure = failure
            failure
        }
        cleanupFailure = cleanupFailure ?: releaseFailure
        val released = displayCreation == DisplayCreation.Released
        val proof = if (released) VirtualDisplayDetachProof(expectedSurface) else detach.proof
        return VirtualDisplayRetirementOutcome(
            detachProof = proof,
            releaseProof = if (released) VirtualDisplayReleaseProof() else null,
            cleanupFailure = cleanupFailure,
            residue = if (released) null else releaseFailure,
        )
    }

    internal fun retireCallbackAndProjection(): ProjectionRetirementOutcome {
        var cleanupFailure: Throwable? = null
        val mustUnregister = (callbackRegistration == CallbackRegistration.Attempted) || (callbackRegistration == CallbackRegistration.Registered)
        if (mustUnregister) {
            val failure = try {
                callbackRegistration = CallbackRegistration.UnregisterAttempted
                platform.unregisterCallback(projection, callback)
                callbackRegistration = CallbackRegistration.Unregistered
                null
            } catch (returnedFailure: Exception) {
                callbackUnregisterFailure = returnedFailure
                returnedFailure
            }
            cleanupFailure = failure
        } else if (callbackRegistration == CallbackRegistration.UnregisterAttempted) {
            cleanupFailure = callbackUnregisterFailure ?: CapturePhysicalException("Projection callback unregister remains unproved")
        }

        if (projectionStop == ProjectionStop.Eligible) {
            val failure = try {
                projectionStop = ProjectionStop.Attempted
                platform.stop(projection)
                projectionStop = ProjectionStop.Stopped
                null
            } catch (returnedFailure: Exception) {
                projectionStopFailure = returnedFailure
                returnedFailure
            }
            cleanupFailure = cleanupFailure ?: failure
        } else if (projectionStop == ProjectionStop.Attempted) {
            val failure = projectionStopFailure ?: CapturePhysicalException("MediaProjection stop remains unproved")
            cleanupFailure = cleanupFailure ?: failure
        }

        val residue = when (callbackRegistration) {
            CallbackRegistration.Attempted,
            CallbackRegistration.Registered,
            CallbackRegistration.UnregisterAttempted,
                -> callbackUnregisterFailure ?: CapturePhysicalException("Projection callback remains registered")

            CallbackRegistration.Prepared,
            CallbackRegistration.Unregistered,
                -> if (projectionStop != ProjectionStop.Stopped) {
                projectionStopFailure ?: CapturePhysicalException("MediaProjection remains owned")
            } else {
                null
            }
        }
        return ProjectionRetirementOutcome(cleanupFailure, residue)
    }
}
