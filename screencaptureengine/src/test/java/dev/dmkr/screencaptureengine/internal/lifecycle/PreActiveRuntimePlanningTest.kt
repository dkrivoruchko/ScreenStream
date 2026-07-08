package dev.dmkr.screencaptureengine.internal.lifecycle

import dev.dmkr.screencaptureengine.CaptureGeometrySource
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.ScreenCaptureStartException
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.transferToInitialRuntimeResourceOwner
import dev.dmkr.screencaptureengine.internal.startup.TargetCreation
import dev.dmkr.screencaptureengine.internal.startup.TestRuntime
import dev.dmkr.screencaptureengine.internal.startup.expectStartException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreActiveRuntimePlanningTest {
    @Test
    fun preActivePlanUsesFrozenStartupGeometryAndRetainsLaterSignalsAsPending() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()

        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val resources = start.await()
        runtime.metricsProvider.update(CaptureMetrics(widthPx = 1440, heightPx = 2560, densityDpi = 560))
        runtime.callbackRegistration.emitResize(width = 640, height = 360)
        runtime.callbackRegistration.emitVisibility(false)
        val owner = resources.transferToPreActiveRuntimeOwner()
        var runtimeOwner: InitialRuntimeResourceOwner? = null

        try {
            runCurrent()
            val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
            runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)

            assertEquals(720, prepared.outputPlan.captureGeometry.widthPx)
            assertEquals(1280, prepared.outputPlan.captureGeometry.heightPx)
            assertEquals(440, prepared.outputPlan.captureGeometry.densityDpi)
            assertEquals(720, prepared.outputPlan.captureTarget.width)
            assertEquals(1280, prepared.outputPlan.captureTarget.height)
            assertEquals(720, prepared.projectionTarget.width)
            assertEquals(1280, prepared.projectionTarget.height)
            assertEquals(440, prepared.projectionTarget.densityDpi)
            assertEquals(TargetCreation(width = 720, height = 1280, densityDpi = 440), runtime.targetOwner.createdTargets.last())
            assertEquals(640, runtimeOwner.initialPendingSignals.pendingCapturedContentResize?.width)
            assertEquals(360, runtimeOwner.initialPendingSignals.pendingCapturedContentResize?.height)
            assertEquals(560, runtimeOwner.initialPendingSignals.latestCaptureMetrics.densityDpi)
            assertEquals(640, runtimeOwner.initialPendingSignals.pendingCaptureGeometry?.widthPx)
            assertEquals(360, runtimeOwner.initialPendingSignals.pendingCaptureGeometry?.heightPx)
            assertEquals(560, runtimeOwner.initialPendingSignals.pendingCaptureGeometry?.densityDpi)
            assertEquals(false, runtimeOwner.initialPendingSignals.latestCapturedContentVisible)
        } finally {
            runtimeOwner?.close()
            owner.close()
            runCurrent()
        }
    }


    @Test
    fun preActiveDelayedListenerReplayOfStartupResizeIsNotPendingRuntimeResize() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()

        val startupResize = runtime.callbackRegistration.emitResizeRawOnly(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        runtime.callbackRegistration.emitResizeToListener(startupResize)
        var runtimeOwner: InitialRuntimeResourceOwner? = null

        try {
            val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
            runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)

            assertNull(runtimeOwner.initialPendingSignals.pendingCapturedContentResize)
        } finally {
            runtimeOwner?.close()
            owner.close()
            runCurrent()
        }
    }


    @Test
    fun preActiveInvalidPendingResizeIsIgnored() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val resources = start.await()
        runtime.callbackRegistration.emitResize(width = 0, height = 360)
        val owner = resources.transferToPreActiveRuntimeOwner()
        var runtimeOwner: InitialRuntimeResourceOwner? = null

        try {
            val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
            runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)

            assertNull(runtimeOwner.initialPendingSignals.pendingCapturedContentResize)
        } finally {
            runtimeOwner?.close()
            owner.close()
            runCurrent()
        }
    }


    @Test
    fun preActiveDensityOnlyMetricsChangeBecomesPendingRuntimeGeometry() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val resources = start.await()
        runtime.metricsProvider.update(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 560))
        val owner = resources.transferToPreActiveRuntimeOwner()
        var runtimeOwner: InitialRuntimeResourceOwner? = null

        try {
            runCurrent()
            val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
            runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)

            assertNull(runtimeOwner.initialPendingSignals.pendingCapturedContentResize)
            assertEquals(720, runtimeOwner.initialPendingSignals.pendingCaptureGeometry?.widthPx)
            assertEquals(1280, runtimeOwner.initialPendingSignals.pendingCaptureGeometry?.heightPx)
            assertEquals(560, runtimeOwner.initialPendingSignals.pendingCaptureGeometry?.densityDpi)
        } finally {
            runtimeOwner?.close()
            owner.close()
            runCurrent()
        }
    }


    @Test
    fun api33MetricsChangeAfterStartupBecomesPendingRuntimeGeometry() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val resources = runtime.start()
        runtime.metricsProvider.update(CaptureMetrics(widthPx = 1440, heightPx = 2560, densityDpi = 560))
        val owner = resources.transferToPreActiveRuntimeOwner()
        var runtimeOwner: InitialRuntimeResourceOwner? = null

        try {
            runCurrent()
            val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
            runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)

            assertNull(runtimeOwner.initialPendingSignals.pendingCapturedContentResize)
            assertEquals(CaptureGeometrySource.MetricsProvider, runtimeOwner.initialPendingSignals.pendingCaptureGeometry?.source)
            assertEquals(1440, runtimeOwner.initialPendingSignals.pendingCaptureGeometry?.widthPx)
            assertEquals(2560, runtimeOwner.initialPendingSignals.pendingCaptureGeometry?.heightPx)
            assertEquals(560, runtimeOwner.initialPendingSignals.pendingCaptureGeometry?.densityDpi)
        } finally {
            runtimeOwner?.close()
            owner.close()
            runCurrent()
        }
    }


    @Test
    fun preActivePrepareInitialActivePlanIsOneShot() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()

        try {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))

            val failure = runCatching {
                owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
            }.exceptionOrNull()

            assertTrue(failure is ScreenCaptureStartException)
            assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, (failure as ScreenCaptureStartException).problem.kind)
        } finally {
            owner.close()
            runCurrent()
        }
    }


    @Test
    fun preActiveCaptureTargetLimitFailureMapsToOutputLimitsExceededBeforeTargetCreate() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        runtime.targetOwner.maxTargetWidth = 100
        runtime.targetOwner.maxTargetHeight = 100
        val owner = runtime.start().transferToPreActiveRuntimeOwner()

        val exception = expectStartException {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.OutputLimitsExceeded, exception.problem.kind)
        assertEquals(1, runtime.targetOwner.createCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun preActiveTargetLimitQueryFailureMapsToSurfaceCreateOrResizeFailed() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        runtime.targetOwner.targetSizeLimitsFailure = IllegalStateException("limits unavailable")
        val owner = runtime.start().transferToPreActiveRuntimeOwner()

        val exception = expectStartException {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.SurfaceCreateOrResizeFailed, exception.problem.kind)
        assertEquals(1, runtime.projection.stopCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
    }


    @Test
    fun preActiveProjectionStopDuringTargetLimitFailureWinsOverSurfaceFailure() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        runtime.targetOwner.targetSizeLimitsFailure = IllegalStateException("limits unavailable")
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        runtime.targetOwner.afterTargetSizeLimits = { runtime.callbackRegistration.emitStop() }

        val exception = expectStartException {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(0, runtime.projection.stopCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
    }


    @Test
    fun preActiveCancellationAfterTargetLimitQueryRollsBackConsumedProjection() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        lateinit var prepare: kotlinx.coroutines.Deferred<PreActiveInitialRuntimePlan>
        runtime.targetOwner.afterTargetSizeLimits = { prepare.cancel() }

        prepare = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        prepare.start()
        runCurrent()

        assertTrue(prepare.isCancelled)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(0, runtime.metricsProvider.activeCollectorCount)
        assertEquals(1, runtime.metricsProvider.attachmentDisposeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun preActivePlanningFailureMapsAndCleanupFailureDoesNotReplacePrimaryException() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        runtime.failOnCleanupFailure = false
        runtime.virtualDisplayOwner.closeFailure = IllegalStateException("cleanup failed")
        val owner = runtime.start().transferToPreActiveRuntimeOwner()

        val exception = expectStartException {
            owner.prepareInitialActivePlan(
                config = ScreenCaptureConfig(metricsProvider = runtime.metricsProvider),
                initialParameters = ScreenCaptureParameters(crop = CropInsetsPx(left = 32768)),
            )
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.OutputPlanInvalid, exception.problem.kind)
        assertEquals(1, runtime.projection.stopCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(0, runtime.metricsProvider.activeCollectorCount)
        assertEquals(1, runtime.metricsProvider.attachmentDisposeCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
        assertEquals(listOf(runtime.virtualDisplayOwner.closeFailure), runtime.cleanupFailures)
    }

}
