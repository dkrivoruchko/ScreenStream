package io.screenstream.capture.internal.capture

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import io.mockk.mockk
import io.screenstream.capture.ColorMode
import io.screenstream.capture.ImageRect
import io.screenstream.capture.Mirror
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.Buffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
internal class EglOwnerLifecycleTest {
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

    // Verification: CAP-03
    @Test
    fun openValidatesEs2PbufferAndLimits() {
        listOf(
            PrecisionCase(intArrayOf(127, 127), 23, EglOwner.FragmentPrecision.High),
            PrecisionCase(intArrayOf(0, 0), 0, EglOwner.FragmentPrecision.Medium),
        ).forEach { case ->
            val fixture = Fixture(
                maxTextureSize = 64,
                maxViewportWidth = 80,
                maxViewportHeight = 80,
                highFloatRange = case.range,
                highFloatPrecision = case.precision,
            )

            assertSame(case.expected, fixture.owner.open())

            assertRequiredConfigAttributes(fixture.egl.chooseConfigAttributes.single())
            assertArrayEquals(
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                fixture.egl.contextAttributes.single(),
            )
            assertArrayEquals(
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                fixture.egl.pbufferAttributes.single(),
            )
            val bind = fixture.egl.makeCurrentCalls.single()
            assertSame(fixture.egl.ownedDisplay, bind.display)
            assertSame(fixture.egl.pbuffer, bind.surface)
            assertSame(fixture.egl.context, bind.context)
            assertEquals(
                listOf(
                    "getDisplay",
                    "initialize",
                    "chooseConfig",
                    "createContext",
                    "createPbufferSurface",
                    "makeCurrent:bind",
                ),
                fixture.egl.calls.take(6),
            )
            assertEquals(
                setOf("currentDisplay", "currentContext", "currentReadSurface", "currentDrawSurface"),
                fixture.egl.calls.drop(6).toSet(),
            )
            assertEquals(10, fixture.egl.calls.size)
            assertEquals(
                listOf(
                    "getInteger:${GLES20.GL_MAX_TEXTURE_SIZE}",
                    "getInteger:${GLES20.GL_MAX_VIEWPORT_DIMS}",
                    "getShaderPrecisionFormat",
                    "getError",
                ),
                fixture.gl.calls,
            )
            assertTrue(fixture.owner.isHealthy)

            fixture.owner.validateTargetAndOutput(plan(64, 64, 64, 64))

            assertCleanCloseWithoutRetry(fixture)
        }

        val viewportFixture = Fixture(maxTextureSize = 100, maxViewportWidth = 80, maxViewportHeight = 60)
        viewportFixture.owner.open()
        viewportFixture.owner.validateTargetAndOutput(plan(80, 60, 80, 60))
        listOf(
            plan(81, 60, 80, 60),
            plan(80, 61, 80, 60),
            plan(80, 60, 81, 60),
            plan(80, 60, 80, 61),
        ).forEach { rejected ->
            val failure = assertThrows(CaptureBoundaryFailure::class.java) {
                viewportFixture.owner.validateTargetAndOutput(rejected)
            }
            assertSame(ScreenCaptureProblem.ResourceExhausted, failure.problem)
            assertTrue(viewportFixture.owner.isHealthy)
        }
        assertCleanCloseWithoutRetry(viewportFixture)

        listOf(
            Fixture(highFloatRange = intArrayOf(1, 0), highFloatPrecision = 1),
            Fixture(maxTextureSize = 0),
        ).forEach { malformed ->
            val failure = assertThrows(CaptureBoundaryFailure::class.java) { malformed.owner.open() }
            assertSame(ScreenCaptureProblem.InternalFailure, failure.problem)
            assertFalse(malformed.owner.isHealthy)
            assertEquals(1, malformed.gl.calls.count { it == "getError" })
            malformed.owner.close()
        }
    }

    // Verification: CAP-03
    @Test
    fun openRejectsInvalidConfigOrBinding() {
        val malformedConfig = Fixture().apply {
            egl.selectedConfigCount = 0
        }
        val configFailure = assertThrows(CaptureBoundaryFailure::class.java) { malformedConfig.owner.open() }
        assertSame(ScreenCaptureProblem.InternalFailure, configFailure.problem)
        val configClose = malformedConfig.owner.close()
        assertNull(configClose.cleanupFailure)
        assertNull(configClose.residue)
        assertNull(configClose.namespaceDestroyedProof)
        assertEquals(0, malformedConfig.egl.createContextCalls.size)
        assertEquals(0, malformedConfig.egl.destroyContextCalls.size)
        assertEquals(0, malformedConfig.egl.releaseThreadCount)

        listOf(
            OpenBindingFailureCase("makeCurrent false") { platform ->
                platform.bindResult = false
                platform.enqueueError(EGL14.EGL_BAD_CONTEXT)
            },
            OpenBindingFailureCase("current display mismatch") { platform ->
                platform.bindCurrentDisplayOverride = mockk()
            },
            OpenBindingFailureCase("current context mismatch") { platform ->
                platform.bindCurrentContextOverride = mockk()
            },
            OpenBindingFailureCase("current read mismatch") { platform ->
                platform.bindCurrentReadSurfaceOverride = mockk()
            },
            OpenBindingFailureCase("current draw mismatch") { platform ->
                platform.bindCurrentDrawSurfaceOverride = mockk()
            },
        ).forEach { case ->
            val fixture = Fixture()
            case.configure(fixture.egl)
            val currentFailure = assertThrows(CaptureBoundaryFailure::class.java) { fixture.owner.open() }
            assertSame(case.name, ScreenCaptureProblem.InternalFailure, currentFailure.problem)
            assertTrue(case.name, fixture.gl.calls.isEmpty())
            val callsBeforeClose = fixture.egl.calls.toList()

            val firstClose = fixture.owner.close()
            val repeatedClose = fixture.owner.close()

            assertNull(case.name, firstClose.cleanupFailure)
            assertNotNull(case.name, firstClose.residue)
            assertNull(case.name, firstClose.namespaceDestroyedProof)
            assertNull(case.name, repeatedClose.cleanupFailure)
            assertNotNull(case.name, repeatedClose.residue)
            assertNull(case.name, repeatedClose.namespaceDestroyedProof)
            assertEquals(case.name, callsBeforeClose, fixture.egl.calls)
            assertEquals(case.name, 0, fixture.egl.destroyContextCalls.size)
            assertEquals(case.name, 0, fixture.egl.destroySurfaceCalls.size)
            assertEquals(case.name, 0, fixture.egl.releaseThreadCount)
        }
    }

