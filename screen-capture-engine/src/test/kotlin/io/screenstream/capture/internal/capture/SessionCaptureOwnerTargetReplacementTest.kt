package io.screenstream.capture.internal.capture

import android.graphics.SurfaceTexture
import android.hardware.DataSpace
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import io.mockk.every
import io.mockk.mockk
import io.screenstream.capture.ColorMode
import io.screenstream.capture.ImageRect
import io.screenstream.capture.Mirror
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@LooperMode(LooperMode.Mode.PAUSED)
internal class SessionCaptureOwnerTargetReplacementTest {
    private var savedNoDisplay: EGLDisplay? = null
    private var savedNoContext: EGLContext? = null
    private var savedNoSurface: EGLSurface? = null

    @Before
    fun installOpaqueEglSentinels() {
        savedNoDisplay = EglSentinelAccess.noDisplay()
        savedNoContext = EglSentinelAccess.noContext()
        savedNoSurface = EglSentinelAccess.noSurface()
        EglSentinelAccess.setNoDisplay(mockk())
        EglSentinelAccess.setNoContext(mockk())
        EglSentinelAccess.setNoSurface(mockk())
    }

    @After
    fun restoreOpaqueEglSentinels() {
        EglSentinelAccess.setNoDisplay(savedNoDisplay)
        EglSentinelAccess.setNoContext(savedNoContext)
        EglSentinelAccess.setNoSurface(savedNoSurface)
    }

    // Verification: CAP-02
    // Verification: TGT-01
    @Test
    fun preAttachmentResourceDenialRollsBackCandidateAndPreservesOldTarget() {
        val fixture = OwnerFixture()
        val opened = fixture.open()
        val denial = Surface.OutOfResourcesException("candidate Surface denied")
        fixture.targetPlatform.candidateSurfaceCreationFailure = denial

        assertTrue(fixture.owner.apply(fixture.replacementPlan))
        fixture.enterAcceptedWork()

        val result = fixture.factPort.applyResults.single() as? CaptureApplyResult.ResourceDenied
            ?: error("Target replacement did not return ResourceDenied")
        assertSame(denial, result.cause)
        assertEquals(listOf(fixture.candidateSurfaceTexture), fixture.targetPlatform.releasedSurfaceTextures)
        assertTrue(fixture.targetPlatform.releasedSurfaces.isEmpty())
        assertEquals(1, fixture.gles.deletedTextures.count { it == CANDIDATE_OES_TEXTURE })
        assertEquals(0, fixture.projectionPlatform.resizeCount)
        assertEquals(0, fixture.projectionPlatform.replacementSurfaceCount)

        fixture.deliverSourceFrame(fixture.initialSurfaceTexture)
        assertSame(opened.sourceIdentity, fixture.factPort.sourceIdentities.single())
        val carrier = ByteBuffer.allocateDirect(fixture.initialPlan.rgbaCarrierByteCount)
        var readResult: CaptureReadResult? = null
        assertTrue(
            fixture.owner.read(fixture.initialPlan, opened.sourceIdentity, carrier) { returned ->
                check(readResult == null)
                readResult = returned
            },
        )
        fixture.enterAcceptedWork()

        val filled = readResult as? CaptureReadResult.Filled
            ?: error("The preserved Target did not return a filled read")
        assertEquals(READBACK_DURATION_NANOS, filled.readbackDurationNanos)
        assertEquals(listOf(fixture.initialSurfaceTexture), fixture.targetPlatform.updatedSurfaceTextures)
        assertEquals(1, fixture.gles.readPixelsCount)
        assertTrue(fixture.factPort.captureFailures.isEmpty())

        fixture.owner.retire()
        fixture.enterAcceptedWork()

        assertEquals(listOf(fixture.initialSurface), fixture.targetPlatform.releasedSurfaces)
        assertEquals(2, fixture.targetPlatform.releasedSurfaceTextures.size)
        assertEquals(1, fixture.targetPlatform.releasedSurfaceTextures.count { it === fixture.initialSurfaceTexture })
        assertEquals(1, fixture.targetPlatform.releasedSurfaceTextures.count { it === fixture.candidateSurfaceTexture })
        assertEquals(1, fixture.gles.deletedTextures.count { it == INITIAL_OES_TEXTURE })
        assertEquals(1, fixture.gles.deletedTextures.count { it == CANDIDATE_OES_TEXTURE })
        assertEquals(1, fixture.gles.deletedTextures.count { it == OUTPUT_TEXTURE })
        assertEquals(1, fixture.projectionPlatform.detachSurfaceCount)
        assertEquals(1, fixture.projectionPlatform.releaseCount)
        assertEquals(1, fixture.projectionPlatform.unregisterCount)
        assertEquals(1, fixture.projectionPlatform.stopCount)
        assertEquals(1, fixture.egl.unbindCount)
        assertEquals(1, fixture.egl.destroyContextCount)
        assertEquals(1, fixture.egl.destroySurfaceCount)
        assertEquals(1, fixture.egl.releaseThreadCount)
        assertEquals(1, fixture.captureThreadQuitCount)
    }

    // Verification: TGT-02
    @Test
    fun rawOomeEscapesAtEachOpenSeamAndRetainsTheExactAcquiredPrefix() {
        for (seam in TargetOpenSeam.entries) {
            val failure = OutOfMemoryError("raw OOME at $seam")
            val fixture = RawTargetFixture(seam, failure)

            val thrown = assertThrows(OutOfMemoryError::class.java) {
                fixture.target.open(fixture.plan)
            }

            assertSame(failure, thrown)
            assertEquals(TargetOpenSeam.entries.take(seam.ordinal + 1), fixture.platform.enteredSeams)
            assertEquals(INITIAL_OES_TEXTURE, fixture.target.requireOesTextureName())
            if (seam == TargetOpenSeam.CreateSurfaceTexture) {
                assertThrows(IllegalStateException::class.java) { fixture.target.requireSurfaceTexture() }
            } else {
                assertSame(fixture.surfaceTexture, fixture.target.requireSurfaceTexture())
            }
            assertNull(fixture.target.retirementSurface)
            assertTrue(fixture.platform.releasedSurfaceTextures.isEmpty())
            assertTrue(fixture.gles.deletedTextures.isEmpty())

            val rollback = fixture.target.rollbackUnattached()
            assertNull(rollback.cleanupFailure)
            assertNull(rollback.residue)
            assertEquals(
                if (seam == TargetOpenSeam.CreateSurfaceTexture) emptyList() else listOf(fixture.surfaceTexture),
                fixture.platform.releasedSurfaceTextures,
            )
            assertEquals(listOf(INITIAL_OES_TEXTURE), fixture.gles.deletedTextures)

            val eglRetirement = fixture.eglOwner.close(
                allowInitializationRelease = !fixture.target.blocksEglInitializationRelease,
            )
            assertNull(eglRetirement.cleanupFailure)
            assertNull(eglRetirement.residue)
        }
    }

