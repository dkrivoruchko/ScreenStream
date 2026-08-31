package io.screenstream.capture.internal.session

import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread

/**
 * Finite ownership ledger for the accepted projection and lane roots before the first Control task transfers them.
 *
 * Every root has one monotone transfer-or-cutoff winner. Queue acceptance is not entry, so the first Control task and
 * its dependencies remain rooted until real entry or cutoff. Cleanup after cutoff claims only roots still owned by
 * Bootstrap and is never a Session terminal or physical-release receipt.
 */
internal class BootstrapOwnership {
    internal enum class LaneStartDecision { Admitted, Cutoff, }

    internal enum class Lane { Control, Capture, }

    internal sealed interface FirstControlEntry {
        class Entered(internal val acceptedProjection: MediaProjection) : FirstControlEntry
        data object CutoffInert : FirstControlEntry
    }

    internal class ProjectionStopClaim(internal val projection: MediaProjection)

    internal class LaneQuitClaim(internal val lane: Lane, internal val thread: HandlerThread)

    private enum class FirstControlEntryState { Pending, Entered, CutoffInert, }

    private enum class ProjectionStopState { NotOwned, Owned, StopAttempted, StopFailed, }

    private enum class LaneState { Absent, Constructed, StartCalling, Started, StartFailed, QuitCalling, QuitRetained, }

    private enum class FirstControlPostState { Absent, Rooted, Calling, EntryObservedDuringCall, AwaitingEntry, Settled, }

    private val gate = Any()

    private var firstControlEntryState: FirstControlEntryState = FirstControlEntryState.Pending
    private var projectionStopState: ProjectionStopState = ProjectionStopState.NotOwned
    private var firstControlPostState: FirstControlPostState = FirstControlPostState.Absent

    private var acceptedProjection: MediaProjection? = null

    private var controlThread: HandlerThread? = null
    private var controlHandler: Handler? = null
    private var controlLaneState: LaneState = LaneState.Absent

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var captureLaneState: LaneState = LaneState.Absent

    private var firstControlExecutor: SessionControlExecutor? = null
    private var firstControlTask: Runnable? = null
    private var preparedFirstControlEntry: FirstControlEntry.Entered? = null

    internal fun adoptAcceptedProjection(mediaProjection: MediaProjection) = synchronized(gate) {
        check(firstControlEntryState == FirstControlEntryState.Pending)
        check(projectionStopState == ProjectionStopState.NotOwned)
        acceptedProjection = mediaProjection
        projectionStopState = ProjectionStopState.Owned
    }

    internal fun makeCutoffInert(): Boolean = synchronized(gate) {
        if (firstControlEntryState != FirstControlEntryState.Pending) return false
        firstControlEntryState = FirstControlEntryState.CutoffInert
        return true
    }

    internal fun recordControlThread(thread: HandlerThread) = synchronized(gate) {
        check((firstControlEntryState == FirstControlEntryState.Pending) || (firstControlEntryState == FirstControlEntryState.CutoffInert))
        check((controlLaneState == LaneState.Absent && controlThread == null))
        controlThread = thread
        controlLaneState = LaneState.Constructed
    }

    internal fun claimControlThreadStart(thread: HandlerThread): LaneStartDecision =
        synchronized(gate) { claimLaneStart(Lane.Control, thread) }

    internal fun recordControlThreadStarted(thread: HandlerThread) = synchronized(gate) {
        check(controlThread === thread && controlLaneState == LaneState.StartCalling)
        controlLaneState = LaneState.Started
    }

    internal fun recordControlThreadStartFailure(thread: HandlerThread) = synchronized(gate) {
        check(controlThread === thread && controlLaneState == LaneState.StartCalling)
        controlLaneState = LaneState.StartFailed
    }

    internal fun recordControlHandler(thread: HandlerThread, handler: Handler) = synchronized(gate) {
        check(controlThread === thread)
        check(controlLaneState == LaneState.Started || controlLaneState == LaneState.QuitCalling || controlLaneState == LaneState.QuitRetained)
        check(controlHandler == null)
        controlHandler = handler
    }

    internal fun recordCaptureThread(thread: HandlerThread) = synchronized(gate) {
        check(firstControlEntryState == FirstControlEntryState.Pending || firstControlEntryState == FirstControlEntryState.CutoffInert)
        check(captureLaneState == LaneState.Absent && captureThread == null)
        captureThread = thread
        captureLaneState = LaneState.Constructed
    }

    internal fun claimCaptureThreadStart(thread: HandlerThread): LaneStartDecision =
        synchronized(gate) { claimLaneStart(Lane.Capture, thread) }

    internal fun recordCaptureThreadStarted(thread: HandlerThread) = synchronized(gate) {
        check(captureThread === thread && captureLaneState == LaneState.StartCalling)
        captureLaneState = LaneState.Started
    }