    // Verification: CAP-03
    // Verification: CAP-04
    @Test
    fun glesFailuresUsePostprobeAndPrecedence() {
        openedFixture().let { fixture ->
            fixture.gl.clearCalls()
            fixture.owner.runGlesGroup { true }
            assertEquals(listOf("getError"), fixture.gl.calls)
            assertTrue(fixture.owner.isHealthy)
            fixture.owner.close()
        }

        openedFixture().let { fixture ->
            fixture.gl.clearCalls()
            fixture.gl.enqueueError(GLES20.GL_OUT_OF_MEMORY)
            val failure = assertThrows(CaptureBoundaryFailure::class.java) {
                fixture.owner.runGlesGroup { true }
            }
            assertSame(ScreenCaptureProblem.ResourceExhausted, failure.problem)
            assertEquals(listOf("getError"), fixture.gl.calls)
            assertQuarantinedBeforeAnotherGroup(fixture)
            fixture.owner.close()
        }

        openedFixture().let { fixture ->
            fixture.gl.clearCalls()
            fixture.gl.enqueueError(GLES20.GL_INVALID_OPERATION)
            val failure = assertThrows(CaptureBoundaryFailure::class.java) {
                fixture.owner.runGlesGroup { true }
            }
            assertSame(ScreenCaptureProblem.InternalFailure, failure.problem)
            assertEquals(listOf("getError"), fixture.gl.calls)
            assertQuarantinedBeforeAnotherGroup(fixture)
            fixture.owner.close()
        }

        openedFixture().let { fixture ->
            fixture.gl.clearCalls()
            fixture.gl.enqueueError(GLES20.GL_OUT_OF_MEMORY)
            val commandFailure = IllegalStateException("command failed")
            val failure = assertThrows(CaptureBoundaryFailure::class.java) {
                fixture.owner.runGlesGroup { throw commandFailure }
            }
            assertSame(ScreenCaptureProblem.InternalFailure, failure.problem)
            assertSame(commandFailure, failure.physicalCause)
            assertSame(commandFailure, failure.cause)
            assertEquals(listOf("getError"), fixture.gl.calls)
            assertQuarantinedBeforeAnotherGroup(fixture)
            fixture.owner.close()
        }

        openedFixture().let { fixture ->
            fixture.gl.clearCalls()
            fixture.gl.enqueueError(GLES20.GL_OUT_OF_MEMORY)
            val physicalCause = IllegalArgumentException("already classified")
            val commandFailure = CaptureBoundaryFailure(ScreenCaptureProblem.CaptureUnavailable, physicalCause)
            val escaped = assertThrows(CaptureBoundaryFailure::class.java) {
                fixture.owner.runGlesGroup { throw commandFailure }
            }
            assertSame(commandFailure, escaped)
            assertSame(physicalCause, escaped.physicalCause)
            assertEquals(listOf("getError"), fixture.gl.calls)
            assertQuarantinedBeforeAnotherGroup(fixture)
            fixture.owner.close()
        }

        openedFixture().let { fixture ->
            fixture.gl.clearCalls()
            val postprobeFailure = IllegalStateException("postprobe failed")
            fixture.gl.enqueueError(postprobeFailure)
            val failure = assertThrows(CaptureBoundaryFailure::class.java) {
                fixture.owner.runGlesGroup { true }
            }
            assertSame(ScreenCaptureProblem.InternalFailure, failure.problem)
            assertSame(postprobeFailure, failure.physicalCause)
            assertSame(postprobeFailure, failure.cause)
            assertEquals(listOf("getError"), fixture.gl.calls)
            assertQuarantinedBeforeAnotherGroup(fixture)
            fixture.owner.close()
        }

        openedFixture().let { fixture ->
            fixture.gl.clearCalls()
            val failure = assertThrows(CaptureBoundaryFailure::class.java) {
                fixture.owner.runGlesGroup { false }
            }
            assertSame(ScreenCaptureProblem.InternalFailure, failure.problem)
            assertEquals(listOf("getError"), fixture.gl.calls)
            assertQuarantinedBeforeAnotherGroup(fixture)
            fixture.owner.close()
        }
    }