    // Verification: TGT-02
    @Test
    fun candidateSurfaceDenialWithFailedTextureRollbackInvalidatesOwnerAndRetainsResidue() {
        val fixture = OwnerFixture()
        fixture.open()
        val denial = Surface.OutOfResourcesException("candidate Surface denied")
        val releaseFailure = IllegalStateException("candidate SurfaceTexture release failed")
        fixture.targetPlatform.candidateSurfaceCreationFailure = denial
        fixture.targetPlatform.candidateSurfaceTextureReleaseFailure = releaseFailure

        assertTrue(fixture.owner.apply(fixture.replacementPlan))
        fixture.enterAcceptedWork()

        val result = fixture.factPort.applyResults.single() as? CaptureApplyResult.Failed
            ?: error("Unsettled candidate rollback did not invalidate the owner")
        assertSame(ScreenCaptureProblem.InternalFailure, result.problem)
        assertSame(denial, result.cause)
        assertSame(CaptureFailureScope.OwnerInvalidated, result.scope)
        assertEquals(1, fixture.targetPlatform.candidateSurfaceTextureReleaseAttemptCount)
        assertSame(
            fixture.candidateSurfaceTexture,
            fixture.targetPlatform.surfaceTextureReleaseAttempts.single(),
        )
        assertFalse(fixture.targetPlatform.releasedSurfaceTextures.contains(fixture.candidateSurfaceTexture))
        assertFalse(fixture.gles.deletedTextures.contains(CANDIDATE_OES_TEXTURE))
        assertSame(fixture.initialSurface, fixture.projectionPlatform.platformAttachedSurface)

        fixture.owner.retire()
        fixture.enterAcceptedWork()

        assertEquals(1, fixture.targetPlatform.candidateSurfaceTextureReleaseAttemptCount)
        assertEquals(listOf(fixture.initialSurface), fixture.targetPlatform.releasedSurfaces)
        assertEquals(listOf(fixture.initialSurfaceTexture), fixture.targetPlatform.releasedSurfaceTextures)
        assertFalse(fixture.gles.deletedTextures.contains(CANDIDATE_OES_TEXTURE))
        assertEquals(0, fixture.egl.unbindCount)
        assertEquals(0, fixture.egl.destroyContextCount)
        assertEquals(0, fixture.egl.destroySurfaceCount)
        assertEquals(0, fixture.egl.releaseThreadCount)
        assertEquals(1, fixture.captureThreadQuitCount)
    }

    // Verification: TGT-02
    @Test
    fun ambiguousSurfaceReplacementWithUnprovedDisplayReleaseQuarantinesBothTargets() {
        val fixture = OwnerFixture()
        fixture.open()
        val replacementFailure = IllegalStateException("setSurface effect then failure")
        val displayReleaseFailure = IllegalStateException("display release unproved")
        fixture.projectionPlatform.replacementFailure = replacementFailure
        fixture.projectionPlatform.releaseFailure = displayReleaseFailure

        assertTrue(fixture.owner.apply(fixture.replacementPlan))
        fixture.enterAcceptedWork()

        val result = fixture.factPort.applyResults.single() as? CaptureApplyResult.Failed
            ?: error("Ambiguous Target replacement did not return Failed")
        assertSame(ScreenCaptureProblem.InternalFailure, result.problem)
        assertSame(replacementFailure, result.cause)
        assertSame(CaptureFailureScope.OwnerInvalidated, result.scope)
        assertSame(fixture.candidateSurface, fixture.projectionPlatform.platformAttachedSurface)
        assertEquals(0, fixture.projectionPlatform.resizeCount)
        assertEquals(1, fixture.projectionPlatform.replacementSurfaceCount)

        fixture.deliverSourceFrame(fixture.initialSurfaceTexture)
        fixture.deliverSourceFrame(fixture.candidateSurfaceTexture)
        assertTrue(fixture.factPort.sourceIdentities.isEmpty())
        assertTrue(fixture.targetPlatform.updatedSurfaceTextures.isEmpty())
        assertEquals(0, fixture.gles.readPixelsCount)

        fixture.owner.retire()
        fixture.enterAcceptedWork()

        assertEquals(1, fixture.projectionPlatform.releaseCount)
        assertEquals(0, fixture.projectionPlatform.detachSurfaceCount)
        assertTrue(fixture.targetPlatform.releasedSurfaces.isEmpty())
        assertTrue(fixture.targetPlatform.releasedSurfaceTextures.isEmpty())
        assertFalse(fixture.gles.deletedTextures.contains(INITIAL_OES_TEXTURE))
        assertFalse(fixture.gles.deletedTextures.contains(CANDIDATE_OES_TEXTURE))
        assertEquals(1, fixture.gles.deletedTextures.count { it == OUTPUT_TEXTURE })
        assertEquals(0, fixture.egl.unbindCount)
        assertEquals(0, fixture.egl.destroyContextCount)
        assertEquals(0, fixture.egl.destroySurfaceCount)
        assertEquals(0, fixture.egl.releaseThreadCount)
        assertEquals(1, fixture.targetPlatform.initialListenerRemovalCount)
        assertEquals(1, fixture.targetPlatform.candidateListenerRemovalCount)
        assertEquals(1, fixture.projectionPlatform.unregisterCount)
        assertEquals(1, fixture.projectionPlatform.stopCount)
        assertEquals(1, fixture.captureThreadQuitCount)
        assertTrue(fixture.factPort.captureFailures.isEmpty())

        fixture.deliverSourceFrame(fixture.initialSurfaceTexture)
        fixture.deliverSourceFrame(fixture.candidateSurfaceTexture)
        assertTrue(fixture.factPort.sourceIdentities.isEmpty())
    }

    // Verification: TGT-03
    @Test
    fun fullCompatibleApplyReusesExactTargetAndSource() {
        val fixture = OwnerFixture()
        val opened = fixture.open()
        val compatiblePlan = capturePlan(
            sourceWidthPx = 4,
            sourceHeightPx = 4,
            rotation = Rotation.Degrees180,
        )

        assertTrue(fixture.owner.apply(compatiblePlan))
        fixture.enterAcceptedWork()

        val applied = fixture.factPort.applyResults.single() as? CaptureApplyResult.Applied
            ?: error("Full-compatible apply did not succeed")
        assertSame(compatiblePlan, applied.plan)
        assertSame(opened.sourceIdentity, applied.sourceIdentity)
        assertSame(fixture.initialSurface, fixture.projectionPlatform.platformAttachedSurface)
        assertEquals(listOf(INITIAL_OES_TEXTURE), fixture.targetPlatform.createdOesTextureNames)
        assertEquals(0, fixture.projectionPlatform.replacementSurfaceCount)

        fixture.deliverSourceFrame(fixture.initialSurfaceTexture)
        assertSame(opened.sourceIdentity, fixture.factPort.sourceIdentities.single())

        fixture.owner.retire()
        fixture.enterAcceptedWork()
    }

