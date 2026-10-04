package io.screenstream.mjpeg.http.media

import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import io.screenstream.streaming.module.StreamingModule.CaptureAttemptId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Capture selection and pooled JPEG storage for one serialized producer and multiple readers.
 * Lock order is runtime control gate → store monitor; the store never calls runtime. Copies and
 * reader wakeups run outside this monitor. Leases keep bytes valid across reselection and disposal.
 */
internal class JpegStore {
    private val monitor = Any()
    private val changeRevision = MutableStateFlow(0L)
    private var closed = false
    private var captureId: CaptureAttemptId? = null
    private var captureStatus = Status.Stopped
    private var logoFrame: Frame? = null
    private var stoppedImagePolicy = PostStopImage.Placeholder

    private var selectedFrame: Frame? = null
    private var spareBuffer: ByteArray? = null
    private var captureSourceToken: Any? = null

    // Logo is installed before capture; subsequent increments count only accepted publications.
    private var lastPublicationVersion = 0L

    fun snapshot(): Snapshot = synchronized(monitor) { Snapshot(captureStatus, selectedFrame != null, captureSourceToken) }

    /** Transfer immutable logo bytes once before capture begins; the caller must not modify them. */
    fun installLogo(bytes: ByteArray) {
        synchronized(monitor) {
            check(!closed && logoFrame == null && captureId == null)
            logoFrame = Frame(bytes = bytes, byteCount = bytes.size, publicationVersion = ++lastPublicationVersion)
        }
    }

    /** Apply selection only while stopping or stopped; active capture keeps its selected image. */
    fun updateStoppedImagePolicy(policy: PostStopImage) {
        synchronized(monitor) {
            if (closed) return
            stoppedImagePolicy = policy
            selectStoppedImage()
        }
        wakeReaders()
    }

    /** Fence old producer copies while retaining selection and readers; false after disposal. */
    fun registerCapture(id: CaptureAttemptId): Boolean {
        synchronized(monitor) {
            if (closed) return false
            captureId = id
            captureSourceToken = Any()
            captureStatus = Status.Starting
        }
        wakeReaders()
        return true
    }

    /** Ignore stale attempts; capture transitions change selection without draining HTTP readers. */
    fun updateCaptureStatus(id: CaptureAttemptId, status: Status): Boolean {
        synchronized(monitor) {
            if (closed || captureId != id) return false
            captureStatus = status
            when (status) {
                Status.Active -> {
                    if (captureSourceToken == null) captureSourceToken = Any()
                }

                Status.Suspended, Status.Failed -> {
                    captureSourceToken = null
                    selectFrame(null)
                }

                Status.Stopping, Status.Stopped -> {
                    captureSourceToken = null
                    selectStoppedImage()
                }

                else -> Unit
            }
        }
        wakeReaders()
        return true
    }

    /**
     * [copyTo] runs at most once, synchronously outside the monitor, writing exactly [byteCount]
     * bytes from offset zero without retaining the array; [byteCount] must be positive.
     * Stale attempts/closed sources admit no copy; source replacement rejects publication.
     * Failed copies return eligible storage before rethrowing.
     * True means image availability changed, avoiding a web-state refresh for every JPEG.
     */
    fun offerJpeg(id: CaptureAttemptId, byteCount: Int, copyTo: (ByteArray) -> Unit): Boolean {
        val reservedSource: Any
        var copyBuffer = synchronized(monitor) {
            if (closed || captureId != id) return false
            reservedSource = captureSourceToken ?: return false
            val buffer = spareBuffer
            spareBuffer = null
            buffer
        }
        val imageChanged = try {
            require(byteCount > 0)
            val reservedBuffer = copyBuffer
            val canReuseBuffer = reservedBuffer != null && reservedBuffer.size >= byteCount && reservedBuffer.size.toLong() <= byteCount.toLong() * 2
            val buffer = if (canReuseBuffer) {
                reservedBuffer
            } else {
                ByteArray(byteCount)
            }
            copyBuffer = buffer
            copyTo(buffer)
            synchronized(monitor) {
                val previouslyHadImage = selectedFrame != null
                if (captureSourceToken === reservedSource) {
                    selectFrame(
                        PooledFrame(
                            bytes = buffer,
                            byteCount = byteCount,
                            publicationVersion = ++lastPublicationVersion,
                            captureSourceToken = reservedSource,
                        )
                    )
                } else {
                    recycleBuffer(buffer)
                }
                previouslyHadImage != (selectedFrame != null)
            }
        } catch (cause: Throwable) {
            synchronized(monitor) { copyBuffer?.let(::recycleBuffer) }
            throw cause
        }
        wakeReaders()
        return imageChanged
    }

