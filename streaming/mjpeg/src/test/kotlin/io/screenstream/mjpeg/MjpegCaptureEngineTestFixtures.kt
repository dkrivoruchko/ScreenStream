package io.screenstream.mjpeg

import android.media.projection.MediaProjection
import io.screenstream.capture.ScreenCaptureConfig
import io.screenstream.capture.ScreenCaptureStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

internal fun initialEngineStatistics(): ScreenCaptureStats {
    val engine = DefaultMjpegEngineFactory.create(
        RuntimeEnvironment.getApplication(), Shadow.newInstanceOf(MediaProjection::class.java), ScreenCaptureConfig(),
    )
    val statistics = engine.stats.value
    runBlocking(Dispatchers.Default) { withTimeout(5.seconds) { engine.stop() } }
    return statistics
}

internal class ControlledMjpegEngineSession(
    holdStart: Boolean = false,
    holdStop: Boolean = false,
    holdConsumer: Boolean = false,
) : MjpegEngineSession {
    private val observations = MutableSharedFlow<MjpegEngineState>(replay = 1, extraBufferCapacity = 16)
    override var currentState = MjpegEngineState(MjpegEngineState.Phase.NotStarted)
        private set
    override val states: Flow<MjpegEngineState> = observations
    override val stats: StateFlow<ScreenCaptureStats> = MutableStateFlow(initialEngineStatistics())
    val startEntered = CompletableDeferred<Unit>()
    val allowStartToReturn = CompletableDeferred<Unit>().also { if (!holdStart) it.complete(Unit) }
    val stopEntered = CompletableDeferred<Unit>()
    val allowStopToReturn = CompletableDeferred<Unit>().also { if (!holdStop) it.complete(Unit) }
    val consumerUnregisterEntered = CompletableDeferred<Unit>()
    val allowConsumerUnregisterToReturn = CompletableDeferred<Unit>().also { if (!holdConsumer) it.complete(Unit) }
    var startFailure: Throwable? = null
    var stopFailure: Throwable? = null
    var updateFailure: Throwable? = null
    var registerFailure: Throwable? = null
    var initialParameters: io.screenstream.capture.ScreenCaptureParameters? = null
    val parameterUpdates = CopyOnWriteArrayList<io.screenstream.capture.ScreenCaptureParameters>()
    private var consumer: ((MjpegBorrowedJpeg) -> Unit)? = null

    init {
        observations.tryEmit(currentState)
    }

    override suspend fun start(parameters: io.screenstream.capture.ScreenCaptureParameters) {
        initialParameters = parameters
        startEntered.complete(Unit)
        startFailure?.let { throw it }
        publish(MjpegEngineState(MjpegEngineState.Phase.Active))
        allowStartToReturn.await()
    }

    override fun updateParameters(parameters: io.screenstream.capture.ScreenCaptureParameters) {
        updateFailure?.let { throw it }
        parameterUpdates += parameters
    }

    override fun registerFrameConsumer(consumer: (MjpegBorrowedJpeg) -> Unit): MjpegFrameRegistration {
        registerFailure?.let { throw it }
        this.consumer = consumer
        return MjpegFrameRegistration {
            this.consumer = null
            consumerUnregisterEntered.complete(Unit)
            allowConsumerUnregisterToReturn.await()
        }
    }

    override suspend fun stop() {
        stopEntered.complete(Unit)
        allowStopToReturn.await()
        stopFailure?.let { throw it }
        if (currentState.phase != MjpegEngineState.Phase.Failed && currentState.phase != MjpegEngineState.Phase.Stopped) {
            publish(MjpegEngineState(MjpegEngineState.Phase.Stopped, stopReason = io.screenstream.capture.ScreenCaptureStopReason.Requested))
        }
    }

    fun publish(state: MjpegEngineState, announce: Boolean = true) {
        currentState = state
        if (announce) check(observations.tryEmit(state))
    }

    fun announce(state: MjpegEngineState) {
        check(observations.tryEmit(state))
    }

    fun deliver(payload: ByteArray) {
        val callback = checkNotNull(consumer)
        val thread = Thread.currentThread()
        var entered = true
        try {
            callback(object : MjpegBorrowedJpeg {
                override val byteCount: Int
                    get() {
                        check(entered && Thread.currentThread() === thread)
                        return payload.size
                    }

                override fun copyTo(destination: ByteArray): Int {
                    check(entered && Thread.currentThread() === thread)
                    require(destination.size >= payload.size)
                    payload.copyInto(destination)
                    return payload.size
                }
            })
        } finally {
            entered = false
        }
    }

    fun releaseAllGates() {
        allowStartToReturn.complete(Unit)
        allowStopToReturn.complete(Unit)
        allowConsumerUnregisterToReturn.complete(Unit)
    }
}

@org.robolectric.annotation.Implements(MediaProjection::class)
internal class InertMjpegProjectionShadow {
    @org.robolectric.annotation.Implementation
    protected fun stop() = Unit
}