    // Verification: TGT-03
    @Test
    fun downscaledShrinkReusesThenGrowthReplacesTargetAndSourceIdentity() {
        val initialPlan = capturePlan(
            sourceWidthPx = 8,
            sourceHeightPx = 8,
            targetMode = CaptureTargetMode.Downscaled,
            targetWidthPx = 4,
            targetHeightPx = 4,
        )
        val fixture = OwnerFixture(initialPlan = initialPlan)
        val opened = fixture.open()
        assertEquals(Triple(4, 4, 320), fixture.projectionPlatform.configuredDisplay)
        val shrinkPlan = capturePlan(
            sourceWidthPx = 8,
            sourceHeightPx = 8,
            targetMode = CaptureTargetMode.Downscaled,
            targetWidthPx = 2,
            targetHeightPx = 2,
        )

        assertTrue(fixture.owner.apply(shrinkPlan))
        fixture.enterAcceptedWork()

        val shrink = fixture.factPort.applyResults.single() as? CaptureApplyResult.Applied
            ?: error("Downscaled shrink did not succeed")
        assertSame(shrinkPlan, shrink.plan)
        assertSame(opened.sourceIdentity, shrink.sourceIdentity)
        assertSame(fixture.initialSurface, fixture.projectionPlatform.platformAttachedSurface)
        assertEquals(listOf(INITIAL_OES_TEXTURE), fixture.targetPlatform.createdOesTextureNames)
        assertEquals(Triple(4, 4, 320), fixture.projectionPlatform.configuredDisplay)
        assertEquals(0, fixture.projectionPlatform.resizeCount)

        val regrowthPlan = capturePlan(
            sourceWidthPx = 8,
            sourceHeightPx = 8,
            targetMode = CaptureTargetMode.Downscaled,
            targetWidthPx = 3,
            targetHeightPx = 3,
        )
        assertTrue(fixture.owner.apply(regrowthPlan))
        fixture.enterAcceptedWork()
        val regrowth = fixture.factPort.applyResults.last() as CaptureApplyResult.Applied
        assertSame(opened.sourceIdentity, regrowth.sourceIdentity)
        assertSame(fixture.initialSurface, fixture.projectionPlatform.platformAttachedSurface)
        assertEquals(Triple(4, 4, 320), fixture.projectionPlatform.configuredDisplay)
        assertEquals(0, fixture.projectionPlatform.resizeCount)

        val growthPlan = capturePlan(
            sourceWidthPx = 8,
            sourceHeightPx = 8,
            targetMode = CaptureTargetMode.Downscaled,
            targetWidthPx = 6,
            targetHeightPx = 6,
        )
        assertTrue(fixture.owner.apply(growthPlan))
        fixture.enterAcceptedWork()

        val growth = fixture.factPort.applyResults.last() as? CaptureApplyResult.Applied
            ?: error("Downscaled growth replacement did not succeed")
        assertSame(growthPlan, growth.plan)
        assertEquals(Triple(6, 6, 320), fixture.projectionPlatform.configuredDisplay)
        assertEquals(1, fixture.projectionPlatform.resizeCount)
        assertNotSame(opened.sourceIdentity, growth.sourceIdentity)
        assertSame(fixture.candidateSurface, fixture.projectionPlatform.platformAttachedSurface)
        assertEquals(
            listOf(INITIAL_OES_TEXTURE, CANDIDATE_OES_TEXTURE),
            fixture.targetPlatform.createdOesTextureNames,
        )
        assertEquals(1, fixture.projectionPlatform.replacementSurfaceCount)
        assertEquals(listOf(fixture.initialSurface), fixture.targetPlatform.releasedSurfaces)
        assertEquals(listOf(fixture.initialSurfaceTexture), fixture.targetPlatform.releasedSurfaceTextures)

        fixture.deliverSourceFrame(fixture.candidateSurfaceTexture)
        assertSame(growth.sourceIdentity, fixture.factPort.sourceIdentities.single())

        val carrier = ByteBuffer.allocateDirect(growthPlan.rgbaCarrierByteCount)
        var readResult: CaptureReadResult? = null
        assertTrue(
            fixture.owner.read(growthPlan, growth.sourceIdentity, carrier) { returned ->
                check(readResult == null)
                readResult = returned
            },
        )
        fixture.enterAcceptedWork()

        val filled = readResult as? CaptureReadResult.Filled
            ?: error("Candidate Target read did not return Filled")
        assertEquals(READBACK_DURATION_NANOS, filled.readbackDurationNanos)
        assertEquals(listOf(fixture.candidateSurfaceTexture), fixture.targetPlatform.updatedSurfaceTextures)
        assertEquals(
            listOf(
                fixture.candidateSurfaceTexture,
                fixture.candidateSurfaceTexture,
                fixture.candidateSurfaceTexture,
            ),
            fixture.targetPlatform.frameAccessSurfaceTextures,
        )
        assertEquals(1, fixture.gles.readPixelsCount)

        fixture.deliverSourceFrame(fixture.initialSurfaceTexture)
        assertEquals(listOf(growth.sourceIdentity), fixture.factPort.sourceIdentities)

        fixture.owner.retire()
        fixture.enterAcceptedWork()

        assertEquals(
            listOf(fixture.initialSurface, fixture.candidateSurface),
            fixture.targetPlatform.surfaceReleaseAttempts,
        )
        assertEquals(
            listOf(fixture.initialSurface, fixture.candidateSurface),
            fixture.targetPlatform.releasedSurfaces,
        )
        assertEquals(
            listOf(fixture.initialSurfaceTexture, fixture.candidateSurfaceTexture),
            fixture.targetPlatform.releasedSurfaceTextures,
        )
        assertEquals(1, fixture.projectionPlatform.detachSurfaceCount)
        assertEquals(1, fixture.projectionPlatform.releaseCount)
        assertEquals(1, fixture.projectionPlatform.unregisterCount)
        assertEquals(1, fixture.projectionPlatform.stopCount)
    }

    // Verification: TGT-03
    @Test
    fun replacementResizeNotificationObservesSelectedConsumerSurface() {
        val plans = listOf(
            capturePlan(8, 6) to capturePlan(6, 8),
            capturePlan(8, 8, CaptureTargetMode.Downscaled, 4, 4) to
                    capturePlan(8, 8, CaptureTargetMode.Downscaled, 6, 6),
        )
        for ((initial, replacement) in plans) {
            val fixture = OwnerFixture(initialPlan = initial, replacementPlan = replacement)
            fixture.open()
            try {
                assertTrue(fixture.owner.apply(replacement))
                fixture.enterAcceptedWork()
                assertTrue(fixture.factPort.applyResults.single() is CaptureApplyResult.Applied)
                // Android can evaluate mirror fit when resize emits its changed-display notification.
                // That physical boundary must already expose the selected consumer, not the previous Surface.
                assertSame(fixture.candidateSurface, fixture.projectionPlatform.surfacesAtResize.single())
                assertEquals(Triple(replacement.targetWidthPx, replacement.targetHeightPx, replacement.densityDpi),
                    fixture.projectionPlatform.configuredDisplay)
            } finally {
                fixture.owner.retire()
                fixture.enterAcceptedWork()
            }
        }
    }

    // Verification: TGT-03
    @Test
    fun sourceAspectShrinkReplacesTargetDespiteRetainedCapacity() {
        val initial = capturePlan(8, 8, CaptureTargetMode.Downscaled, 4, 4)
        val shrunk = capturePlan(2, 4, CaptureTargetMode.Downscaled, 1, 2)
        val fixture = OwnerFixture(initialPlan = initial)
        val opened = fixture.open()

        assertTrue(fixture.owner.apply(shrunk))
        fixture.enterAcceptedWork()

        val applied = fixture.factPort.applyResults.single() as CaptureApplyResult.Applied
        assertSame(shrunk, applied.plan)
        assertNotSame(opened.sourceIdentity, applied.sourceIdentity)
        assertEquals(Triple(1, 2, 320), fixture.projectionPlatform.configuredDisplay)
        assertSame(fixture.candidateSurface, fixture.projectionPlatform.platformAttachedSurface)
        assertEquals(listOf(fixture.initialSurface), fixture.targetPlatform.releasedSurfaces)
        fixture.deliverSourceFrame(fixture.initialSurfaceTexture)
        assertTrue(fixture.factPort.sourceIdentities.isEmpty())
        fixture.deliverSourceFrame(fixture.candidateSurfaceTexture)
        assertSame(applied.sourceIdentity, fixture.factPort.sourceIdentities.single())

        fixture.owner.retire()
        fixture.enterAcceptedWork()
        assertEquals(listOf(fixture.initialSurface, fixture.candidateSurface), fixture.targetPlatform.releasedSurfaces)
        assertEquals(1, fixture.projectionPlatform.releaseCount)
    }

