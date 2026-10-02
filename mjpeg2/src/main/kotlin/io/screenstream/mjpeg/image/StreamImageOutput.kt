package io.screenstream.mjpeg.image

import android.content.res.AssetManager
import io.screenstream.capture.EncodedFrame
import io.screenstream.mjpeg.capture.MjpegCaptureAttempt
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.settings.StreamBehaviorSettings
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the capture source and stopped-image policy. Reservations only change local state and mint
 * immutable ordered publications; callers apply their HTTP effects outside controller/attempt gates.
 * HTTP owns live JPEG storage. Borrowed frames are copied synchronously on their callback thread.
 * One lazy asset read serves placeholder jobs, each tied to the publication that requested it.
 */
internal class StreamImageOutput(
    private val http: HttpDelivery,
    assets: AssetManager,
    private val onFailure: (Throwable) -> Unit,
) {
    private val gate = Any()
    private val workJob = SupervisorJob()
    private val scope = CoroutineScope(workJob + Dispatchers.Default)
    private val stoppedImage = scope.async(start = CoroutineStart.LAZY) {
        assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
    }
    private var owner: StreamingModule.CaptureAttemptId? = null
    private var mode = Mode.Stopped
    private var publication: HttpDelivery.Publication? = null
    private var policy = StreamBehaviorSettings.PostStopImage.Placeholder
    private var stoppedImageAllowed = false
    private var placeholderJob: Job? = null

    /** Inert source reservation; a delayed effect cannot reopen an attempt stopped in the meantime. */
    fun reserveCapture(id: StreamingModule.CaptureAttemptId): HttpDelivery.Publication? = synchronized(gate) {
        if (mode == Mode.Closed) return null
        owner = id
        mode = Mode.Live
        stoppedImageAllowed = false
        reserve(HttpDelivery.ImageKind.StreamFrame, clear = false)
    }

    fun offerFrame(id: StreamingModule.CaptureAttemptId, frame: EncodedFrame) {
        val current = synchronized(gate) {
            if (owner != id || mode != Mode.Live) return
            checkNotNull(publication)
        }
        http.updatePublication(current)
        http.offerJpeg(current, frame.byteCount, frame::copyTo)
    }

    /** Called at unavailable-event acceptance, without entering HTTP or any external callback. */
    fun reserveUnavailable(id: StreamingModule.CaptureAttemptId, reason: MjpegCaptureAttempt.UnavailableReason): HttpDelivery.Publication? = synchronized(gate) {
        if (owner != id || mode != Mode.Live && mode != Mode.Suspended) return null
        when (reason) {
            MjpegCaptureAttempt.UnavailableReason.Suspended -> {
                if (mode == Mode.Suspended) return null
                mode = Mode.Suspended
                reserve(kind = null, clear = true)
            }
            is MjpegCaptureAttempt.UnavailableReason.Stopped -> {
                val normal = reason.reason == MjpegCaptureAttempt.StopReason.User || reason.reason == MjpegCaptureAttempt.StopReason.System
                mode = if (normal) Mode.Stopped else Mode.Failed
                reserve(kind = null, clear = !normal || policy != StreamBehaviorSettings.PostStopImage.LastAvailableFrame)
            }
        }
    }

    /** Resume only this suspended source; a superseding Stop or capture cannot acquire live rights. */
    fun reserveResumed(id: StreamingModule.CaptureAttemptId): HttpDelivery.Publication? = synchronized(gate) {
        if (owner != id || mode != Mode.Suspended) return null
        mode = Mode.Live
        reserve(HttpDelivery.ImageKind.StreamFrame, clear = false)
    }

    /** Policy changes while live do not revoke frames or mint a publication revision. */
    fun reserveStopped(policy: StreamBehaviorSettings.PostStopImage, allowed: Boolean): HttpDelivery.Publication? = synchronized(gate) {
        if (mode == Mode.Closed || this.policy == policy && stoppedImageAllowed == allowed) return null
        this.policy = policy
        stoppedImageAllowed = allowed
        if (mode != Mode.Stopped) return null
        when {
            !allowed -> reserve(kind = null, clear = false)
            policy == StreamBehaviorSettings.PostStopImage.None -> reserve(kind = null, clear = true)
            policy == StreamBehaviorSettings.PostStopImage.LastAvailableFrame -> reserve(kind = null, clear = false)
            else -> reserve(HttpDelivery.ImageKind.Placeholder, clear = false)
        }
    }

    fun reserveFailure(id: StreamingModule.CaptureAttemptId): HttpDelivery.Publication? = synchronized(gate) {
        if (owner != id || mode == Mode.Closed || mode == Mode.Failed) return null
        mode = Mode.Failed
        reserve(kind = null, clear = true)
    }

    fun reserveClose(): HttpDelivery.Publication? = synchronized(gate) {
        if (mode == Mode.Closed) return null
        mode = Mode.Closed
        owner = null
        reserve(kind = null, clear = true)
    }

    /** Apply immutable effects in any execution order; HTTP rejects superseded revisions and copies. */
    fun apply(change: HttpDelivery.Publication?) {
        if (change == null) return
        if (!http.updatePublication(change)) return
        val (previous, next) = synchronized(gate) {
            if (publication !== change) return
            val previous = placeholderJob
            val next = if (change.kind == HttpDelivery.ImageKind.Placeholder) scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val bytes = stoppedImage.await()
                    http.offerJpeg(change, bytes.size) { destination, offset ->
                        bytes.copyInto(destination, offset)
                        bytes.size
                    }
                } catch (_: CancellationException) {
                    // The publication job or the entire owner has been stopped.
                } catch (failure: Throwable) {
                    if (synchronized(gate) { publication === change && mode != Mode.Closed }) onFailure(failure)
                }
            } else null
            placeholderJob = next
            previous to next
        }
        previous?.cancel()
        next?.start()
    }

    fun requestStop() {
        scope.cancel()
    }

    suspend fun awaitCleanup() {
        workJob.join()
    }

    /** Caller holds the local gate. A clear is carried forward in every later publication. */
    private fun reserve(kind: HttpDelivery.ImageKind?, clear: Boolean): HttpDelivery.Publication {
        val revision = Math.incrementExact(publication?.revision ?: 0L)
        return HttpDelivery.Publication(revision, if (clear) revision else publication?.clearRevision ?: 0L, kind).also {
            publication = it
        }
    }

    private enum class Mode { Live, Suspended, Stopped, Failed, Closed }
}
