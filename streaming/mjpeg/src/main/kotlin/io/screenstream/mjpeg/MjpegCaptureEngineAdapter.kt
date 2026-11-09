package io.screenstream.mjpeg

import android.content.Context
import android.media.projection.MediaProjection
import io.screenstream.capture.EncodedFrame
import io.screenstream.capture.FrameConsumerRegistration
import io.screenstream.capture.ScreenCaptureConfig
import io.screenstream.capture.ScreenCaptureEngine
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.ScreenCaptureSession
import io.screenstream.capture.ScreenCaptureState
import io.screenstream.capture.ScreenCaptureStats
import io.screenstream.capture.ScreenCaptureStopReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** Successful creation transfers projection ownership; a thrown construction step leaves it with the caller. */
internal fun interface MjpegEngineFactory {
    fun create(context: Context, mediaProjection: MediaProjection, config: ScreenCaptureConfig): MjpegEngineSession
}

/** Only the Engine state facts used by the MJPEG capture adapter. */
internal data class MjpegEngineState(
    val phase: Phase,
    val problem: ScreenCaptureProblem? = null,
    val stopReason: ScreenCaptureStopReason? = null,
) {
    enum class Phase {
        NotStarted,
        Starting,
        Active,
        Reconfiguring,
        Suspended,
        Stopped,
        Failed,
    }
}

internal interface MjpegEngineSession {
    val currentState: MjpegEngineState
    val states: Flow<MjpegEngineState>
    val stats: StateFlow<ScreenCaptureStats>

    suspend fun start(parameters: ScreenCaptureParameters)
    fun updateParameters(parameters: ScreenCaptureParameters)
    fun registerFrameConsumer(consumer: (MjpegBorrowedJpeg) -> Unit): MjpegFrameRegistration
    suspend fun stop()
}

/** Access is borrowed for the receiving callback body on its thread; never retain this frame. */
internal interface MjpegBorrowedJpeg {
    val byteCount: Int
    fun copyTo(destination: ByteArray): Int
}

/** Await exact consumer completion independently of media stop. */
internal fun interface MjpegFrameRegistration {
    suspend fun unregister()
}

internal object DefaultMjpegEngineFactory : MjpegEngineFactory {
    override fun create(context: Context, mediaProjection: MediaProjection, config: ScreenCaptureConfig): MjpegEngineSession {
        // Allocate the forwarding session and its Flow before the Engine adopts the projection.
        val session = ForwardingMjpegEngineSession()
        session.engine = ScreenCaptureEngine.createSession(context, mediaProjection, config)
        return session
    }
}

private class ForwardingMjpegEngineSession : MjpegEngineSession {
    lateinit var engine: ScreenCaptureSession

    override val currentState: MjpegEngineState
        get() = engine.state.value.toMjpegState()

    override val states: Flow<MjpegEngineState> = flow {
        engine.state.collect { emit(it.toMjpegState()) }
    }

    override val stats: StateFlow<ScreenCaptureStats>
        get() = engine.stats

    override suspend fun start(parameters: ScreenCaptureParameters): Unit = engine.start(parameters)

    override fun updateParameters(parameters: ScreenCaptureParameters): Unit = engine.updateParameters(parameters)

    override fun registerFrameConsumer(consumer: (MjpegBorrowedJpeg) -> Unit): MjpegFrameRegistration {
        // Retain the exact registration without allocating another handle after registration succeeds.
        val registration = ForwardingMjpegFrameRegistration()
        registration.engineRegistration = engine.registerFrameConsumer { frame ->
            consumer(ForwardingMjpegBorrowedJpeg(frame))
        }
        return registration
    }

    override suspend fun stop(): Unit = engine.stop()
}

private class ForwardingMjpegFrameRegistration : MjpegFrameRegistration {
    lateinit var engineRegistration: FrameConsumerRegistration

    override suspend fun unregister(): Unit = engineRegistration.unregister()
}

private class ForwardingMjpegBorrowedJpeg(private val frame: EncodedFrame) : MjpegBorrowedJpeg {
    override val byteCount: Int
        get() = frame.byteCount

    override fun copyTo(destination: ByteArray): Int = frame.copyTo(destination)
}

private fun ScreenCaptureState.toMjpegState(): MjpegEngineState = when (this) {
    ScreenCaptureState.NotStarted -> MjpegEngineState(MjpegEngineState.Phase.NotStarted)
    ScreenCaptureState.Starting -> MjpegEngineState(MjpegEngineState.Phase.Starting)
    is ScreenCaptureState.Active -> MjpegEngineState(MjpegEngineState.Phase.Active)
    is ScreenCaptureState.Reconfiguring -> MjpegEngineState(MjpegEngineState.Phase.Reconfiguring)
    is ScreenCaptureState.Suspended -> MjpegEngineState(MjpegEngineState.Phase.Suspended, problem = problem)
    is ScreenCaptureState.Stopped -> MjpegEngineState(MjpegEngineState.Phase.Stopped, stopReason = reason)
    is ScreenCaptureState.Failed -> MjpegEngineState(MjpegEngineState.Phase.Failed, problem = problem)
}