    // Verification: CAP-03
    @Test
    fun outOfMemoryEscapesBeforeCleanup() {
        openedFixture().let { fixture ->
            fixture.gl.clearCalls()
            val failure = OutOfMemoryError("command OOME")

            val escaped = assertThrows(OutOfMemoryError::class.java) {
                fixture.owner.runGlesGroup { throw failure }
            }

            assertSame(failure, escaped)
            assertTrue(fixture.gl.calls.isEmpty())
            assertEquals(0, fixture.egl.destroyContextCalls.size)
            assertEquals(0, fixture.egl.destroySurfaceCalls.size)
            assertEquals(0, fixture.egl.releaseThreadCount)
            fixture.owner.close()
        }

        openedFixture().let { fixture ->
            fixture.gl.clearCalls()
            val failure = OutOfMemoryError("postprobe OOME")
            fixture.gl.enqueueError(failure)

            val escaped = assertThrows(OutOfMemoryError::class.java) {
                fixture.owner.runGlesGroup { true }
            }

            assertSame(failure, escaped)
            assertEquals(listOf("getError"), fixture.gl.calls)
            assertEquals(0, fixture.egl.destroyContextCalls.size)
            assertEquals(0, fixture.egl.destroySurfaceCalls.size)
            assertEquals(0, fixture.egl.releaseThreadCount)
            fixture.owner.close()
        }

        Fixture().let { fixture ->
            val failure = OutOfMemoryError("pbuffer OOME")
            fixture.egl.createPbufferFailure = failure

            val escaped = assertThrows(OutOfMemoryError::class.java) { fixture.owner.open() }

            assertSame(failure, escaped)
            assertEquals(0, fixture.egl.destroyContextCalls.size)
            assertEquals(0, fixture.egl.makeCurrentCalls.size)
            assertEquals(0, fixture.egl.releaseThreadCount)
            val retirement = fixture.owner.close()
            assertNull(retirement.cleanupFailure)
            assertNull(retirement.residue)
            assertTrue(retirement.namespaceDestroyedProof?.matches(fixture.owner) == true)
            assertEquals(1, fixture.egl.destroyContextCalls.size)
        }
    }

    // Verification: CAP-03
    @Test
    fun pbufferFailureRetiresContextOnce() {
        val acquisitionException = IllegalArgumentException("pbuffer failed")
        val destroyException = IllegalStateException("context destroy failed")
        val cases = listOf(
            PbufferFailureCase("bad-alloc", EGL14.EGL_BAD_ALLOC, null, DestroyOutcome.Success),
            PbufferFailureCase("bad-alloc/destroy-false", EGL14.EGL_BAD_ALLOC, null, DestroyOutcome.ReturnFalse),
            PbufferFailureCase("bad-alloc/destroy-throws", EGL14.EGL_BAD_ALLOC, null, DestroyOutcome.Throw(destroyException)),
            PbufferFailureCase("exception", null, acquisitionException, DestroyOutcome.Success),
            PbufferFailureCase("other-egl-error", EGL14.EGL_BAD_SURFACE, null, DestroyOutcome.Success),
        )

        cases.forEach { case ->
            val fixture = Fixture()
            if (case.eglError != null) {
                fixture.egl.createdPbuffer = EGL14.EGL_NO_SURFACE
                fixture.egl.enqueueError(case.eglError)
            } else {
                fixture.egl.createPbufferFailure = case.acquisitionFailure
            }
            when (val destroy = case.destroyOutcome) {
                DestroyOutcome.Success -> Unit
                DestroyOutcome.ReturnFalse -> {
                    fixture.egl.destroyContextResult = false
                    fixture.egl.enqueueError(EGL14.EGL_BAD_CONTEXT)
                }

                is DestroyOutcome.Throw -> fixture.egl.destroyContextFailure = destroy.failure
            }

            val acquisitionFailure = captureThrowable { fixture.owner.open() }

            if (case.acquisitionFailure != null) {
                assertSame(case.acquisitionFailure, acquisitionFailure)
            } else {
                assertTrue(case.name, acquisitionFailure is CaptureBoundaryFailure)
                acquisitionFailure as CaptureBoundaryFailure
                val expected = if (case.eglError == EGL14.EGL_BAD_ALLOC) {
                    ScreenCaptureProblem.ResourceExhausted
                } else {
                    ScreenCaptureProblem.InternalFailure
                }
                assertSame(case.name, expected, acquisitionFailure.problem)
            }
            assertEquals(case.name, 0, fixture.egl.destroyContextCalls.size)
            assertEquals(case.name, 0, fixture.egl.makeCurrentCalls.size)
            assertEquals(case.name, 0, fixture.egl.destroySurfaceCalls.size)
            assertEquals(case.name, 0, fixture.egl.releaseThreadCount)

            val first = fixture.owner.close()
            val callsAfterFirst = fixture.egl.calls.toList()
            val repeated = fixture.owner.close()

            assertEquals(case.name, 1, fixture.egl.destroyContextCalls.size)
            assertSame(case.name, fixture.egl.ownedDisplay, fixture.egl.destroyContextCalls.single().display)
            assertSame(case.name, fixture.egl.context, fixture.egl.destroyContextCalls.single().context)
            assertEquals(case.name, 0, fixture.egl.destroySurfaceCalls.size)
            assertEquals(case.name, 0, fixture.egl.releaseThreadCount)
            assertEquals(case.name, callsAfterFirst, fixture.egl.calls)
            when (val destroy = case.destroyOutcome) {
                DestroyOutcome.Success -> {
                    assertNull(case.name, first.cleanupFailure)
                    assertNull(case.name, first.residue)
                    assertTrue(case.name, first.namespaceDestroyedProof?.matches(fixture.owner) == true)
                    assertNull(case.name, repeated.cleanupFailure)
                    assertNull(case.name, repeated.residue)
                }

                DestroyOutcome.ReturnFalse -> {
                    assertNotNull(case.name, first.cleanupFailure)
                    assertSame(case.name, first.cleanupFailure, first.residue)
                    assertNull(case.name, first.namespaceDestroyedProof)
                    assertSame(case.name, first.cleanupFailure, repeated.cleanupFailure)
                    assertSame(case.name, first.residue, repeated.residue)
                }

                is DestroyOutcome.Throw -> {
                    assertSame(case.name, destroy.failure, first.cleanupFailure)
                    assertSame(case.name, destroy.failure, first.residue)
                    assertNull(case.name, first.namespaceDestroyedProof)
                    assertSame(case.name, destroy.failure, repeated.cleanupFailure)
                    assertSame(case.name, destroy.failure, repeated.residue)
                }
            }
        }
    }