    internal fun recordCaptureThreadStartFailure(thread: HandlerThread) = synchronized(gate) {
        check(captureThread === thread && captureLaneState == LaneState.StartCalling)
        captureLaneState = LaneState.StartFailed
    }

    internal fun recordCaptureHandler(thread: HandlerThread, handler: Handler) = synchronized(gate) {
        check(captureThread === thread)
        check(captureLaneState == LaneState.Started || captureLaneState == LaneState.QuitCalling || captureLaneState == LaneState.QuitRetained)
        check(captureHandler == null)
        captureHandler = handler
    }

    internal fun rootFirstControlTask(executor: SessionControlExecutor, task: Runnable) = synchronized(gate) {
        check(firstControlEntryState == FirstControlEntryState.Pending || firstControlEntryState == FirstControlEntryState.CutoffInert)
        check(firstControlPostState == FirstControlPostState.Absent)
        check(firstControlExecutor == null && firstControlTask == null && preparedFirstControlEntry == null)
        val preparedEntry = if (firstControlEntryState == FirstControlEntryState.Pending) {
            FirstControlEntry.Entered(prepareTransfer())
        } else {
            null
        }
        firstControlExecutor = executor
        firstControlTask = task
        preparedFirstControlEntry = preparedEntry
        firstControlPostState = FirstControlPostState.Rooted
    }

    internal fun recordFirstControlPostAttempt(executor: SessionControlExecutor, task: Runnable) = synchronized(gate) {
        check(firstControlExecutor === executor && firstControlTask === task)
        check(firstControlPostState == FirstControlPostState.Rooted)
        firstControlPostState = FirstControlPostState.Calling
    }

    internal fun recordFirstControlPostResult(executor: SessionControlExecutor, task: Runnable, accepted: Boolean) = synchronized(gate) {
        check(firstControlExecutor === executor && firstControlTask === task)
        when (firstControlPostState) {
            FirstControlPostState.EntryObservedDuringCall -> settleFirstControlRoots()

            FirstControlPostState.Calling -> if (accepted) {
                firstControlPostState = FirstControlPostState.AwaitingEntry
            } else {
                settleFirstControlRoots()
                check(firstControlEntryState != FirstControlEntryState.Entered)
            }

            else -> error("First Control post returned from an invalid state: $firstControlPostState")
        }
    }

    internal fun recordFirstControlPostException(executor: SessionControlExecutor, task: Runnable) = synchronized(gate) {
        check(firstControlExecutor === executor && firstControlTask === task)
        when (firstControlPostState) {
            FirstControlPostState.EntryObservedDuringCall -> settleFirstControlRoots()
            FirstControlPostState.Calling -> firstControlPostState = FirstControlPostState.AwaitingEntry
            else -> error("First Control post threw from an invalid state: $firstControlPostState")
        }
    }

    internal fun discardUnpostedFirstControlTask(executor: SessionControlExecutor, task: Runnable) = synchronized(gate) {
        check(firstControlEntryState == FirstControlEntryState.CutoffInert)
        check(firstControlExecutor === executor && firstControlTask === task)
        check(firstControlPostState == FirstControlPostState.Rooted)
        settleFirstControlRoots()
    }

    internal fun enterFirstControlTask(executor: SessionControlExecutor, task: Runnable): FirstControlEntry = synchronized(gate) {
        check(firstControlExecutor === executor && firstControlTask === task)
        check(firstControlPostState == FirstControlPostState.Calling || firstControlPostState == FirstControlPostState.AwaitingEntry) {
            "First Control task entered from an invalid post state: $firstControlPostState"
        }

        if (firstControlPostState == FirstControlPostState.Calling) {
            firstControlPostState = FirstControlPostState.EntryObservedDuringCall
        }

        val entry = when (firstControlEntryState) {
            FirstControlEntryState.Pending -> checkNotNull(preparedFirstControlEntry)
            FirstControlEntryState.CutoffInert -> FirstControlEntry.CutoffInert
            FirstControlEntryState.Entered -> error("First Control task entered more than once")
        }
        if (firstControlPostState == FirstControlPostState.AwaitingEntry) {
            settleFirstControlRoots()
        }
        return entry
    }

    internal fun commitFirstControlTransfer() = synchronized(gate) {
        check(firstControlEntryState == FirstControlEntryState.Pending)
        check(firstControlPostState == FirstControlPostState.EntryObservedDuringCall || firstControlPostState == FirstControlPostState.Settled)
        firstControlEntryState = FirstControlEntryState.Entered
        projectionStopState = ProjectionStopState.NotOwned
        preparedFirstControlEntry = null
        acceptedProjection = null
        controlThread = null
        controlHandler = null
        controlLaneState = LaneState.Absent
        captureThread = null
        captureHandler = null
        captureLaneState = LaneState.Absent
    }