    // Verification: TGT-03
    @Test
    fun densityOnlyApplyKeepsRetainedTargetExtentAndIdentity() {
        val initial = capturePlan(8, 8, CaptureTargetMode.Downscaled, 4, 4)
        val fixture = OwnerFixture(initialPlan = initial)
        val opened = fixture.open()
        val changed = capturePlan(8, 8, CaptureTargetMode.Downscaled, 2, 2, densityDpi = 480)

        assertTrue(fixture.owner.apply(changed))
        fixture.enterAcceptedWork()

        val applied = fixture.factPort.applyResults.single() as CaptureApplyResult.Applied
        assertSame(changed, applied.plan)
        assertSame(opened.sourceIdentity, applied.sourceIdentity)
        assertSame(fixture.initialSurface, fixture.projectionPlatform.platformAttachedSurface)
        assertEquals(Triple(4, 4, 480), fixture.projectionPlatform.configuredDisplay)
        assertEquals(1, fixture.projectionPlatform.resizeCount)
        assertEquals(0, fixture.projectionPlatform.replacementSurfaceCount)
        assertEquals(listOf(INITIAL_OES_TEXTURE), fixture.targetPlatform.createdOesTextureNames)
        fixture.owner.retire()
        fixture.enterAcceptedWork()
    }

    // Verification: TGT-02
    @Test
    fun attachedCandidateResizeFailureRetainsCurrentAndRetiringRoots() {
        val fixture = OwnerFixture(
            initialPlan = capturePlan(8, 8, CaptureTargetMode.Downscaled, 4, 4),
            replacementPlan = capturePlan(8, 8, CaptureTargetMode.Downscaled, 6, 6),
        )
        val opened = fixture.open()
        val failure = IllegalStateException("display resize failed after possible mutation")
        fixture.projectionPlatform.resizeFailure = failure

        assertTrue(fixture.owner.apply(fixture.replacementPlan))
        fixture.enterAcceptedWork()

        val result = fixture.factPort.applyResults.single() as CaptureApplyResult.Failed
        assertSame(ScreenCaptureProblem.InternalFailure, result.problem)
        assertSame(failure, result.cause)
        assertSame(CaptureFailureScope.OwnerInvalidated, result.scope)
        assertEquals(Triple(6, 6, 320), fixture.projectionPlatform.configuredDisplay)
        assertEquals(1, fixture.projectionPlatform.replacementSurfaceCount)
        assertSame(fixture.candidateSurface, fixture.projectionPlatform.platformAttachedSurface)
        assertTrue(fixture.targetPlatform.releasedSurfaces.isEmpty())
        var read: CaptureReadResult? = null
        assertTrue(fixture.owner.read(fixture.initialPlan, opened.sourceIdentity,
            ByteBuffer.allocateDirect(fixture.initialPlan.rgbaCarrierByteCount)) { read = it })
        fixture.enterAcceptedWork()
        val rejected = read as CaptureReadResult.Failed
        assertFalse(rejected.sourceConsumed)
        assertEquals(0, fixture.gles.readPixelsCount)

        fixture.owner.retire()
        fixture.enterAcceptedWork()
        assertEquals(listOf(fixture.candidateSurface, fixture.initialSurface), fixture.targetPlatform.releasedSurfaces)
        assertEquals(listOf(fixture.candidateSurface), fixture.projectionPlatform.detachedSurfaces)
        assertTrue(fixture.factPort.captureFailures.isEmpty())
        assertEquals(1, fixture.projectionPlatform.releaseCount)
        assertEquals(1, fixture.projectionPlatform.resizeCount)
    }

    // Verification: TGT-02
    @Test
    fun enteredResizeRetainsAttachedAndRetiringTargetsUntilRealReturn() {
        val fixture = OwnerFixture(
            initialPlan = capturePlan(8, 8, CaptureTargetMode.Downscaled, 4, 4),
            replacementPlan = capturePlan(8, 8, CaptureTargetMode.Downscaled, 6, 6),
            useRealCaptureThread = true,
        )
        val opened = fixture.open()
        val entered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        val failure = IllegalStateException("resize returned failure after blocked entry")
        fixture.projectionPlatform.resizeFailure = failure
        fixture.projectionPlatform.onResizeEntered = {
            entered.countDown()
            check(allowReturn.await(5, TimeUnit.SECONDS)) { "Test did not release the entered resize" }
        }
        try {
            assertTrue(fixture.owner.apply(fixture.replacementPlan))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertSame(fixture.candidateSurface, fixture.projectionPlatform.platformAttachedSurface)
            assertSame(fixture.candidateSurface, fixture.projectionPlatform.surfacesAtResize.single())
            assertTrue(fixture.factPort.applyResults.isEmpty())
            assertTrue(fixture.targetPlatform.releasedSurfaces.isEmpty())
            assertTrue(fixture.targetPlatform.releasedSurfaceTextures.isEmpty())
            assertEquals(0, fixture.projectionPlatform.releaseCount)
            assertThrows(IllegalStateException::class.java) {
                fixture.owner.read(fixture.initialPlan, opened.sourceIdentity,
                    ByteBuffer.allocateDirect(fixture.initialPlan.rgbaCarrierByteCount)) { error("Unreturned resize admitted a read") }
            }
            // Stop admission does not settle an entered resize or release either physical Target.
            fixture.owner.retire()
            assertTrue(fixture.factPort.applyResults.isEmpty())
            assertTrue(fixture.targetPlatform.releasedSurfaces.isEmpty())
            assertEquals(0, fixture.projectionPlatform.releaseCount)
        } finally {
            allowReturn.countDown()
            fixture.owner.retire()
            fixture.awaitCaptureThreadExit()
        }

        val result = fixture.factPort.applyResults.single() as CaptureApplyResult.Failed
        assertSame(failure, result.cause)
        assertSame(CaptureFailureScope.OwnerInvalidated, result.scope)
        assertEquals(listOf(fixture.candidateSurface), fixture.projectionPlatform.detachedSurfaces)
        assertEquals(listOf(fixture.candidateSurface, fixture.initialSurface), fixture.targetPlatform.releasedSurfaces)
        assertEquals(listOf(fixture.candidateSurfaceTexture, fixture.initialSurfaceTexture), fixture.targetPlatform.releasedSurfaceTextures)
        assertEquals(1, fixture.projectionPlatform.releaseCount)
        assertEquals(1, fixture.projectionPlatform.resizeCount)
        assertTrue(fixture.factPort.captureFailures.isEmpty())
    }