    // Verification: CAP-03
    @Test
    fun bindingThreadAndUnbindGateRetirement() {
        val fixture = openedFixture()
        fixture.gl.clearCalls()
        val wrongThreadClose = AtomicReference<EglOwner.EglRetirementOutcome>()
        val wrongThreadGles = AtomicReference<Throwable>()
        var commandEntered = false
        val worker = Thread {
            wrongThreadClose.set(fixture.owner.close())
            wrongThreadGles.set(captureThrowable {
                fixture.owner.runGlesGroup {
                    commandEntered = true
                    true
                }
            })
        }
        worker.start()
        worker.join(5_000L)
        assertFalse("worker did not finish", worker.isAlive)

        assertNull(wrongThreadClose.get().cleanupFailure)
        assertNotNull(wrongThreadClose.get().residue)
        assertNull(wrongThreadClose.get().namespaceDestroyedProof)
        assertTrue(wrongThreadGles.get() is CaptureBoundaryFailure)
        assertSame(ScreenCaptureProblem.InternalFailure, (wrongThreadGles.get() as CaptureBoundaryFailure).problem)
        assertFalse(commandEntered)
        assertTrue(fixture.gl.calls.isEmpty())
        assertEquals(1, fixture.egl.makeCurrentCalls.size)
        assertEquals(0, fixture.egl.destroyContextCalls.size)
        assertNull(fixture.owner.close().residue)
        assertExactUnbindCall(fixture)

        val unbindException = IllegalStateException("unbind failed")
        listOf(
            UnbindFailureCase("returned false", returnedFalse = true),
            UnbindFailureCase("returned true without proof", currentContextAfterReturn = mockk()),
            UnbindFailureCase("threw Exception", failure = unbindException),
        ).forEach { case ->
            val blocked = openedFixture()
            if (case.returnedFalse) {
                blocked.egl.unbindResult = false
                blocked.egl.enqueueError(EGL14.EGL_BAD_CONTEXT)
            }
            blocked.egl.unbindCurrentContextOverride = case.currentContextAfterReturn
            blocked.egl.unbindFailure = case.failure
            val callsBefore = blocked.egl.calls.size

            val first = blocked.owner.close()
            val callsAfterFirst = blocked.egl.calls.toList()
            val repeated = blocked.owner.close()

            assertNotNull(first.cleanupFailure)
            assertSame(first.cleanupFailure, first.residue)
            assertNull(first.namespaceDestroyedProof)
            assertSame(first.cleanupFailure, repeated.cleanupFailure)
            assertSame(first.residue, repeated.residue)
            assertEquals(callsAfterFirst, blocked.egl.calls)
            assertEquals(2, blocked.egl.makeCurrentCalls.size)
            assertExactUnbindCall(blocked)
            assertEquals(0, blocked.egl.destroyContextCalls.size)
            assertEquals(0, blocked.egl.destroySurfaceCalls.size)
            assertEquals(0, blocked.egl.releaseThreadCount)
            assertTrue(blocked.egl.calls.size > callsBefore)
            case.failure?.let { failure ->
                assertSame(case.name, failure, first.cleanupFailure)
                assertSame(case.name, failure, first.residue)
            }
        }
    }