    internal fun claimProjectionStop(): ProjectionStopClaim? = synchronized(gate) {
        if (firstControlEntryState != FirstControlEntryState.CutoffInert || projectionStopState != ProjectionStopState.Owned) {
            return null
        }
        val projection = checkNotNull(acceptedProjection)
        projectionStopState = ProjectionStopState.StopAttempted
        return ProjectionStopClaim(projection)
    }

    internal fun recordProjectionStopReturned(claim: ProjectionStopClaim) = synchronized(gate) {
        check(projectionStopState == ProjectionStopState.StopAttempted)
        check(acceptedProjection === claim.projection)
        acceptedProjection = null
        projectionStopState = ProjectionStopState.NotOwned
    }

    internal fun recordProjectionStopFailure(claim: ProjectionStopClaim) = synchronized(gate) {
        check(projectionStopState == ProjectionStopState.StopAttempted)
        check(acceptedProjection === claim.projection)
        projectionStopState = ProjectionStopState.StopFailed
    }

    internal fun claimControlLaneQuit(): LaneQuitClaim? = synchronized(gate) { claimLaneQuit(Lane.Control) }

    internal fun claimCaptureLaneQuit(): LaneQuitClaim? = synchronized(gate) { claimLaneQuit(Lane.Capture) }

    internal fun recordLaneQuitReturned(claim: LaneQuitClaim, quitRequested: Boolean) = synchronized(gate) {
        when (claim.lane) {
            Lane.Control -> {
                check(controlThread === claim.thread && controlLaneState == LaneState.QuitCalling)
                controlLaneState = LaneState.QuitRetained
                if (!quitRequested) {
                    if (firstControlEntryState == FirstControlEntryState.CutoffInert &&
                        firstControlPostState == FirstControlPostState.AwaitingEntry
                    ) {
                        settleFirstControlRoots()
                    }
                }
            }

            Lane.Capture -> {
                check(captureThread === claim.thread && captureLaneState == LaneState.QuitCalling)
                captureLaneState = LaneState.QuitRetained
            }
        }
    }

    internal fun recordLaneQuitFailure(claim: LaneQuitClaim) = synchronized(gate) {
        when (claim.lane) {
            Lane.Control -> {
                check(controlThread === claim.thread && controlLaneState == LaneState.QuitCalling)
                controlLaneState = LaneState.QuitRetained
            }

            Lane.Capture -> {
                check(captureThread === claim.thread && captureLaneState == LaneState.QuitCalling)
                captureLaneState = LaneState.QuitRetained
            }
        }
    }

    private fun prepareTransfer(): MediaProjection {
        check(firstControlEntryState == FirstControlEntryState.Pending)
        check(projectionStopState == ProjectionStopState.Owned)
        check(controlLaneState == LaneState.Started)
        check(captureLaneState == LaneState.Started)
        checkNotNull(controlThread)
        checkNotNull(controlHandler)
        checkNotNull(captureThread)
        checkNotNull(captureHandler)
        return checkNotNull(acceptedProjection)
    }

    private fun claimLaneQuit(lane: Lane): LaneQuitClaim? {
        if (firstControlEntryState != FirstControlEntryState.CutoffInert) return null
        val thread: HandlerThread
        when (lane) {
            Lane.Control -> {
                if (firstControlPostState == FirstControlPostState.Rooted || firstControlPostState == FirstControlPostState.Calling) {
                    return null
                }
                if (controlLaneState != LaneState.Constructed && controlLaneState != LaneState.Started && controlLaneState != LaneState.StartFailed) {
                    return null
                }
                thread = checkNotNull(controlThread)
                controlLaneState = LaneState.QuitCalling
            }

            Lane.Capture -> {
                if (captureLaneState != LaneState.Constructed && captureLaneState != LaneState.Started && captureLaneState != LaneState.StartFailed) {
                    return null
                }
                thread = checkNotNull(captureThread)
                captureLaneState = LaneState.QuitCalling
            }
        }
        return LaneQuitClaim(lane, thread)
    }

    private fun claimLaneStart(lane: Lane, thread: HandlerThread): LaneStartDecision {
        return when (firstControlEntryState) {
            FirstControlEntryState.Pending -> {
                when (lane) {
                    Lane.Control -> {
                        check(controlThread === thread && controlLaneState == LaneState.Constructed)
                        controlLaneState = LaneState.StartCalling
                    }

                    Lane.Capture -> {
                        check(captureThread === thread && captureLaneState == LaneState.Constructed)
                        captureLaneState = LaneState.StartCalling
                    }
                }
                LaneStartDecision.Admitted
            }

            FirstControlEntryState.CutoffInert -> LaneStartDecision.Cutoff
            FirstControlEntryState.Entered -> error("$lane lane start claimed after Bootstrap ownership ended")
        }
    }

    private fun settleFirstControlRoots() {
        firstControlPostState = FirstControlPostState.Settled
        firstControlExecutor = null
        firstControlTask = null
        preparedFirstControlEntry = null
    }
}