    /**
     * Called during admission before headers to pin their JPEG. Null means closed or no image.
     */
    fun openReader(): Reader? = synchronized(monitor) {
        if (closed) return null
        val initialFrame = acquireSelectedFrame() ?: return null
        Reader(initialFrame)
    }

    /**
     * Permanently close producer/reader admission and drop owned storage. Runtime separately closes
     * reader handles and cancels jobs. Writer leases remain valid until release; pending copies
     * cannot publish or refill the pool.
     */
    fun dispose() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            captureId = null
            captureSourceToken = null
            selectFrame(null)
            logoFrame = null
            spareBuffer = null
        }
        wakeReaders()
    }

    private fun selectStoppedImage() {
        if (captureStatus != Status.Stopping && captureStatus != Status.Stopped) return
        when (stoppedImagePolicy) {
            PostStopImage.None -> selectFrame(null)
            PostStopImage.Placeholder -> selectFrame(logoFrame)
            PostStopImage.LastAvailableFrame -> Unit
        }
    }

    private fun selectFrame(next: Frame?) {
        if (selectedFrame === next) return
        val previous = selectedFrame
        selectedFrame = next
        if (previous is PooledFrame && previous.leaseCount == 0) {
            recycleBuffer(previous.bytes)
        }
    }

    // A resumed reader may enter runtime; never wake collectors while holding the store monitor.
    private fun wakeReaders() {
        changeRevision.update { it + 1 }
    }

    private fun recycleBuffer(bytes: ByteArray) {
        if (!closed && spareBuffer == null) spareBuffer = bytes
    }

    private fun acquireSelectedFrame(): Frame? {
        val frame = selectedFrame ?: return null
        if (frame is PooledFrame) frame.leaseCount++
        return frame
    }

    private fun releaseLease(frame: Frame) {
        if (frame !is PooledFrame) return
        frame.leaseCount--
        if (frame !== selectedFrame && frame.leaseCount == 0) {
            recycleBuffer(frame.bytes)
        }
    }

    /**
     * Read [bytes] only while holding an acquisition; never modify them. Only [byteCount] bytes
     * are valid. Null [captureSourceToken] identifies the logo. [publicationVersion] advances on
     * logo installation and accepted capture publications, not reselection or rejected copies.
     */
    open class Frame internal constructor(
        val bytes: ByteArray,
        val byteCount: Int,
        val publicationVersion: Long,
        val captureSourceToken: Any? = null,
    )

    private class PooledFrame(bytes: ByteArray, byteCount: Int, publicationVersion: Long, captureSourceToken: Any) :
        Frame(bytes, byteCount, publicationVersion, captureSourceToken) {
        var leaseCount = 0
    }

    /**
     * One response's initial lease pins the JPEG before headers. [close] can revoke it before
     * handoff under the store monitor; afterwards only the writer's try/finally releases it.
     */
    inner class Reader internal constructor(initialFrame: Frame) {
        private var readerClosed = false
        private var initialLease: Frame? = initialFrame

        /** Pinned header length survives initial handoff and selection changes. */
        val initialByteCount: Int = initialFrame.byteCount

        /** Wake-only revision: capture changes and reader cutoffs both unblock an empty-image wait. */
        val changeRevision: StateFlow<Long> get() = this@JpegStore.changeRevision

        val isClosed: Boolean
            get() = synchronized(monitor) { closed || readerClosed }

        /**
         * Transfer the initial lease or admit a selected image; every result needs one [releaseFrame].
         * On null, end if [isClosed], otherwise wait for [changeRevision]. Observe its revision before this
         * call so a concurrent publication or cutoff cannot leave the writer waiting for a lost wakeup.
         */
        fun acquireFrame(): Frame? = synchronized(monitor) {
            if (closed || readerClosed) return null
            val frame = initialLease
            if (frame != null) {
                initialLease = null
                return frame
            }
            acquireSelectedFrame()
        }

        fun releaseFrame(frame: Frame) {
            synchronized(monitor) { releaseLease(frame) }
        }

        /** Images from revoked capture sources and the logo cannot count as live capture recovery. */
        fun isCurrentCapture(frame: Frame): Boolean = synchronized(monitor) {
            !closed && frame.captureSourceToken != null && frame.captureSourceToken === captureSourceToken
        }

        /** Close all admission and release only the initial lease still awaiting writer handoff. */
        fun close() {
            synchronized(monitor) {
                if (readerClosed) return
                readerClosed = true
                initialLease?.let(::releaseLease)
                initialLease = null
            }
            wakeReaders()
        }
    }

    /** Availability is independent of capture phase; [sourceIdentity] fences obsolete slow observations. */
    data class Snapshot(val capture: Status, val hasImage: Boolean, val sourceIdentity: Any?)
}