    // Verification: CAP-03
    @Test
    fun closeRunsCleanupAndPreservesFailurePrecedence() {
        val contextFailure = IllegalStateException("context destroy failed")
        val surfaceFailure = IllegalArgumentException("surface destroy failed")
        val releaseFailure = UnsupportedOperationException("thread release failed")
        val cases = listOf(
            CloseCase("success"),
            CloseCase("context", contextFailure = contextFailure),
            CloseCase("surface", surfaceFailure = surfaceFailure),
            CloseCase("release", releaseFailure = releaseFailure),
            CloseCase(
                "all",
                contextFailure = contextFailure,
                surfaceFailure = surfaceFailure,
                releaseFailure = releaseFailure,
            ),
        )

        cases.forEach { case ->
            val fixture = openedFixture()
            fixture.egl.destroyContextFailure = case.contextFailure
            fixture.egl.destroySurfaceFailure = case.surfaceFailure
            fixture.egl.releaseThreadFailure = case.releaseFailure
            val callsBefore = fixture.egl.calls.size

            val first = fixture.owner.close()
            val suffix = fixture.egl.calls.drop(callsBefore)
            val callsAfterFirst = fixture.egl.calls.toList()
            val repeated = fixture.owner.close()

            assertEquals(
                case.name,
                listOf("makeCurrent:unbind", "currentContext", "destroyContext", "destroySurface", "releaseThread"),
                suffix,
            )
            assertEquals(case.name, callsAfterFirst, fixture.egl.calls)
            assertEquals(case.name, 2, fixture.egl.makeCurrentCalls.size)
            assertExactUnbindCall(fixture)
            assertEquals(case.name, 1, fixture.egl.destroyContextCalls.size)
            assertEquals(case.name, 1, fixture.egl.destroySurfaceCalls.size)
            assertEquals(case.name, 1, fixture.egl.releaseThreadCount)
            assertSame(case.name, fixture.egl.context, fixture.egl.destroyContextCalls.single().context)
            assertSame(case.name, fixture.egl.pbuffer, fixture.egl.destroySurfaceCalls.single().surface)

            val expectedFirstFailure = case.contextFailure ?: case.surfaceFailure ?: case.releaseFailure
            assertSame(case.name, expectedFirstFailure, first.cleanupFailure)
            assertSame(case.name, expectedFirstFailure, repeated.cleanupFailure)
            val expectedResidue = case.contextFailure ?: case.surfaceFailure ?: case.releaseFailure
            assertSame(case.name, expectedResidue, first.residue)
            assertSame(case.name, expectedResidue, repeated.residue)
            if (case.contextFailure == null) {
                assertTrue(case.name, first.namespaceDestroyedProof?.matches(fixture.owner) == true)
            } else {
                assertNull(case.name, first.namespaceDestroyedProof)
            }
            if (expectedFirstFailure == null) {
                assertNull(case.name, repeated.namespaceDestroyedProof)
            }
        }

        val rawOomeFixture = openedFixture()
        val rawOome = OutOfMemoryError("context destroy OOME")
        rawOomeFixture.egl.destroyContextFailure = rawOome

        val escaped = assertThrows(OutOfMemoryError::class.java) { rawOomeFixture.owner.close() }

        assertSame(rawOome, escaped)
        assertExactUnbindCall(rawOomeFixture)
        assertEquals(1, rawOomeFixture.egl.destroyContextCalls.size)
        assertEquals(0, rawOomeFixture.egl.destroySurfaceCalls.size)
        assertEquals(0, rawOomeFixture.egl.releaseThreadCount)
        val callsBeforeSecond = rawOomeFixture.egl.calls.size
        val second = rawOomeFixture.owner.close()
        assertEquals(
            listOf("destroySurface", "releaseThread"),
            rawOomeFixture.egl.calls.drop(callsBeforeSecond),
        )
        val callsAfterSecond = rawOomeFixture.egl.calls.toList()
        val repeated = rawOomeFixture.owner.close()
        assertEquals(1, rawOomeFixture.egl.destroyContextCalls.size)
        assertEquals(1, rawOomeFixture.egl.destroySurfaceCalls.size)
        assertSame(rawOomeFixture.egl.ownedDisplay, rawOomeFixture.egl.destroySurfaceCalls.single().display)
        assertSame(rawOomeFixture.egl.pbuffer, rawOomeFixture.egl.destroySurfaceCalls.single().surface)
        assertEquals(1, rawOomeFixture.egl.releaseThreadCount)
        assertNull(second.cleanupFailure)
        assertNotNull(second.residue)
        assertNull(second.namespaceDestroyedProof)
        assertNull(repeated.cleanupFailure)
        assertNotNull(repeated.residue)
        assertNull(repeated.namespaceDestroyedProof)
        assertEquals(callsAfterSecond, rawOomeFixture.egl.calls)
    }

    private fun assertCleanCloseWithoutRetry(fixture: Fixture) {
        val callsBeforeClose = fixture.egl.calls.size
        val first = fixture.owner.close()
        val callsAfterFirst = fixture.egl.calls.toList()
        val repeated = fixture.owner.close()

        assertNull(first.cleanupFailure)
        assertNull(first.residue)
        assertTrue(first.namespaceDestroyedProof?.matches(fixture.owner) == true)
        assertExactUnbindCall(fixture)
        assertEquals(
            listOf("makeCurrent:unbind", "currentContext", "destroyContext", "destroySurface", "releaseThread"),
            fixture.egl.calls.drop(callsBeforeClose),
        )
        assertNull(repeated.cleanupFailure)
        assertNull(repeated.residue)
        assertNull(repeated.namespaceDestroyedProof)
        assertEquals(callsAfterFirst, fixture.egl.calls)
    }

    private fun assertExactUnbindCall(fixture: Fixture) {
        val unbind = fixture.egl.makeCurrentCalls.single { it.context === EGL14.EGL_NO_CONTEXT }
        assertSame(fixture.egl.ownedDisplay, unbind.display)
        assertSame(EGL14.EGL_NO_SURFACE, unbind.surface)
        assertSame(EGL14.EGL_NO_CONTEXT, unbind.context)
    }

