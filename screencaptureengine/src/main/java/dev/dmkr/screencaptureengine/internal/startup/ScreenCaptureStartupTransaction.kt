package dev.dmkr.screencaptureengine.internal.startup

import android.media.projection.MediaProjection
import android.os.Build
import dev.dmkr.screencaptureengine.API34_FIRST_CAPTURED_CONTENT_RESIZE_TIMEOUT_MILLIS
import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureGeometrySource
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblem
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.ScreenCaptureStartException
import dev.dmkr.screencaptureengine.internal.gl.CleanupFailureCollector
import dev.dmkr.screencaptureengine.internal.gl.DefaultStartupCleanupFailureSink
import dev.dmkr.screencaptureengine.internal.gl.DefaultStartupCleanupScheduler
import dev.dmkr.screencaptureengine.internal.gl.StartupCleanupFailureSink
import dev.dmkr.screencaptureengine.internal.gl.StartupCleanupScheduler
import dev.dmkr.screencaptureengine.internal.gl.scheduleStartupCleanup
import dev.dmkr.screencaptureengine.internal.platform.metrics.CaptureMetricsObservation
import dev.dmkr.screencaptureengine.internal.platform.projection.MediaProjectionCallbackAdapter
import dev.dmkr.screencaptureengine.internal.platform.projection.MediaProjectionHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCallbackRawEvent
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCallbackRegistration
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCapturedContentResize
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetOwnerHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionVirtualDisplayOwner
import dev.dmkr.screencaptureengine.internal.platform.projection.StartupCapturedContentResize
import dev.dmkr.screencaptureengine.internal.platform.projection.StartupProjectionCallbackRouter
import dev.dmkr.screencaptureengine.internal.platform.projection.VirtualDisplayOwner
import dev.dmkr.screencaptureengine.internal.target.ProjectionTargetOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Internal startup transaction that reaches authoritative startup geometry without exposing a public session.
 *
 * The transaction validates inputs, creates the projection target, attaches projection callbacks,
 * creates the single virtual display, and resolves authoritative startup geometry. It stops before
 * rendering/readback/encoder preparation, public state publication, encoding, and frame publication.
 */
