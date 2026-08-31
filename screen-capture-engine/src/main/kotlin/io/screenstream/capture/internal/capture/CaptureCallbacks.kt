package io.screenstream.capture.internal.capture

internal sealed interface CaptureCallbackIdentity {
    class Projection(internal val token: ProjectionOwner.Token) : CaptureCallbackIdentity

    class Target(internal val source: SourceCandidate.Token) : CaptureCallbackIdentity
}

internal interface CaptureCallbackBoundary {
    fun onCallbackException(identity: CaptureCallbackIdentity, failure: Exception)
}

internal inline fun runCaptureCallback(
    boundary: CaptureCallbackBoundary,
    identity: CaptureCallbackIdentity,
    block: () -> Unit,
) {
    try {
        block()
    } catch (failure: Exception) {
        try {
            boundary.onCallbackException(identity, failure)
        } catch (_: Exception) {
        }
    }
}