    private fun assertRequiredConfigAttributes(attributes: IntArray) {
        assertEquals(EGL14.EGL_NONE, attributes.last())
        val pairs = attributes.dropLast(1).chunked(2).associate { pair -> pair[0] to pair[1] }
        assertTrue(checkNotNull(pairs[EGL14.EGL_SURFACE_TYPE]) and EGL14.EGL_PBUFFER_BIT != 0)
        assertTrue(checkNotNull(pairs[EGL14.EGL_RENDERABLE_TYPE]) and EGL14.EGL_OPENGL_ES2_BIT != 0)
        assertTrue(checkNotNull(pairs[EGL14.EGL_CONFORMANT]) and EGL14.EGL_OPENGL_ES2_BIT != 0)
        assertTrue(checkNotNull(pairs[EGL14.EGL_RED_SIZE]) >= 8)
        assertTrue(checkNotNull(pairs[EGL14.EGL_GREEN_SIZE]) >= 8)
        assertTrue(checkNotNull(pairs[EGL14.EGL_BLUE_SIZE]) >= 8)
        assertTrue(checkNotNull(pairs[EGL14.EGL_ALPHA_SIZE]) >= 8)
        assertEquals(0, pairs[EGL14.EGL_DEPTH_SIZE])
        assertEquals(0, pairs[EGL14.EGL_STENCIL_SIZE])
    }

    private fun assertQuarantinedBeforeAnotherGroup(fixture: Fixture) {
        assertFalse(fixture.owner.isHealthy)
        val callsBefore = fixture.gl.calls.toList()
        var commandEntered = false
        val failure = assertThrows(CaptureBoundaryFailure::class.java) {
            fixture.owner.runGlesGroup {
                commandEntered = true
                true
            }
        }
        assertSame(ScreenCaptureProblem.InternalFailure, failure.problem)
        assertFalse(commandEntered)
        assertEquals(callsBefore, fixture.gl.calls)
    }

    private fun openedFixture(): Fixture = Fixture().also { it.owner.open() }

    private fun captureThrowable(block: () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("expected a Throwable")
    }

    private fun plan(
        targetWidthPx: Int,
        targetHeightPx: Int,
        outputWidthPx: Int,
        outputHeightPx: Int,
    ): CapturePlan = CapturePlan(
        appliedSourceRect = ImageRect.create(0, 0, targetWidthPx, targetHeightPx),
        rotation = Rotation.Degrees0,
        mirror = Mirror.None,
        colorMode = ColorMode.Color,
        sourceWidthPx = targetWidthPx,
        sourceHeightPx = targetHeightPx,
        densityDpi = 1,
        targetMode = CaptureTargetMode.Full,
        targetWidthPx = targetWidthPx,
        targetHeightPx = targetHeightPx,
        rgbaLayout = Rgba8888Layout.create(outputWidthPx, outputHeightPx),
    )

    private class Fixture(
        maxTextureSize: Int = 4_096,
        maxViewportWidth: Int = 4_096,
        maxViewportHeight: Int = 4_096,
        highFloatRange: IntArray = intArrayOf(127, 127),
        highFloatPrecision: Int = 23,
    ) {
        val egl = RecordingEglPlatform()
        val gl = RecordingGlesPlatform(
            maxTextureSize = maxTextureSize,
            maxViewportWidth = maxViewportWidth,
            maxViewportHeight = maxViewportHeight,
            highFloatRange = highFloatRange,
            highFloatPrecision = highFloatPrecision,
        )
        val owner = EglOwner(egl, gl)
    }

    private data class PrecisionCase(
        val range: IntArray,
        val precision: Int,
        val expected: EglOwner.FragmentPrecision,
    )

    private data class OpenBindingFailureCase(
        val name: String,
        val configure: (RecordingEglPlatform) -> Unit,
    )

    private data class UnbindFailureCase(
        val name: String,
        val returnedFalse: Boolean = false,
        val currentContextAfterReturn: EGLContext? = null,
        val failure: Exception? = null,
    )

    private data class PbufferFailureCase(
        val name: String,
        val eglError: Int?,
        val acquisitionFailure: Throwable?,
        val destroyOutcome: DestroyOutcome,
    )

    private sealed interface DestroyOutcome {
        data object Success : DestroyOutcome
        data object ReturnFalse : DestroyOutcome
        data class Throw(val failure: Exception) : DestroyOutcome
    }

    private data class CloseCase(
        val name: String,
        val contextFailure: Exception? = null,
        val surfaceFailure: Exception? = null,
        val releaseFailure: Exception? = null,
    )

    private data class MakeCurrentCall(
        val display: EGLDisplay,
        val surface: EGLSurface,
        val context: EGLContext,
    )

    private data class ContextCall(val display: EGLDisplay, val context: EGLContext)

    private data class SurfaceCall(val display: EGLDisplay, val surface: EGLSurface)