    // Verification: TGT-02
    @Test
    fun committedReplacementOldSurfaceReleaseFailureInvalidatesOwnerAndRetainsOldRoots() {
        val fixture = OwnerFixture()
        fixture.open()
        val oldSurfaceReleaseFailure = IllegalStateException("old Surface release failed")
        fixture.targetPlatform.initialSurfaceReleaseFailure = oldSurfaceReleaseFailure

        assertTrue(fixture.owner.apply(fixture.replacementPlan))
        fixture.enterAcceptedWork()

        val result = fixture.factPort.applyResults.single() as? CaptureApplyResult.Failed
            ?: error("Committed replacement failure did not return Failed")
        assertSame(ScreenCaptureProblem.InternalFailure, result.problem)
        assertSame(oldSurfaceReleaseFailure, result.cause)
        assertSame(CaptureFailureScope.OwnerInvalidated, result.scope)
        assertSame(fixture.candidateSurface, fixture.projectionPlatform.platformAttachedSurface)
        assertEquals(1, fixture.projectionPlatform.replacementSurfaceCount)
        assertEquals(
            listOf(fixture.initialSurface),
            fixture.targetPlatform.surfaceReleaseAttempts,
        )
        assertTrue(fixture.targetPlatform.releasedSurfaces.isEmpty())
        assertTrue(fixture.targetPlatform.releasedSurfaceTextures.isEmpty())
        assertTrue(fixture.targetPlatform.surfaceTextureReleaseAttempts.isEmpty())

        fixture.deliverSourceFrame(fixture.candidateSurfaceTexture)
        val candidateIdentity = fixture.factPort.sourceIdentities.single()
        val carrier = ByteBuffer.allocateDirect(fixture.replacementPlan.rgbaCarrierByteCount)
        var readResult: CaptureReadResult? = null
        assertTrue(
            fixture.owner.read(fixture.replacementPlan, candidateIdentity, carrier) { returned ->
                check(readResult == null)
                readResult = returned
            },
        )
        fixture.enterAcceptedWork()

        val rejectedRead = readResult as? CaptureReadResult.Failed
            ?: error("Invalidated replacement owner reused a candidate read")
        assertSame(ScreenCaptureProblem.InternalFailure, rejectedRead.problem)
        assertFalse(rejectedRead.sourceConsumed)
        assertEquals(0, fixture.gles.readPixelsCount)
        assertTrue(fixture.targetPlatform.frameAccessSurfaceTextures.isEmpty())

        fixture.owner.retire()
        fixture.enterAcceptedWork()

        assertEquals(
            listOf(fixture.initialSurface, fixture.candidateSurface),
            fixture.targetPlatform.surfaceReleaseAttempts,
        )
        assertEquals(listOf(fixture.candidateSurface), fixture.targetPlatform.releasedSurfaces)
        assertEquals(listOf(fixture.candidateSurfaceTexture), fixture.targetPlatform.releasedSurfaceTextures)
        assertFalse(fixture.targetPlatform.releasedSurfaceTextures.contains(fixture.initialSurfaceTexture))
        assertEquals(listOf(OUTPUT_TEXTURE, CANDIDATE_OES_TEXTURE), fixture.gles.deletedTextures)
        assertFalse(fixture.gles.deletedTextures.contains(INITIAL_OES_TEXTURE))
        assertEquals(1, fixture.projectionPlatform.detachSurfaceCount)
        assertEquals(1, fixture.projectionPlatform.releaseCount)
        assertEquals(1, fixture.projectionPlatform.unregisterCount)
        assertEquals(1, fixture.projectionPlatform.stopCount)
        assertEquals(0, fixture.egl.unbindCount)
        assertEquals(0, fixture.egl.destroyContextCount)
        assertEquals(0, fixture.egl.destroySurfaceCount)
        assertEquals(0, fixture.egl.releaseThreadCount)
        assertThrows(IllegalStateException::class.java) {
            fixture.owner.read(fixture.replacementPlan, candidateIdentity, carrier) { }
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.owner.apply(fixture.replacementPlan)
        }
        fixture.owner.retire()
        fixture.enterAcceptedWork()
        assertEquals(
            listOf(fixture.initialSurface, fixture.candidateSurface),
            fixture.targetPlatform.surfaceReleaseAttempts,
        )
    }

    // Verification: TGT-01
    @Test
    fun glCapacityDenialPreservesTheInstalledTargetAndReadablePlan() {
        val fixture = OwnerFixture()
        val opened = fixture.open()
        val denied = planWithOutput(fixture.initialPlan, 4_097, 1)

        assertTrue(fixture.owner.apply(denied))
        fixture.enterAcceptedWork()

        assertTrue(fixture.factPort.applyResults.single() is CaptureApplyResult.ResourceDenied)
        assertEquals(0, fixture.projectionPlatform.resizeCount)
        assertEquals(0, fixture.projectionPlatform.replacementSurfaceCount)
        fixture.deliverSourceFrame(fixture.initialSurfaceTexture)
        var read: CaptureReadResult? = null
        assertTrue(
            fixture.owner.read(
                fixture.initialPlan,
                opened.sourceIdentity,
                ByteBuffer.allocateDirect(fixture.initialPlan.rgbaCarrierByteCount),
            ) { read = it },
        )
        fixture.enterAcceptedWork()
        assertTrue(read is CaptureReadResult.Filled)
        fixture.owner.retire()
        fixture.enterAcceptedWork()
        assertEquals(1, fixture.egl.destroyContextCount)
    }

    // Verification: CAP-03
    // Verification: TGT-02
    @Test
    fun candidateGlesOutOfMemoryPreservesResourceClassificationWithOrWithoutAGeneratedName() {
        for (generatesName in listOf(true, false)) {
            val fixture = OwnerFixture()
            val opened = fixture.open()
            if (!generatesName) {
                every { fixture.gles.platform.genTextures(any()) } answers { firstArg<IntArray>()[0] = 0 }
            }
            every { fixture.gles.platform.getError() } returns GLES20.GL_OUT_OF_MEMORY

            assertTrue(fixture.owner.apply(fixture.replacementPlan))
            fixture.enterAcceptedWork()

            val failed = fixture.factPort.applyResults.single() as CaptureApplyResult.Failed
            assertSame(ScreenCaptureProblem.ResourceExhausted, failed.problem)
            assertSame(CaptureFailureScope.OwnerInvalidated, failed.scope)
            fixture.deliverSourceFrame(fixture.initialSurfaceTexture)
            var read: CaptureReadResult? = null
            assertTrue(
                fixture.owner.read(
                    fixture.initialPlan,
                    opened.sourceIdentity,
                    ByteBuffer.allocateDirect(fixture.initialPlan.rgbaCarrierByteCount),
                ) { read = it },
            )
            fixture.enterAcceptedWork()
            val rejected = read as CaptureReadResult.Failed
            assertSame(CaptureFailureScope.OwnerInvalidated, rejected.scope)
            assertFalse(rejected.sourceConsumed)
            assertEquals(0, fixture.gles.readPixelsCount)

            fixture.owner.retire()
            fixture.enterAcceptedWork()
            assertTrue(fixture.gles.deletedTextures.isEmpty())
            assertEquals(1, fixture.egl.destroyContextCount)
            assertEquals(1, fixture.egl.destroySurfaceCount)
            assertEquals(1, fixture.egl.releaseThreadCount)
            assertTrue(fixture.factPort.captureFailures.isEmpty())
        }
    }

    // Verification: CAP-03
    // Verification: TGT-02
    @Test
    fun androidCapacityDenialFollowedByRollbackGlesOutOfMemoryRemainsInternalFailure() {
        val fixture = OwnerFixture()
        fixture.open()
        val denial = Surface.OutOfResourcesException("candidate Surface denied")
        fixture.targetPlatform.candidateSurfaceCreationFailure = denial
        var groupReturns = 0
        every { fixture.gles.platform.getError() } answers {
            if (groupReturns++ == 0) GLES20.GL_NO_ERROR else GLES20.GL_OUT_OF_MEMORY
        }

        assertTrue(fixture.owner.apply(fixture.replacementPlan))
        fixture.enterAcceptedWork()

        val failed = fixture.factPort.applyResults.single() as CaptureApplyResult.Failed
        assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
        assertSame(denial, failed.cause)
        assertSame(CaptureFailureScope.OwnerInvalidated, failed.scope)
        assertEquals(listOf(fixture.candidateSurfaceTexture), fixture.targetPlatform.releasedSurfaceTextures)
        fixture.owner.retire()
        fixture.enterAcceptedWork()
        assertEquals(listOf(CANDIDATE_OES_TEXTURE), fixture.gles.deletedTextures)
        assertEquals(1, fixture.egl.destroyContextCount)
        assertEquals(1, fixture.egl.releaseThreadCount)
    }

