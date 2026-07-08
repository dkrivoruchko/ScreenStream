package dev.dmkr.screencaptureengine.internal.startup

import dev.dmkr.screencaptureengine.internal.platform.projection.MediaProjectionCallbackAdapter
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCallbackHandlerHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCallbackRawEvent
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCallbackRegistration
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetOwnerHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionVirtualDisplayOwner

internal fun interface StartupCallbackAdapterFactory {
    fun create(
        listener: MediaProjectionCallbackAdapter.Listener,
        synchronousEventObserver: (ProjectionCallbackRawEvent) -> Unit,
    ): ProjectionCallbackRegistration
}

internal fun interface StartupProjectionTargetOwnerFactory {
    fun create(): ProjectionTargetOwnerHandle
}

internal fun interface StartupVirtualDisplayFactory {
    fun create(
        projection: ProjectionHandle,
        name: String,
        target: ProjectionTargetHandle,
        callbackHandler: ProjectionCallbackHandlerHandle?,
    ): ProjectionVirtualDisplayOwner
}

/**
 * Milestones reached by [ScreenCaptureStartupTransaction].
 *
 * The transaction records only the internal startup path through authoritative geometry. Rendering
 * pipeline readiness and initial Active commit are outside this milestone list.
 */
internal enum class ScreenCaptureStartupMilestone {
    ValidatedInputs,
    ProjectionTargetReady,
    ProjectionCallbackAttached,
    VirtualDisplayAttempted,
    VirtualDisplayOwned,
    AuthoritativeStartupGeometryReady,
}