    private class RecordingEglPlatform : EglPlatform {
        val ownedDisplay: EGLDisplay = mockk()
        val config: EGLConfig = mockk()
        val context: EGLContext = mockk()
        val pbuffer: EGLSurface = mockk()
        val calls = mutableListOf<String>()
        val chooseConfigAttributes = mutableListOf<IntArray>()
        val contextAttributes = mutableListOf<IntArray>()
        val pbufferAttributes = mutableListOf<IntArray>()
        val makeCurrentCalls = mutableListOf<MakeCurrentCall>()
        val createContextCalls = mutableListOf<ContextCall>()
        val destroyContextCalls = mutableListOf<ContextCall>()
        val destroySurfaceCalls = mutableListOf<SurfaceCall>()
        var releaseThreadCount = 0
        var selectedConfig: EGLConfig? = config
        var selectedConfigCount = 1
        var createdContext: EGLContext = context
        var createdPbuffer: EGLSurface = pbuffer
        var createContextFailure: Throwable? = null
        var createPbufferFailure: Throwable? = null
        var bindResult = true
        var unbindResult = true
        var bindFailure: Throwable? = null
        var unbindFailure: Throwable? = null
        var destroyContextResult = true
        var destroySurfaceResult = true
        var releaseThreadResult = true
        var destroyContextFailure: Throwable? = null
        var destroySurfaceFailure: Throwable? = null
        var releaseThreadFailure: Throwable? = null
        var bindCurrentDisplayOverride: EGLDisplay? = null
        var bindCurrentContextOverride: EGLContext? = null
        var bindCurrentReadSurfaceOverride: EGLSurface? = null
        var bindCurrentDrawSurfaceOverride: EGLSurface? = null
        var unbindCurrentContextOverride: EGLContext? = null
        private val errors = ArrayDeque<Any>()
        private var currentDisplayValue: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var currentContextValue: EGLContext = EGL14.EGL_NO_CONTEXT
        private var currentReadSurfaceValue: EGLSurface = EGL14.EGL_NO_SURFACE
        private var currentDrawSurfaceValue: EGLSurface = EGL14.EGL_NO_SURFACE

        override val currentDisplay: EGLDisplay
            get() {
                calls += "currentDisplay"
                return currentDisplayValue
            }

        override val currentContext: EGLContext
            get() {
                calls += "currentContext"
                return currentContextValue
            }

        override val currentReadSurface: EGLSurface
            get() {
                calls += "currentReadSurface"
                return currentReadSurfaceValue
            }

        override val currentDrawSurface: EGLSurface
            get() {
                calls += "currentDrawSurface"
                return currentDrawSurfaceValue
            }

        fun enqueueError(value: Int) {
            errors.addLast(value)
        }

        override fun getDisplay(): EGLDisplay {
            calls += "getDisplay"
            return ownedDisplay
        }

        override fun initialize(display: EGLDisplay, version: IntArray): Boolean {
            calls += "initialize"
            assertSame(this.ownedDisplay, display)
            version[0] = 1
            version[1] = 5
            return true
        }

        override fun chooseConfig(
            display: EGLDisplay,
            attributes: IntArray,
            configs: Array<EGLConfig?>,
            count: IntArray,
        ): Boolean {
            calls += "chooseConfig"
            assertSame(this.ownedDisplay, display)
            chooseConfigAttributes += attributes.copyOf()
            configs[0] = selectedConfig
            count[0] = selectedConfigCount
            return true
        }

        override fun createContext(display: EGLDisplay, config: EGLConfig, attributes: IntArray): EGLContext {
            calls += "createContext"
            createContextCalls += ContextCall(display, context)
            assertSame(this.ownedDisplay, display)
            assertSame(this.config, config)
            contextAttributes += attributes.copyOf()
            createContextFailure?.let { throw it }
            return createdContext
        }

        override fun createPbufferSurface(display: EGLDisplay, config: EGLConfig, attributes: IntArray): EGLSurface {
            calls += "createPbufferSurface"
            assertSame(this.ownedDisplay, display)
            assertSame(this.config, config)
            pbufferAttributes += attributes.copyOf()
            createPbufferFailure?.let { throw it }
            return createdPbuffer
        }

        override fun makeCurrent(display: EGLDisplay, surface: EGLSurface, context: EGLContext): Boolean {
            val unbind = context === EGL14.EGL_NO_CONTEXT
            calls += if (unbind) "makeCurrent:unbind" else "makeCurrent:bind"
            makeCurrentCalls += MakeCurrentCall(display, surface, context)
            if (unbind) {
                unbindFailure?.let { throw it }
                if (unbindResult) {
                    currentDisplayValue = EGL14.EGL_NO_DISPLAY
                    currentContextValue = unbindCurrentContextOverride ?: EGL14.EGL_NO_CONTEXT
                    currentReadSurfaceValue = EGL14.EGL_NO_SURFACE
                    currentDrawSurfaceValue = EGL14.EGL_NO_SURFACE
                }
                return unbindResult
            }
            bindFailure?.let { throw it }
            if (bindResult) {
                currentDisplayValue = bindCurrentDisplayOverride ?: display
                currentContextValue = bindCurrentContextOverride ?: context
                currentReadSurfaceValue = bindCurrentReadSurfaceOverride ?: surface
                currentDrawSurfaceValue = bindCurrentDrawSurfaceOverride ?: surface
            }
            return bindResult
        }

        override fun destroyContext(display: EGLDisplay, context: EGLContext): Boolean {
            calls += "destroyContext"
            destroyContextCalls += ContextCall(display, context)
            destroyContextFailure?.let { throw it }
            return destroyContextResult
        }

        override fun destroySurface(display: EGLDisplay, surface: EGLSurface): Boolean {
            calls += "destroySurface"
            destroySurfaceCalls += SurfaceCall(display, surface)
            destroySurfaceFailure?.let { throw it }
            return destroySurfaceResult
        }

