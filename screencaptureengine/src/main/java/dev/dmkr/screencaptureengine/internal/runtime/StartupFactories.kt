package dev.dmkr.screencaptureengine.internal.runtime

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
