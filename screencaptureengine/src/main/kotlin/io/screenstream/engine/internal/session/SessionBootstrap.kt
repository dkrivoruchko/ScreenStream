package io.screenstream.engine.internal.session

import android.os.HandlerThread
import android.os.Looper
import io.screenstream.engine.internal.runtime.SessionExecutionComposition

internal class SessionBootstrap internal constructor(
    private val frontDoor: SessionFrontDoor,
    private val capsule: BootstrapCapsule,
    private val execution: SessionExecutionComposition,
) {
    internal fun dispatch() {
        val accepted = execution.bootstrapDispatcher.dispatch(
            Runnable {
                try {
                    runAttempt()
                } catch (exception: Exception) {
                    frontDoor.onBootstrapFailure(capsule, exception)
                    retireAfterCutoff()
                } catch (fatal: Throwable) {
                    frontDoor.onBootstrapFatal(capsule)
                    throw fatal
                }
            },
        )
        if (!accepted) {
            frontDoor.onBootstrapFailure(capsule, IllegalStateException("Bootstrap dispatch was rejected"))
            retireAfterCutoff()
        }
    }

    private fun runAttempt() {
        if (!frontDoor.enterBootstrap(capsule)) {
            retireAfterCutoff()
            return
        }

        val controlThread = execution.handlerLanes.newThread(CONTROL_THREAD_NAME)
        frontDoor.recordControlThread(capsule, controlThread)
        if (retireIfCutoff()) return
        execution.handlerLanes.start(controlThread)
        frontDoor.recordControlThreadStarted(capsule, controlThread)
        if (retireIfCutoff()) return
        val controlLooper = requireLooper(execution.handlerLanes.looper(controlThread), "Control")
        frontDoor.recordControlLooper(capsule, controlThread, controlLooper)
        if (retireIfCutoff()) return
        val controlHandler = execution.handlerLanes.handler(controlLooper)
        frontDoor.recordControlHandler(capsule, controlLooper, controlHandler)
        if (retireIfCutoff()) return

        val captureThread = execution.handlerLanes.newThread(CAPTURE_THREAD_NAME)
        frontDoor.recordCaptureThread(capsule, captureThread)
        if (retireIfCutoff()) return
        execution.handlerLanes.start(captureThread)
        frontDoor.recordCaptureThreadStarted(capsule, captureThread)
        if (retireIfCutoff()) return
        val captureLooper = requireLooper(execution.handlerLanes.looper(captureThread), "Capture")
        frontDoor.recordCaptureLooper(capsule, captureThread, captureLooper)
        if (retireIfCutoff()) return
        val captureHandler = execution.handlerLanes.handler(captureLooper)
        frontDoor.recordCaptureHandler(capsule, captureLooper, captureHandler)
        if (retireIfCutoff()) return

        val controlLoop = SessionControlLoop(
            frontDoor = frontDoor,
            observations = frontDoor.observations,
            metricsCapsule = frontDoor.metricsCapsule,
            captureCapsule = frontDoor.captureCapsule,
            encoderCapsule = frontDoor.encoderCapsule,
            deliveryCapsule = frontDoor.deliveryCapsule,
            serialRoles = frontDoor.serialRoles,
        )
        val firstTurn = Runnable { controlLoop.enterFirstTurn(capsule) }
        val postAccepted = controlHandler.post(firstTurn)
        frontDoor.recordFirstControlPost(capsule, postAccepted)
        retireIfCutoff()
    }

    private fun retireIfCutoff(): Boolean {
        if (!frontDoor.bootstrapCutoffWon(capsule)) return false
        retireAfterCutoff()
        return true
    }

    private fun retireAfterCutoff() {
        val retirement = frontDoor.claimBootstrapRetirement(capsule) ?: return
        val retiredCapture = retireLane(retirement.captureThread)
        val retiredControl = retireLane(retirement.controlThread)
        frontDoor.recordBootstrapRetirement(
            capsule = capsule,
            retiredCaptureThread = retiredCapture.retiredThread,
            retiredControlThread = retiredControl.retiredThread,
            captureCause = retiredCapture.cause,
            controlCause = retiredControl.cause,
        )
    }

    private fun retireLane(thread: HandlerThread?): LaneRetirementOutcome {
        if (thread == null) return LaneRetirementOutcome(null, null)
        return try {
            LaneRetirementOutcome(if (thread.quitSafely()) thread else null, null)
        } catch (exception: Exception) {
            LaneRetirementOutcome(null, exception)
        }
    }

    private fun requireLooper(looper: Looper?, lane: String): Looper =
        looper ?: throw IllegalStateException("$lane HandlerThread returned no Looper")

    private companion object {
        private const val CONTROL_THREAD_NAME = "ScreenCaptureEngine-Control"
        private const val CAPTURE_THREAD_NAME = "ScreenCaptureEngine-Capture"
    }
}

private class LaneRetirementOutcome(
    val retiredThread: HandlerThread?,
    val cause: Exception?,
)