    private fun planWithOutput(plan: CapturePlan, widthPx: Int, heightPx: Int): CapturePlan = CapturePlan(
        appliedSourceRect = plan.appliedSourceRect,
        rotation = plan.rotation,
        mirror = plan.mirror,
        colorMode = plan.colorMode,
        sourceWidthPx = plan.sourceWidthPx,
        sourceHeightPx = plan.sourceHeightPx,
        densityDpi = plan.densityDpi,
        targetMode = plan.targetMode,
        targetWidthPx = plan.targetWidthPx,
        targetHeightPx = plan.targetHeightPx,
        rgbaLayout = Rgba8888Layout.create(widthPx, heightPx),
    )

    private enum class TargetOpenSeam {
        CreateSurfaceTexture,
        SetDefaultBufferSize,
        CreateSurface,
    }

    private class RawTargetFixture(
        seam: TargetOpenSeam,
        failure: OutOfMemoryError,
    ) {
        val plan: CapturePlan = capturePlan(sourceWidthPx = 4, sourceHeightPx = 4)
        val surfaceTexture: SurfaceTexture = mockk()
        val gles = RecordingGlesPlatform()
        private val egl = RecordingEglPlatform()
        val eglOwner = EglOwner(egl.platform, gles.platform)
        val platform = RawOomeTargetPlatform(seam, failure, surfaceTexture)
        val target: TargetOwner

        init {
            eglOwner.open()
            target = TargetOwner(
                captureHandler = Handler(Looper.getMainLooper()),
                eglOwner = eglOwner,
                sourceSink = { error("Unexpected source callback") },
                callbackBoundary = object : CaptureCallbackBoundary {
                    override fun onCallbackException(identity: CaptureCallbackIdentity, failure: Exception) {
                        error("Unexpected callback failure: $failure")
                    }
                },
                platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                platform = platform,
            )
        }
    }

    private class RawOomeTargetPlatform(
        private val failingSeam: TargetOpenSeam,
        private val failure: OutOfMemoryError,
        private val surfaceTexture: SurfaceTexture,
    ) : TargetPlatform {
        private val surface: Surface = mockk()
        val enteredSeams = mutableListOf<TargetOpenSeam>()
        val releasedSurfaceTextures = mutableListOf<SurfaceTexture>()

        override fun createSurfaceTexture(oesTextureName: Int): SurfaceTexture {
            check(oesTextureName == INITIAL_OES_TEXTURE)
            enter(TargetOpenSeam.CreateSurfaceTexture)
            return surfaceTexture
        }

        override fun setDefaultBufferSize(surfaceTexture: SurfaceTexture, widthPx: Int, heightPx: Int) {
            check(surfaceTexture === this.surfaceTexture)
            enter(TargetOpenSeam.SetDefaultBufferSize)
        }

        override fun createSurface(surfaceTexture: SurfaceTexture): Surface {
            check(surfaceTexture === this.surfaceTexture)
            enter(TargetOpenSeam.CreateSurface)
            return surface
        }

        override fun setFrameListener(
            surfaceTexture: SurfaceTexture,
            listener: SurfaceTexture.OnFrameAvailableListener,
            handler: Handler,
        ) = error("Listener installation was not expected")

        override fun clearFrameListener(surfaceTexture: SurfaceTexture) =
            error("Listener removal was not expected")

        override fun updateTexImage(surfaceTexture: SurfaceTexture) =
            error("Frame update was not expected")

        override fun getTransformMatrix(surfaceTexture: SurfaceTexture, destination: FloatArray) =
            error("Transform read was not expected")

        override fun dataSpace(surfaceTexture: SurfaceTexture): Int =
            error("DataSpace read was not expected")

        override fun releaseSurface(surface: Surface) {
            error("No Surface was returned from the failing seam")
        }

        override fun releaseSurfaceTexture(surfaceTexture: SurfaceTexture) {
            check(surfaceTexture === this.surfaceTexture)
            releasedSurfaceTextures += surfaceTexture
        }

        private fun enter(seam: TargetOpenSeam) {
            enteredSeams += seam
            if (seam == failingSeam) throw failure
        }
    }

    private class OwnerFixture(
        val initialPlan: CapturePlan = capturePlan(sourceWidthPx = 4, sourceHeightPx = 4),
        val replacementPlan: CapturePlan = capturePlan(sourceWidthPx = 6, sourceHeightPx = 4),
        private val useRealCaptureThread: Boolean = false,
    ) {
        val projection: MediaProjection = mockk()
        val virtualDisplay: VirtualDisplay = mockk()
        val initialSurfaceTexture: SurfaceTexture = mockk()
        val candidateSurfaceTexture: SurfaceTexture = mockk()
        val initialSurface: Surface = mockk()
        val candidateSurface: Surface = mockk()
        val factPort = RecordingFactPort()
        val targetPlatform = RecordingTargetPlatform(
            initialSurfaceTexture = initialSurfaceTexture,
            candidateSurfaceTexture = candidateSurfaceTexture,
            initialSurface = initialSurface,
            candidateSurface = candidateSurface,
        )
        val projectionPlatform = RecordingProjectionPlatform(
            projection = projection,
            display = virtualDisplay,
            initialSurface = initialSurface,
            candidateSurface = candidateSurface,
        )
        val egl = RecordingEglPlatform()
        val gles = RecordingGlesPlatform()
        private val captureThread: HandlerThread = if (useRealCaptureThread) {
            HandlerThread("CaptureReplacementTest").apply { start() }
        } else {
            mockk()
        }
        private val captureHandler = Handler(if (useRealCaptureThread) captureThread.looper else Looper.getMainLooper())
        var captureThreadQuitCount = 0
            private set

        val owner: SessionCaptureOwner

        init {
            if (!useRealCaptureThread) {
                every { captureThread.quitSafely() } answers {
                    captureThreadQuitCount += 1
                    true
                }
            }
            val poster = object : HandlerTaskPoster {
                override fun post(handler: Handler, task: Runnable): Boolean = handler.post(task)
                override fun postDelayed(handler: Handler, task: Runnable, delayMillis: Long): Boolean =
                    handler.postDelayed(task, delayMillis)

                override fun removeCallbacks(handler: Handler, task: Runnable) = handler.removeCallbacks(task)
            }
            var readbackTimeNanos = READBACK_START_NANOS
            owner = SessionCaptureOwner(
                captureThread = captureThread,
                captureHandler = captureHandler,
                projectionCallbackHandler = captureHandler,
                handlerTaskPoster = poster,
                factPort = factPort,
                readbackClock = {
                    readbackTimeNanos.also { readbackTimeNanos += READBACK_DURATION_NANOS }
                },
                platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                projectionPlatform = projectionPlatform,
                eglPlatform = egl.platform,
                glesPlatform = gles.platform,
                targetPlatform = targetPlatform,
                projectionStopCompletion = ProjectionStopCompletion(),
            )
        }

        fun open(): CaptureOpenResult.Opened {
            owner.adoptProjection(projection)
            check(owner.open(initialPlan))
            enterAcceptedWork()
            return factPort.openResult as? CaptureOpenResult.Opened
                ?: error("Capture owner did not open")
        }

        fun enterAcceptedWork() {
            if (useRealCaptureThread) {
                val settled = CountDownLatch(1)
                check(captureHandler.post { settled.countDown() })
                check(settled.await(5, TimeUnit.SECONDS)) { "Capture commands did not return" }
            } else {
                shadowOf(Looper.getMainLooper()).idle()
            }
        }

        fun awaitCaptureThreadExit() {
            check(useRealCaptureThread)
            captureThread.join(5_000)
            check(!captureThread.isAlive) { "Test Capture thread did not finish after resize return" }
        }

        fun deliverSourceFrame(surfaceTexture: SurfaceTexture) {
            val listener = targetPlatform.listenerFor(surfaceTexture)
            check(captureHandler.post { listener.onFrameAvailable(surfaceTexture) })
            enterAcceptedWork()
        }
    }