        override fun releaseThread(): Boolean {
            calls += "releaseThread"
            releaseThreadCount += 1
            releaseThreadFailure?.let { throw it }
            return releaseThreadResult
        }

        override fun getError(): Int {
            calls += "getError"
            val result = if (errors.isEmpty()) EGL14.EGL_SUCCESS else errors.removeFirst()
            if (result is Throwable) throw result
            return result as Int
        }
    }

    private class RecordingGlesPlatform(
        private val maxTextureSize: Int,
        private val maxViewportWidth: Int,
        private val maxViewportHeight: Int,
        highFloatRange: IntArray,
        private val highFloatPrecision: Int,
    ) : GlesPlatform {
        val calls = mutableListOf<String>()
        private val highFloatRange = highFloatRange.copyOf()
        private val errors = ArrayDeque<Any>()

        fun enqueueError(value: Any) {
            errors.addLast(value)
        }

        fun clearCalls() {
            calls.clear()
        }

        override fun getError(): Int {
            calls += "getError"
            val result = if (errors.isEmpty()) GLES20.GL_NO_ERROR else errors.removeFirst()
            if (result is Throwable) throw result
            return result as Int
        }

        override fun getInteger(name: Int, values: IntArray) {
            calls += "getInteger:$name"
            when (name) {
                GLES20.GL_MAX_TEXTURE_SIZE -> values[0] = maxTextureSize
                GLES20.GL_MAX_VIEWPORT_DIMS -> {
                    values[0] = maxViewportWidth
                    values[1] = maxViewportHeight
                }

                else -> unexpected("getInteger($name)")
            }
        }

        override fun getShaderPrecisionFormat(range: IntArray, precision: IntArray) {
            calls += "getShaderPrecisionFormat"
            range[0] = highFloatRange[0]
            range[1] = highFloatRange[1]
            precision[0] = highFloatPrecision
        }

        override fun genTextures(names: IntArray): Unit = unexpected("genTextures")
        override fun bindTexture(target: Int, texture: Int): Unit = unexpected("bindTexture")
        override fun texParameter(target: Int, name: Int, value: Int): Unit = unexpected("texParameter")
        override fun texImage2D(width: Int, height: Int): Unit = unexpected("texImage2D")
        override fun deleteTextures(names: IntArray): Unit = unexpected("deleteTextures")
        override fun genFramebuffers(names: IntArray): Unit = unexpected("genFramebuffers")
        override fun bindFramebuffer(framebuffer: Int): Unit = unexpected("bindFramebuffer")
        override fun framebufferTexture2D(texture: Int): Unit = unexpected("framebufferTexture2D")
        override fun checkFramebufferStatus(): Int = unexpected("checkFramebufferStatus")
        override fun deleteFramebuffers(names: IntArray): Unit = unexpected("deleteFramebuffers")
        override fun createShader(type: Int): Int = unexpected("createShader")
        override fun shaderSource(shader: Int, source: String): Unit = unexpected("shaderSource")
        override fun compileShader(shader: Int): Unit = unexpected("compileShader")
        override fun getShaderStatus(shader: Int, status: IntArray): Unit = unexpected("getShaderStatus")
        override fun deleteShader(shader: Int): Unit = unexpected("deleteShader")
        override fun createProgram(): Int = unexpected("createProgram")
        override fun attachShader(program: Int, shader: Int): Unit = unexpected("attachShader")
        override fun bindAttribLocation(program: Int, index: Int, name: String): Unit = unexpected("bindAttribLocation")
        override fun linkProgram(program: Int): Unit = unexpected("linkProgram")
        override fun getProgramStatus(program: Int, status: IntArray): Unit = unexpected("getProgramStatus")
        override fun getUniformLocation(program: Int, name: String): Int = unexpected("getUniformLocation")
        override fun detachShader(program: Int, shader: Int): Unit = unexpected("detachShader")
        override fun deleteProgram(program: Int): Unit = unexpected("deleteProgram")
        override fun useProgram(program: Int): Unit = unexpected("useProgram")
        override fun viewport(width: Int, height: Int): Unit = unexpected("viewport")
        override fun activeTexture(texture: Int): Unit = unexpected("activeTexture")
        override fun uniform1i(location: Int, value: Int): Unit = unexpected("uniform1i")
        override fun uniform1f(location: Int, value: Float): Unit = unexpected("uniform1f")
        override fun uniformMatrix4fv(location: Int, values: FloatArray): Unit = unexpected("uniformMatrix4fv")
        override fun vertexAttribPointer(index: Int, values: Buffer): Unit = unexpected("vertexAttribPointer")
        override fun enableVertexAttribArray(index: Int): Unit = unexpected("enableVertexAttribArray")
        override fun colorMask(): Unit = unexpected("colorMask")
        override fun packAlignmentOne(): Unit = unexpected("packAlignmentOne")
        override fun disable(capability: Int): Unit = unexpected("disable")
        override fun drawTriangleStrip(): Unit = unexpected("drawTriangleStrip")
        override fun readPixels(width: Int, height: Int, carrier: Buffer): Unit = unexpected("readPixels")

        private fun unexpected(operation: String): Nothing =
            throw AssertionError("Unexpected GLES call: $operation")
    }
}
