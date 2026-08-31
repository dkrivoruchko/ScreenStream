package io.screenstream.capture.internal.session

import android.media.projection.MediaProjection
import android.os.HandlerThread
import io.screenstream.capture.internal.capture.EglPlatform
import io.screenstream.capture.internal.capture.GlesPlatform
import io.screenstream.capture.internal.capture.ProjectionPlatform
import io.screenstream.capture.internal.capture.SessionCaptureOwner
import io.screenstream.capture.internal.capture.TargetPlatform
import io.screenstream.capture.internal.encoding.NativeJpegFacade
import io.screenstream.capture.internal.metrics.SessionMetricsOwner
import io.screenstream.capture.internal.metrics.SessionMetricsSourceSelection
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import io.screenstream.capture.internal.runtime.HandlerThreadPlatform
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import io.screenstream.capture.internal.runtime.ProductionRuntime

/**
 * Builds the fixed session graph and starts its Control and Capture lanes off the caller thread.
 *
 * Bootstrap owns every constructed prefix root until the exact first Control entry commits transfer through
 * [BootstrapOwnership]. A concurrent terminal cutoff leaves untransferred roots with Bootstrap for best-effort
 * retirement. Bootstrap has no lifecycle, publication, retry, or replacement-lane authority.
 */
internal class SessionBootstrap(
    private val coordinator: SessionCoordinator,
    private val ownership: BootstrapOwnership,
    private val workerDispatcher: NonInlineDispatcher,
    private val handlerThreadPlatform: HandlerThreadPlatform,
    private val handlerTaskPoster: HandlerTaskPoster,
    private val metricsSourceSelection: SessionMetricsSourceSelection,
    private val executionClock: ElapsedRealtimeClock,
    private val platformSdkInt: Int,
    private val projectionPlatform: ProjectionPlatform,
    private val eglPlatform: EglPlatform,
    private val glesPlatform: GlesPlatform,
    private val targetPlatform: TargetPlatform,
    private val nativeJpeg: NativeJpegFacade,
    private val projectionStop: ProjectionStop = AndroidBootstrapProjectionStop,
) {
    internal fun interface ProjectionStop {
        fun stop(projection: MediaProjection)
    }

    internal class PreparedGraph(
        internal val executor: SessionControlExecutor,
        internal val captureLink: SessionCaptureLink,
        internal val captureOwner: SessionCaptureOwner,
        internal val metricsOwner: SessionMetricsOwner,
        internal val encodingLink: SessionEncodingLink,
        internal val deliveryLink: SessionDeliveryLink,
    )

    internal fun dispatch() {
        val accepted = try {
            workerDispatcher.tryDispatch {
                try {
                    runBootstrap()
                } catch (failure: Exception) {
                    try {
                        coordinator.onBootstrapFailure(ownership, failure)
                    } catch (_: Exception) {
                    }
                    retireAfterCutoff()
                }
            }
        } catch (failure: Exception) {
            try {
                coordinator.onBootstrapFailure(ownership, failure)
            } catch (_: Exception) {
            }
            return
        }
        if (!accepted) {
            try {
                coordinator.onBootstrapFailure(ownership, IllegalStateException("Bootstrap dispatch was rejected"))
            } catch (_: Exception) {
            }
            return
        }
        coordinator.onBootstrapWorkerAccepted(ownership)
    }

    internal fun requestPrefixRetirement() {
        try {
            workerDispatcher.tryDispatch(::retireAfterCutoff)
        } catch (_: Exception) {
        }
    }

    private fun runBootstrap() {
        val controlThread = handlerThreadPlatform.newThread(CONTROL_THREAD_NAME)
        ownership.recordControlThread(controlThread)
        if (!startControlThread(controlThread)) return
        if (retireIfCutoff()) return
        val controlLooper = handlerThreadPlatform.looper(controlThread)
            ?: throw IllegalStateException("Control HandlerThread returned no Looper")
        if (retireIfCutoff()) return
        val controlHandler = handlerThreadPlatform.handler(controlLooper)
        ownership.recordControlHandler(controlThread, controlHandler)
        if (retireIfCutoff()) return

        val captureThread = handlerThreadPlatform.newThread(CAPTURE_THREAD_NAME)
        ownership.recordCaptureThread(captureThread)
        if (!startCaptureThread(captureThread)) return
        if (retireIfCutoff()) return
        val captureLooper = handlerThreadPlatform.looper(captureThread)
            ?: throw IllegalStateException("Capture HandlerThread returned no Looper")
        if (retireIfCutoff()) return
        val captureHandler = handlerThreadPlatform.handler(captureLooper)
        ownership.recordCaptureHandler(captureThread, captureHandler)
        if (retireIfCutoff()) return

        val executor = SessionControlExecutor(
            coordinator = coordinator,
            controlThread = controlThread,
            controlHandler = controlHandler,
            handlerTaskPoster = handlerTaskPoster,
        )
        val captureLink = SessionCaptureLink(coordinator)
        val captureOwner = SessionCaptureOwner(
            captureThread = captureThread,
            captureHandler = captureHandler,
            controlHandler = controlHandler,
            handlerTaskPoster = handlerTaskPoster,
            factPort = captureLink,
            readbackClock = executionClock,
            platformSdkInt = platformSdkInt,
            projectionPlatform = projectionPlatform,
            eglPlatform = eglPlatform,
            glesPlatform = glesPlatform,
            targetPlatform = targetPlatform,
        )
        val preparedGraph = PreparedGraph(
            executor = executor,
            captureLink = captureLink,
            captureOwner = captureOwner,
            metricsOwner = SessionMetricsOwner(
                workerDispatcher = workerDispatcher,
                sourceSelection = metricsSourceSelection,
                requestControlTurn = coordinator::signalControl,
            ),
            encodingLink = SessionEncodingLink(
                coordinator = coordinator,
                workerDispatcher = workerDispatcher,
                clock = executionClock,
                nativeJpeg = nativeJpeg,
            ),
            deliveryLink = SessionDeliveryLink(coordinator, workerDispatcher),
        )
        val firstControlTask = object : Runnable {
            override fun run() {
                val entry = try {
                    coordinator.enterFirstControlTask(ownership, executor, this, preparedGraph)
                } catch (failure: Exception) {
                    try {
                        coordinator.onBootstrapFailure(ownership, failure)
                    } catch (_: Exception) {
                    }
                    retireAfterCutoff()
                    return
                }
                when (entry) {
                    is BootstrapOwnership.FirstControlEntry.Entered -> executor.enterTurn()
                    BootstrapOwnership.FirstControlEntry.CutoffInert -> retireAfterCutoff()
                }
            }
        }
        ownership.rootFirstControlTask(executor, firstControlTask)
        if (discardFirstControlTaskAndRetireIfCutoff(executor, firstControlTask)) return
        ownership.recordFirstControlPostAttempt(executor, firstControlTask)
        val accepted = try {
            handlerTaskPoster.post(controlHandler, firstControlTask)
        } catch (failure: Exception) {
            try {
                ownership.recordFirstControlPostException(executor, firstControlTask)
            } catch (_: Exception) {
            }
            throw failure
        }
        ownership.recordFirstControlPostResult(executor, firstControlTask, accepted)
        if (accepted) coordinator.onFirstControlPostAccepted(ownership)
        retireIfCutoff()
    }

    private fun startControlThread(thread: HandlerThread): Boolean {
        if (coordinator.claimControlThreadStart(ownership, thread) == BootstrapOwnership.LaneStartDecision.Cutoff) {
            retireAfterCutoff()
            return false
        }
        try {
            handlerThreadPlatform.start(thread)
        } catch (failure: Exception) {
            try {
                ownership.recordControlThreadStartFailure(thread)
            } catch (_: Exception) {
            }
            throw failure
        }
        ownership.recordControlThreadStarted(thread)
        return true
    }

    private fun startCaptureThread(thread: HandlerThread): Boolean {
        if (coordinator.claimCaptureThreadStart(ownership, thread) == BootstrapOwnership.LaneStartDecision.Cutoff) {
            retireAfterCutoff()
            return false
        }
        try {
            handlerThreadPlatform.start(thread)
        } catch (failure: Exception) {
            try {
                ownership.recordCaptureThreadStartFailure(thread)
            } catch (_: Exception) {
            }
            throw failure
        }
        ownership.recordCaptureThreadStarted(thread)
        return true
    }

    private fun discardFirstControlTaskAndRetireIfCutoff(executor: SessionControlExecutor, task: Runnable): Boolean {
        if (!coordinator.bootstrapCutoffWon(ownership)) return false
        ownership.discardUnpostedFirstControlTask(executor, task)
        retireAfterCutoff()
        return true
    }

    private fun retireIfCutoff(): Boolean {
        if (!coordinator.bootstrapCutoffWon(ownership)) return false
        retireAfterCutoff()
        return true
    }

    private fun retireAfterCutoff() {
        try {
            retireProjection()
        } catch (_: Exception) {
        }
        try {
            ownership.claimCaptureLaneQuit()?.let(::retireLane)
        } catch (_: Exception) {
        }
        try {
            ownership.claimControlLaneQuit()?.let(::retireLane)
        } catch (_: Exception) {
        }
    }

    private object AndroidBootstrapProjectionStop : ProjectionStop {
        override fun stop(projection: MediaProjection) {
            projection.stop()
        }
    }

    private fun retireProjection() {
        val claim = ownership.claimProjectionStop() ?: return
        try {
            projectionStop.stop(claim.projection)
        } catch (_: Exception) {
            try {
                ownership.recordProjectionStopFailure(claim)
            } catch (_: Exception) {
            }
            return
        }
        ownership.recordProjectionStopReturned(claim)
    }

    private fun retireLane(claim: BootstrapOwnership.LaneQuitClaim) {
        val quitRequested = try {
            claim.thread.quitSafely()
        } catch (_: Exception) {
            try {
                ownership.recordLaneQuitFailure(claim)
            } catch (_: Exception) {
            }
            return
        }
        ownership.recordLaneQuitReturned(claim, quitRequested)
    }

    private companion object {
        private const val CONTROL_THREAD_NAME: String = ProductionRuntime.THREAD_NAME_PREFIX + "Control"
        private const val CAPTURE_THREAD_NAME: String = ProductionRuntime.THREAD_NAME_PREFIX + "Capture"
    }
}