    private class RecordingFactPort : SessionCaptureFactPort {
        var openResult: CaptureOpenResult? = null
            private set
        val applyResults = mutableListOf<CaptureApplyResult>()
        val sourceIdentities = mutableListOf<CaptureSourceIdentity>()
        val captureFailures = mutableListOf<Exception>()

        override fun onOpenReturned(result: CaptureOpenResult) {
            check(openResult == null)
            openResult = result
        }

        override fun onApplyReturned(result: CaptureApplyResult) {
            applyResults += result
        }

        override fun onSourceAvailable(sourceIdentity: CaptureSourceIdentity) {
            sourceIdentities += sourceIdentity
        }

        override fun onProjectionStopped(projectionIdentity: CaptureProjectionIdentity) =
            error("Unexpected projection stop")

        override fun onCapturedContentResize(projectionIdentity: CaptureProjectionIdentity, widthPx: Int, heightPx: Int) =
            error("Unexpected captured-content resize")

        override fun onCapturedContentVisibilityChanged(projectionIdentity: CaptureProjectionIdentity, isVisible: Boolean) =
            error("Unexpected captured-content visibility")

        override fun onCaptureFailure(failure: Exception) {
            captureFailures += failure
        }
    }

    private class RecordingProjectionPlatform(
        private val projection: MediaProjection,
        private val display: VirtualDisplay,
        private val initialSurface: Surface,
        private val candidateSurface: Surface,
    ) : ProjectionPlatform {
        val surfacesAtResize = mutableListOf<Surface>()
        val detachedSurfaces = mutableListOf<Surface>()
        var onResizeEntered: (() -> Unit)? = null
        var resizeFailure: Exception? = null
        var configuredDisplay: Triple<Int, Int, Int>? = null
            private set
        var replacementFailure: Exception? = null
        var releaseFailure: Exception? = null
        var platformAttachedSurface: Surface? = null
            private set
        var resizeCount = 0
            private set
        var replacementSurfaceCount = 0
            private set
        var detachSurfaceCount = 0
            private set
        var releaseCount = 0
            private set
        var unregisterCount = 0
            private set
        var stopCount = 0
            private set

        override fun registerCallback(projection: MediaProjection, callback: MediaProjection.Callback, handler: Handler) {
            check(projection === this.projection)
        }

        override fun createVirtualDisplay(
            projection: MediaProjection,
            widthPx: Int,
            heightPx: Int,
            densityDpi: Int,
            surface: Surface,
        ): VirtualDisplay {
            check(projection === this.projection)
            check(surface === initialSurface)
            platformAttachedSurface = surface
            configuredDisplay = Triple(widthPx, heightPx, densityDpi)
            return display
        }

        override fun resize(display: VirtualDisplay, widthPx: Int, heightPx: Int, densityDpi: Int) {
            check(display === this.display)
            resizeCount += 1
            surfacesAtResize += checkNotNull(platformAttachedSurface)
            // The platform may mutate before throwing; failure is not a rollback receipt.
            configuredDisplay = Triple(widthPx, heightPx, densityDpi)
            onResizeEntered?.invoke()
            resizeFailure?.let { throw it }
        }

        override fun setSurface(display: VirtualDisplay, surface: Surface?) {
            check(display === this.display)
            when {
                surface === candidateSurface -> {
                    replacementSurfaceCount += 1
                    platformAttachedSurface = candidateSurface
                    replacementFailure?.let { throw it }
                }

                surface == null -> {
                    detachSurfaceCount += 1
                    detachedSurfaces += checkNotNull(platformAttachedSurface)
                    platformAttachedSurface = null
                }

                else -> error("Unexpected VirtualDisplay Surface")
            }
        }

        override fun release(display: VirtualDisplay) {
            check(display === this.display)
            releaseCount += 1
            releaseFailure?.let { throw it }
            platformAttachedSurface = null
        }

        override fun unregisterCallback(projection: MediaProjection, callback: MediaProjection.Callback) {
            check(projection === this.projection)
            unregisterCount += 1
        }

        override fun stop(projection: MediaProjection) {
            check(projection === this.projection)
            stopCount += 1
        }
    }

    private class RecordingTargetPlatform(
        private val initialSurfaceTexture: SurfaceTexture,
        private val candidateSurfaceTexture: SurfaceTexture,
        private val initialSurface: Surface,
        private val candidateSurface: Surface,
    ) : TargetPlatform {
        var candidateSurfaceCreationFailure: Surface.OutOfResourcesException? = null
        var candidateSurfaceTextureReleaseFailure: Exception? = null
        val createdOesTextureNames = mutableListOf<Int>()
        val surfaceReleaseAttempts = mutableListOf<Surface>()
        val releasedSurfaces = mutableListOf<Surface>()
        val surfaceTextureReleaseAttempts = mutableListOf<SurfaceTexture>()
        val releasedSurfaceTextures = mutableListOf<SurfaceTexture>()
        val updatedSurfaceTextures = mutableListOf<SurfaceTexture>()
        val frameAccessSurfaceTextures = mutableListOf<SurfaceTexture>()
        var initialSurfaceReleaseFailure: Exception? = null
        var candidateSurfaceTextureReleaseAttemptCount = 0
            private set
        var initialListenerRemovalCount = 0
            private set
        var candidateListenerRemovalCount = 0
            private set
        private var initialListener: SurfaceTexture.OnFrameAvailableListener? = null
        private var candidateListener: SurfaceTexture.OnFrameAvailableListener? = null

        override fun createSurfaceTexture(oesTextureName: Int): SurfaceTexture {
            createdOesTextureNames += oesTextureName
            return when (oesTextureName) {
                INITIAL_OES_TEXTURE -> initialSurfaceTexture
                CANDIDATE_OES_TEXTURE -> candidateSurfaceTexture
                else -> error("Unexpected OES texture name")
            }
        }

        override fun setDefaultBufferSize(surfaceTexture: SurfaceTexture, widthPx: Int, heightPx: Int) = Unit

        override fun createSurface(surfaceTexture: SurfaceTexture): Surface = when {
            surfaceTexture === initialSurfaceTexture -> initialSurface
            surfaceTexture === candidateSurfaceTexture -> {
                candidateSurfaceCreationFailure?.let { throw it }
                candidateSurface
            }

            else -> error("Unexpected SurfaceTexture")
        }

        override fun setFrameListener(
            surfaceTexture: SurfaceTexture,
            listener: SurfaceTexture.OnFrameAvailableListener,
            handler: Handler,
        ) {
            when {
                surfaceTexture === initialSurfaceTexture -> initialListener = listener
                surfaceTexture === candidateSurfaceTexture -> candidateListener = listener
                else -> error("Unexpected SurfaceTexture listener")
            }
        }

        override fun clearFrameListener(surfaceTexture: SurfaceTexture) {
            when {
                surfaceTexture === initialSurfaceTexture -> initialListenerRemovalCount += 1
                surfaceTexture === candidateSurfaceTexture -> candidateListenerRemovalCount += 1
                else -> error("Unexpected SurfaceTexture listener removal")
            }
        }

        override fun updateTexImage(surfaceTexture: SurfaceTexture) {
            frameAccessSurfaceTextures += surfaceTexture
            updatedSurfaceTextures += surfaceTexture
        }

        override fun getTransformMatrix(surfaceTexture: SurfaceTexture, destination: FloatArray) {
            frameAccessSurfaceTextures += surfaceTexture
            destination.fill(0f)
            destination[0] = 1f
            destination[5] = 1f
            destination[10] = 1f
            destination[15] = 1f
        }

        override fun dataSpace(surfaceTexture: SurfaceTexture): Int {
            frameAccessSurfaceTextures += surfaceTexture
            return DataSpace.DATASPACE_UNKNOWN
        }

        override fun releaseSurface(surface: Surface) {
            surfaceReleaseAttempts += surface
            if (surface === initialSurface) initialSurfaceReleaseFailure?.let { throw it }
            releasedSurfaces += surface
        }

        override fun releaseSurfaceTexture(surfaceTexture: SurfaceTexture) {
            surfaceTextureReleaseAttempts += surfaceTexture
            if (surfaceTexture === candidateSurfaceTexture) {
                candidateSurfaceTextureReleaseAttemptCount += 1
                candidateSurfaceTextureReleaseFailure?.let { throw it }
            }
            releasedSurfaceTextures += surfaceTexture
        }

        fun listenerFor(surfaceTexture: SurfaceTexture): SurfaceTexture.OnFrameAvailableListener = when {
            surfaceTexture === initialSurfaceTexture -> checkNotNull(initialListener)
            surfaceTexture === candidateSurfaceTexture -> checkNotNull(candidateListener)
            else -> error("Unexpected SurfaceTexture listener lookup")
        }
    }

