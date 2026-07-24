package io.screenstream.engine.internal.session

import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.screenstream.engine.ScreenCaptureParameters

internal enum class BootstrapEntryState {
    Queued,
    Entered,
    CutoffInert,
    Closed,
}

/** Finite, one-shot owner for accepted authority and every partial Handler-lane construction prefix. */
internal class BootstrapCapsule internal constructor() {
    internal var entryState: BootstrapEntryState = BootstrapEntryState.Queued
        private set
    internal var acceptedProjection: MediaProjection? = null
        private set
    internal var acceptedParameters: ScreenCaptureParameters? = null
        private set
    internal var acceptedConfigRevision: Long = 0L
        private set
    internal var controlThread: HandlerThread? = null
        private set
    internal var controlThreadStarted: Boolean = false
        private set
    internal var controlLooper: Looper? = null
        private set
    internal var controlHandler: Handler? = null
        private set
    internal var captureThread: HandlerThread? = null
        private set
    internal var captureThreadStarted: Boolean = false
        private set
    internal var captureLooper: Looper? = null
        private set
    internal var captureHandler: Handler? = null
        private set
    internal var firstControlPostReturned: Boolean = false
        private set
    internal var firstControlPostAccepted: Boolean? = null
        private set
    internal var authorityTransferred: Boolean = false
        private set
    internal var retirementStarted: Boolean = false
        private set
    internal var captureRetirementCause: Exception? = null
        private set
    internal var controlRetirementCause: Exception? = null
        private set

    internal fun adoptAcceptedProjection(
        mediaProjection: MediaProjection,
        parameters: ScreenCaptureParameters,
        configRevision: Long,
    ) {
        check(entryState == BootstrapEntryState.Queued)
        check(acceptedProjection == null)
        require(configRevision > 0L)
        acceptedProjection = mediaProjection
        acceptedParameters = parameters
        acceptedConfigRevision = configRevision
    }

    internal fun enter(): Boolean {
        if (entryState != BootstrapEntryState.Queued) return false
        entryState = BootstrapEntryState.Entered
        return true
    }

    internal fun makeCutoffInert(): Boolean {
        if (entryState != BootstrapEntryState.Queued) return false
        entryState = BootstrapEntryState.CutoffInert
        return true
    }

    internal fun recordControlThread(thread: HandlerThread) {
        check(entryState == BootstrapEntryState.Entered && controlThread == null)
        controlThread = thread
    }

    internal fun recordControlThreadStarted(thread: HandlerThread) {
        check(controlThread === thread)
        controlThreadStarted = true
    }

    internal fun recordControlLooper(thread: HandlerThread, looper: Looper) {
        check(controlThread === thread && controlThreadStarted && controlLooper == null)
        controlLooper = looper
    }

    internal fun recordControlHandler(looper: Looper, handler: Handler) {
        check(controlLooper === looper && controlHandler == null)
        controlHandler = handler
    }

    internal fun recordCaptureThread(thread: HandlerThread) {
        check(entryState == BootstrapEntryState.Entered && captureThread == null)
        captureThread = thread
    }

    internal fun recordCaptureThreadStarted(thread: HandlerThread) {
        check(captureThread === thread)
        captureThreadStarted = true
    }

    internal fun recordCaptureLooper(thread: HandlerThread, looper: Looper) {
        check(captureThread === thread && captureThreadStarted && captureLooper == null)
        captureLooper = looper
    }

    internal fun recordCaptureHandler(looper: Looper, handler: Handler) {
        check(captureLooper === looper && captureHandler == null)
        captureHandler = handler
    }

    internal fun recordFirstControlPost(accepted: Boolean) {
        firstControlPostReturned = true
        firstControlPostAccepted = accepted
    }

    internal fun transferAuthority(): BootstrapTransfer {
        check(entryState == BootstrapEntryState.Entered)
        check(!authorityTransferred)
        val transfer = BootstrapTransfer(
            acceptedProjection = checkNotNull(acceptedProjection),
            acceptedParameters = checkNotNull(acceptedParameters),
            acceptedConfigRevision = acceptedConfigRevision,
            controlThread = checkNotNull(controlThread),
            controlHandler = checkNotNull(controlHandler),
            captureThread = checkNotNull(captureThread),
            captureHandler = checkNotNull(captureHandler),
        )
        authorityTransferred = true
        acceptedProjection = null
        acceptedParameters = null
        acceptedConfigRevision = 0L
        controlThread = null
        controlLooper = null
        controlHandler = null
        captureThread = null
        captureLooper = null
        captureHandler = null
        entryState = BootstrapEntryState.Closed
        return transfer
    }

    internal fun takeProjectionForCaptureRetirement(): MediaProjection? {
        val projection = acceptedProjection ?: return null
        acceptedProjection = null
        acceptedParameters = null
        acceptedConfigRevision = 0L
        return projection
    }

    internal fun claimRetirement(): BootstrapLaneRetirement? {
        if (authorityTransferred || retirementStarted) return null
        retirementStarted = true
        if (!captureThreadStarted) {
            captureThread = null
            captureLooper = null
            captureHandler = null
        }
        if (!controlThreadStarted) {
            controlThread = null
            controlLooper = null
            controlHandler = null
        }
        return BootstrapLaneRetirement(
            captureThread = captureThread,
            controlThread = controlThread,
        )
    }

    internal fun closeCutoffInertWithoutLanes() {
        if (entryState != BootstrapEntryState.CutoffInert || acceptedProjection != null) return
        check(!controlThreadStarted && !captureThreadStarted)
        controlThread = null
        captureThread = null
        retirementStarted = true
        entryState = BootstrapEntryState.Closed
    }

    internal fun recordRetirement(
        retiredCaptureThread: HandlerThread?,
        retiredControlThread: HandlerThread?,
        captureCause: Exception?,
        controlCause: Exception?,
    ) {
        captureRetirementCause = captureCause
        controlRetirementCause = controlCause
        if (retiredCaptureThread != null && captureThread === retiredCaptureThread) {
            captureThread = null
            captureLooper = null
            captureHandler = null
            captureThreadStarted = false
        }
        if (retiredControlThread != null && controlThread === retiredControlThread) {
            controlThread = null
            controlLooper = null
            controlHandler = null
            controlThreadStarted = false
        }
        if (acceptedProjection == null && captureThread == null && controlThread == null) {
            entryState = BootstrapEntryState.Closed
        }
    }
}

internal class BootstrapLaneRetirement internal constructor(
    internal val captureThread: HandlerThread?,
    internal val controlThread: HandlerThread?,
)

internal class BootstrapTransfer internal constructor(
    internal val acceptedProjection: MediaProjection,
    internal val acceptedParameters: ScreenCaptureParameters,
    internal val acceptedConfigRevision: Long,
    internal val controlThread: HandlerThread,
    internal val controlHandler: Handler,
    internal val captureThread: HandlerThread,
    internal val captureHandler: Handler,
)