@Suppress("unused")
internal class ScreenCaptureStartupTransaction(
    private val startupResizeTimeoutMillis: Long = API34_FIRST_CAPTURED_CONTENT_RESIZE_TIMEOUT_MILLIS,
    private val callbackAdapterFactory: StartupCallbackAdapterFactory = StartupCallbackAdapterFactory { listener, synchronousEventObserver ->
        MediaProjectionCallbackAdapter(listener = listener, synchronousEventObserver = synchronousEventObserver)
    },
    private val projectionTargetOwnerFactory: StartupProjectionTargetOwnerFactory = StartupProjectionTargetOwnerFactory {
        ProjectionTargetOwner()
    },
    private val virtualDisplayFactory: StartupVirtualDisplayFactory = StartupVirtualDisplayFactory { projection, name, target, callbackHandler ->
        VirtualDisplayOwner.create(
            projection = projection,
            name = name,
            target = target,
            callbackHandler = callbackHandler,
        )
    },
    private val cleanupScheduler: StartupCleanupScheduler = DefaultStartupCleanupScheduler,
    private val cleanupFailureSink: StartupCleanupFailureSink = DefaultStartupCleanupFailureSink,
    private val beforeInputValidationForTesting: (CaptureMetricsObservation) -> Unit = {},
) {
    private val problemSequence = AtomicLong()

    internal suspend fun startThroughAuthoritativeStartupGeometry(
        config: ScreenCaptureConfig,
        mediaProjection: MediaProjection,
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters.defaults(),
        virtualDisplayName: String = DEFAULT_VIRTUAL_DISPLAY_NAME,
        onMetricsChanged: () -> Unit = {},
    ): ScreenCaptureStartupResources =
        startThroughAuthoritativeStartupGeometry(
            config = config,
            projection = MediaProjectionHandle(mediaProjection),
            initialParameters = initialParameters,
            virtualDisplayName = virtualDisplayName,
            onMetricsChanged = onMetricsChanged,
        )

    internal suspend fun startThroughAuthoritativeStartupGeometry(
        config: ScreenCaptureConfig,
        projection: ProjectionHandle,
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters.defaults(),
        virtualDisplayName: String = DEFAULT_VIRTUAL_DISPLAY_NAME,
        onMetricsChanged: () -> Unit = {},
    ): ScreenCaptureStartupResources {
        val coroutineContext = currentCoroutineContext()
        val projectionLifecycle = StartupProjectionLifecycle(projection = projection, cleanupFailureSink = cleanupFailureSink)
        val startupGeometryArbiter = StartupGeometryArbiter(projectionLifecycle = projectionLifecycle)
        val callbackRouter = StartupProjectionCallbackRouter(
            startupSink = object : StartupProjectionCallbackRouter.StartupSink {
                override fun onProjectionStopped() {
                    startupGeometryArbiter.recordProjectionStopped()
                }

                override fun onCapturedContentResized(resize: ProjectionCapturedContentResize) {
                    startupGeometryArbiter.recordCapturedContentResize(StartupCapturedContentResize.from(resize))
                }

                override fun onCapturedContentResized(width: Int, height: Int) {
                    startupGeometryArbiter.recordCapturedContentResize(
                        StartupCapturedContentResize.unidentified(width = width, height = height),
                    )
                }

                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) = Unit
            },
        )
        val milestones = ArrayList<ScreenCaptureStartupMilestone>(ScreenCaptureStartupMilestone.entries.size)
        var metricsObservation: CaptureMetricsObservation? = null

        var callbackAdapter: ProjectionCallbackRegistration? = null
        var targetOwner: ProjectionTargetOwnerHandle? = null
        var virtualDisplayOwner: ProjectionVirtualDisplayOwner? = null
        var completedResources: ScreenCaptureStartupResources? = null

        fun newProblem(kind: ScreenCaptureProblemKind, message: String, cause: Throwable?): ScreenCaptureProblem =
            ScreenCaptureProblem(sequence = problemSequence.incrementAndGet(), kind = kind, message = message, cause = cause)

        fun startException(kind: ScreenCaptureProblemKind, message: String, cause: Throwable? = null): ScreenCaptureStartException =
            ScreenCaptureStartException(
                requiresFreshProjection = projectionLifecycle.requiresFreshProjection,
                problem = newProblem(kind = kind, message = message, cause = cause),
            )

        fun projectionStoppedException(): ScreenCaptureStartException =
            ScreenCaptureStartException(
                requiresFreshProjection = true,
                problem = newProblem(
                    kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
                    message = "MediaProjection stopped before startup completed",
                    cause = null,
                ),
            )

        fun throwIfPreAuthoritativeSignalWins() {
            if (startupGeometryArbiter.isProjectionStopped || callbackAdapter?.projectionStopObserved == true) {
                throw projectionStoppedException()
            }
            coroutineContext.ensureActive()
        }

        fun scheduleCleanup(block: () -> Unit) {
            scheduleStartupCleanup(
                cleanupScheduler = cleanupScheduler,
                cleanupFailureSink = cleanupFailureSink,
                block = block,
            )
        }

        fun rollback() {
            projectionLifecycle.closeIngress()
            startupGeometryArbiter.close()
            callbackRouter.close()
            runCatching { callbackAdapter?.close() }.onFailure(cleanupFailureSink::onCleanupFailure)
            callbackAdapter = null
            projectionLifecycle.stopProjectionIfRequired()
            val ownerToClose = targetOwner
            val virtualDisplayToClose = virtualDisplayOwner
            targetOwner = null
            virtualDisplayOwner = null
            scheduleCleanup {
                val cleanupFailures = CleanupFailureCollector()
                cleanupFailures.collect { virtualDisplayToClose?.close() }
                cleanupFailures.collect { ownerToClose?.close() }
                cleanupFailures.throwIfAny()
            }
        }

        try {
            val createdMetricsObservation = try {
                CaptureMetricsObservation.start(
                    provider = config.metricsProvider,
                    coroutineContext = coroutineContext,
                    onMetricsChanged = onMetricsChanged,
                )
            } catch (cause: Throwable) {
                coroutineContext.ensureActive()
                throw startException(
                    kind = ScreenCaptureProblemKind.MetricsUnavailableOrInvalid,
                    message = "Startup metrics observation could not be attached.",
                    cause = cause,
                )
            }
            metricsObservation = createdMetricsObservation
            beforeInputValidationForTesting(createdMetricsObservation)
            createdMetricsObservation.refreshLatestProviderState(config.metricsProvider.metrics.value)

            val startupMetrics = try {
                coroutineContext.ensureActive()
                validateInputs(initialParameters = initialParameters, latestMetrics = createdMetricsObservation.latestAvailableMetricsOrNull)
            } catch (cause: ScreenCaptureStartException) {
                throwIfPreAuthoritativeSignalWins()
                throw cause
            }
            milestones += ScreenCaptureStartupMilestone.ValidatedInputs
            coroutineContext.ensureActive()

            val createdTargetOwner = try {
                projectionTargetOwnerFactory.create()
            } catch (cause: Throwable) {
                throwIfPreAuthoritativeSignalWins()
                throw startException(
                    kind = ScreenCaptureProblemKind.SurfaceCreateOrResizeFailed,
                    message = "Projection target owner could not be created.",
                    cause = cause,
                )
            }
            targetOwner = createdTargetOwner
            val target = try {
                createdTargetOwner.createTarget(width = startupMetrics.widthPx, height = startupMetrics.heightPx, densityDpi = startupMetrics.densityDpi)
            } catch (cause: Throwable) {
                throwIfPreAuthoritativeSignalWins()
                throw startException(
                    kind = ScreenCaptureProblemKind.SurfaceCreateOrResizeFailed,
                    message = "Projection target surface could not be created.",
                    cause = cause,
                )
            }
            milestones += ScreenCaptureStartupMilestone.ProjectionTargetReady
            coroutineContext.ensureActive()

            val createdCallbackAdapter = try {
                callbackAdapterFactory.create(
                    listener = callbackRouter,
                ) { event ->
                    when (event) {
                        ProjectionCallbackRawEvent.Stop -> {
                            callbackRouter.markProjectionStopObserved()
                            startupGeometryArbiter.recordProjectionStopped()
                        }

                        is ProjectionCallbackRawEvent.Resize -> {
                            startupGeometryArbiter.recordCapturedContentResize(StartupCapturedContentResize.from(event.resize))
                        }

                        is ProjectionCallbackRawEvent.Visibility -> Unit
                    }
                }
            } catch (cause: Throwable) {
                throwIfPreAuthoritativeSignalWins()
                throw startException(
                    kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
                    message = "MediaProjection callback adapter could not be created.",
                    cause = cause,
                )
            }
            callbackAdapter = createdCallbackAdapter
            try {
                createdCallbackAdapter.register(projection)
            } catch (cause: Throwable) {
                throwIfPreAuthoritativeSignalWins()
                throw startException(
                    kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
                    message = "MediaProjection callback could not be registered.",
                    cause = cause,
                )
            }
            milestones += ScreenCaptureStartupMilestone.ProjectionCallbackAttached
            throwIfPreAuthoritativeSignalWins()

            val createdVirtualDisplay = try {
                projectionLifecycle.createVirtualDisplayIfNotStopped(
                    onCreateEntered = { milestones += ScreenCaptureStartupMilestone.VirtualDisplayAttempted },
                    isProjectionStopObserved = { createdCallbackAdapter.projectionStopObserved },
                ) {
                    virtualDisplayFactory.create(
                        projection = projection,
                        name = virtualDisplayName,
                        target = target,
                        callbackHandler = createdCallbackAdapter.callbackHandler,
                    )
                } ?: throw projectionStoppedException()
            } catch (cause: ScreenCaptureStartException) {
                throw cause
            } catch (cause: Throwable) {
                throwIfPreAuthoritativeSignalWins()
                throw startException(
                    kind = createVirtualDisplayFailureKind(
                        projectionStopObserved = startupGeometryArbiter.isProjectionStopped || createdCallbackAdapter.projectionStopObserved,
                    ),
                    message = "VirtualDisplay could not be created.",
                    cause = cause,
                )
            }
            virtualDisplayOwner = createdVirtualDisplay
            milestones += ScreenCaptureStartupMilestone.VirtualDisplayOwned
            throwIfPreAuthoritativeSignalWins()

            val authoritativeGeometry = awaitAuthoritativeStartupGeometry(
                metricsObservation = createdMetricsObservation,
                startupMetrics = startupMetrics,
                startupGeometryArbiter = startupGeometryArbiter,
                projectionStoppedException = ::projectionStoppedException,
            ) {
                startException(
                    kind = ScreenCaptureProblemKind.StartupGeometryUnavailable,
                    message = "Authoritative startup capture geometry was not available before timeout.",
                )
            }
            milestones += ScreenCaptureStartupMilestone.AuthoritativeStartupGeometryReady
            if (startupGeometryArbiter.isProjectionStopped) throw projectionStoppedException()
            coroutineContext.ensureActive()
            authoritativeGeometry.consumedResize?.let(callbackRouter::markAuthoritativeStartupResizeConsumed)

            val resources = ScreenCaptureStartupResources(
                callbackRouter = callbackRouter,
                callbackAdapter = createdCallbackAdapter,
                projectionTargetOwner = createdTargetOwner,
                virtualDisplayOwner = createdVirtualDisplay,
                currentProjectionTarget = target,
                startupGeometry = authoritativeGeometry.geometry,
                milestones = milestones.toList(),
                metricsObservation = createdMetricsObservation,
                cleanupScheduler = cleanupScheduler,
                cleanupFailureSink = cleanupFailureSink,
                newProblem = ::newProblem,
                projectionStopObserved = { startupGeometryArbiter.isProjectionStopped },
                projectionStoppedProblem = {
                    newProblem(
                        kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
                        message = "MediaProjection stopped before startup completed",
                        cause = null,
                    )
                },
                stopProjectionIfRequired = projectionLifecycle::stopProjectionIfRequired,
            )
            if (startupGeometryArbiter.isProjectionStopped) throw projectionStoppedException()
            coroutineContext.ensureActive()
            completedResources = resources
            callbackAdapter = null
            targetOwner = null
            virtualDisplayOwner = null
            metricsObservation = null
            return resources
        } catch (cause: CancellationException) {
            rollback()
            throw cause
        } catch (cause: ScreenCaptureStartException) {
            rollback()
            throw cause
        } catch (cause: Throwable) {
            val exception = startException(
                kind = ScreenCaptureProblemKind.InternalInvariantViolation,
                message = "Screen capture startup failed.",
                cause = cause,
            )
            rollback()
            throw exception
        } finally {
            if (completedResources == null) {
                runCatching { metricsObservation?.close() }.onFailure(cleanupFailureSink::onCleanupFailure)
                callbackRouter.close()
            }
        }
    }

    private fun validateInputs(
        initialParameters: ScreenCaptureParameters,
        latestMetrics: CaptureMetrics?,
    ): CaptureMetrics {
        require(initialParameters.encoderProvider.id.isNotBlank()) { "encoderProvider.id must not be blank" }
        require(initialParameters.encoderProvider.outputFormat.mimeType.isNotBlank()) { "encoderProvider.outputFormat.mimeType must not be blank" }
        return latestMetrics ?: throw ScreenCaptureStartException(
            requiresFreshProjection = false,
            problem = ScreenCaptureProblem(
                sequence = problemSequence.incrementAndGet(),
                kind = ScreenCaptureProblemKind.MetricsUnavailableOrInvalid,
                message = "Startup metrics are unavailable or invalid.",
                cause = null,
            ),
        )
    }

    private suspend fun awaitAuthoritativeStartupGeometry(
        metricsObservation: CaptureMetricsObservation,
        startupMetrics: CaptureMetrics,
        startupGeometryArbiter: StartupGeometryArbiter,
        projectionStoppedException: () -> ScreenCaptureStartException,
        startupGeometryUnavailableException: () -> ScreenCaptureStartException,
    ): StartupAuthoritativeGeometry {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (startupGeometryArbiter.isProjectionStopped) throw projectionStoppedException()
            return StartupAuthoritativeGeometry(
                geometry = CaptureGeometry(
                    widthPx = startupMetrics.widthPx,
                    heightPx = startupMetrics.heightPx,
                    densityDpi = startupMetrics.densityDpi,
                    source = CaptureGeometrySource.MetricsProvider,
                ),
                consumedResize = null,
            )
        }

        return try {
            coroutineScope {
                val callerJob = coroutineContext[Job]
                val job = launch {
                    delay(startupResizeTimeoutMillis.milliseconds)
                    startupGeometryArbiter.recordTimeout()
                }
                try {
                    when (val decision = startupGeometryArbiter.awaitDecision(callerJob)) {
                        StartupGeometryDecision.ProjectionStopped -> throw projectionStoppedException()
                        StartupGeometryDecision.CallerCancelled -> {
                            coroutineContext.ensureActive()
                            throw CancellationException("Startup caller was cancelled before authoritative geometry was ready.")
                        }

                        is StartupGeometryDecision.FirstValidResize -> {
                            val latestMetrics = metricsObservation.latestMetrics
                            StartupAuthoritativeGeometry(
                                geometry = CaptureGeometry(
                                    widthPx = decision.resize.width,
                                    heightPx = decision.resize.height,
                                    densityDpi = latestMetrics.densityDpi,
                                    source = CaptureGeometrySource.CapturedContentResize,
                                ),
                                consumedResize = decision.resize,
                            )
                        }

                        StartupGeometryDecision.Timeout -> throw startupGeometryUnavailableException()
                    }
                } finally {
                    job.cancel()
                }
            }
        } catch (cause: CancellationException) {
            if (startupGeometryArbiter.isProjectionStopped) throw projectionStoppedException()
            throw cause
        }
    }

    private fun createVirtualDisplayFailureKind(projectionStopObserved: Boolean): ScreenCaptureProblemKind =
        if (projectionStopObserved) {
            ScreenCaptureProblemKind.ProjectionInvalidOrStopped
        } else {
            ScreenCaptureProblemKind.VirtualDisplayCreateFailed
        }

    private companion object {
        private const val DEFAULT_VIRTUAL_DISPLAY_NAME: String = "ScreenCaptureEngine"
    }
}