    private class RecordingEglPlatform {
        val platform: EglPlatform = mockk()
        private val display: EGLDisplay = mockk()
        private val config: EGLConfig = mockk()
        private val context: EGLContext = mockk()
        private val pbuffer: EGLSurface = mockk()
        private var currentDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var currentContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var currentSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var unbindCount = 0
            private set
        var destroyContextCount = 0
            private set
        var destroySurfaceCount = 0
            private set
        var releaseThreadCount = 0
            private set

        init {
            every { platform.currentDisplay } answers { currentDisplay }
            every { platform.currentContext } answers { currentContext }
            every { platform.currentReadSurface } answers { currentSurface }
            every { platform.currentDrawSurface } answers { currentSurface }
            every { platform.getDisplay() } returns display
            every { platform.initialize(display, any()) } answers {
                secondArg<IntArray>()[0] = 1
                secondArg<IntArray>()[1] = 5
                true
            }
            every { platform.chooseConfig(display, any(), any(), any()) } answers {
                arg<Array<EGLConfig?>>(2)[0] = config
                arg<IntArray>(3)[0] = 1
                true
            }
            every { platform.createContext(display, config, any()) } returns context
            every { platform.createPbufferSurface(display, config, any()) } returns pbuffer
            every { platform.makeCurrent(display, any(), any()) } answers {
                val returnedContext = thirdArg<EGLContext>()
                if (returnedContext === EGL14.EGL_NO_CONTEXT) {
                    unbindCount += 1
                    currentDisplay = EGL14.EGL_NO_DISPLAY
                    currentContext = EGL14.EGL_NO_CONTEXT
                    currentSurface = EGL14.EGL_NO_SURFACE
                } else {
                    currentDisplay = display
                    currentContext = context
                    currentSurface = pbuffer
                }
                true
            }
            every { platform.destroyContext(display, context) } answers {
                destroyContextCount += 1
                true
            }
            every { platform.destroySurface(display, pbuffer) } answers {
                destroySurfaceCount += 1
                true
            }
            every { platform.releaseDisplayInitialization(display) } returns true
            every { platform.releaseThread() } answers {
                releaseThreadCount += 1
                true
            }
            every { platform.getError() } returns EGL14.EGL_SUCCESS
        }
    }

    private class RecordingGlesPlatform {
        val platform: GlesPlatform = mockk(relaxed = true)
        val deletedTextures = mutableListOf<Int>()
        var readPixelsCount = 0
            private set
        private val generatedTextureNames = intArrayOf(INITIAL_OES_TEXTURE, OUTPUT_TEXTURE, CANDIDATE_OES_TEXTURE)
        private var nextTextureIndex = 0
        private var nextFramebufferName = FRAMEBUFFER_NAME
        private var nextShaderName = FIRST_SHADER_NAME
        private var nextProgramName = PROGRAM_NAME

        init {
            every { platform.getError() } returns GLES20.GL_NO_ERROR
            every { platform.getInteger(any(), any()) } answers {
                when (firstArg<Int>()) {
                    GLES20.GL_MAX_TEXTURE_SIZE -> secondArg<IntArray>()[0] = 4_096
                    GLES20.GL_MAX_VIEWPORT_DIMS -> {
                        secondArg<IntArray>()[0] = 4_096
                        secondArg<IntArray>()[1] = 4_096
                    }

                    GLES20.GL_RED_BITS,
                    GLES20.GL_GREEN_BITS,
                    GLES20.GL_BLUE_BITS,
                        -> secondArg<IntArray>()[0] = 8

                    else -> error("Unexpected GLES integer query")
                }
            }
            every { platform.getShaderPrecisionFormat(any(), any()) } answers {
                firstArg<IntArray>().fill(0)
                secondArg<IntArray>().fill(0)
            }
            every { platform.genTextures(any()) } answers {
                firstArg<IntArray>()[0] = generatedTextureNames[nextTextureIndex++]
            }
            every { platform.deleteTextures(any()) } answers {
                deletedTextures += firstArg<IntArray>()[0]
            }
            every { platform.genFramebuffers(any()) } answers {
                firstArg<IntArray>()[0] = nextFramebufferName++
            }
            every { platform.createShader(any()) } answers { nextShaderName++ }
            every { platform.getShaderStatus(any(), any()) } answers {
                secondArg<IntArray>()[0] = GLES20.GL_TRUE
            }
            every { platform.createProgram() } answers { nextProgramName++ }
            every { platform.getProgramStatus(any(), any()) } answers {
                secondArg<IntArray>()[0] = GLES20.GL_TRUE
            }
            every { platform.getUniformLocation(any(), any()) } returns 1
            every { platform.checkFramebufferStatus() } returns GLES20.GL_FRAMEBUFFER_COMPLETE
            every { platform.readPixels(any(), any(), any()) } answers {
                readPixelsCount += 1
            }
        }
    }

    private companion object {
        private const val INITIAL_OES_TEXTURE = 101
        private const val OUTPUT_TEXTURE = 102
        private const val CANDIDATE_OES_TEXTURE = 103
        private const val FRAMEBUFFER_NAME = 201
        private const val FIRST_SHADER_NAME = 301
        private const val PROGRAM_NAME = 401
        private const val READBACK_START_NANOS = 10L
        private const val READBACK_DURATION_NANOS = 7L

        private fun capturePlan(
            sourceWidthPx: Int,
            sourceHeightPx: Int,
            targetMode: CaptureTargetMode = CaptureTargetMode.Full,
            targetWidthPx: Int = sourceWidthPx,
            targetHeightPx: Int = sourceHeightPx,
            rotation: Rotation = Rotation.Degrees0,
            densityDpi: Int = 320,
        ): CapturePlan = CapturePlan(
            appliedSourceRect = ImageRect.create(0, 0, sourceWidthPx, sourceHeightPx),
            rotation = rotation,
            mirror = Mirror.None,
            colorMode = ColorMode.Color,
            sourceWidthPx = sourceWidthPx,
            sourceHeightPx = sourceHeightPx,
            densityDpi = densityDpi,
            targetMode = targetMode,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
            rgbaLayout = Rgba8888Layout.create(widthPx = 2, heightPx = 2),
        )
    }
}
