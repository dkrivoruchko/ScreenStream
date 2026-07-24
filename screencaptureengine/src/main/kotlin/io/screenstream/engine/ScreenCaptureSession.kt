package io.screenstream.engine

import android.media.projection.MediaProjection
import io.screenstream.engine.internal.session.SessionFrontDoor
import io.screenstream.engine.internal.session.SubscriptionHandle
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

public class ScreenCaptureSession private constructor(
    private val frontDoor: SessionFrontDoor,
) {
    public val state: StateFlow<ScreenCaptureState>
        get() = frontDoor.state

    public val stats: StateFlow<ScreenCaptureStats>
        get() = frontDoor.stats

    public val diagnosticEvents: SharedFlow<ScreenCaptureDiagnosticEvent>
        get() = frontDoor.diagnosticEvents

    public suspend fun start(
        mediaProjection: MediaProjection,
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters(),
    ): Unit = frontDoor.start(mediaProjection, initialParameters)

    public fun updateParameters(parameters: ScreenCaptureParameters): Unit =
        frontDoor.updateParameters(parameters)

    public fun onFrame(callback: (EncodedImageFrame) -> Unit): FrameSubscription =
        FrameSubscription.create(frontDoor.registerFrameCallback(callback))

    public fun stop(): Unit = frontDoor.stop()

    internal companion object {
        @JvmSynthetic
        internal fun create(frontDoor: SessionFrontDoor): ScreenCaptureSession = ScreenCaptureSession(frontDoor)
    }
}

public class FrameSubscription private constructor(
    private val handle: SubscriptionHandle,
) {
    public suspend fun unsubscribe(): Unit = handle.unsubscribe()

    internal companion object {
        @JvmSynthetic
        internal fun create(handle: SubscriptionHandle): FrameSubscription = FrameSubscription(handle)
    }
}
