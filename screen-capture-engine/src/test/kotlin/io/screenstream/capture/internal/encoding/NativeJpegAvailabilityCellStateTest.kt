package io.screenstream.capture.internal.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class NativeJpegAvailabilityCellStateTest {
    // Verification: ENC-02
    @Test
    fun successfulInitialLoadPublishesAvailableOnce() {
        val loadCalls = AtomicInteger()
        val cell = NativeJpegAvailabilityCell(initialLoad = { loadCalls.incrementAndGet() })

        repeat(2) {
            assertSame(NativeJpegProcess.Availability.Available, cell.resolve())
        }
        assertEquals(1, loadCalls.get())
    }

    // Verification: ENC-02
    @Test
    fun exactUnsatisfiedLinkErrorPublishesCleanUnavailableOnce() {
        val loadCalls = AtomicInteger()
        val cell = NativeJpegAvailabilityCell(
            initialLoad = {
                loadCalls.incrementAndGet()
                throw UnsatisfiedLinkError("missing optional DSO")
            },
        )

        repeat(2) {
            assertSame(NativeJpegProcess.Availability.CleanUnavailable, cell.resolve())
        }
        assertEquals(1, loadCalls.get())
    }

    // Verification: ENC-02
    @Test
    fun concurrentCallersShareOneInitialLoad() {
        val callerCount = 8
        val callersReady = CountDownLatch(callerCount)
        val startCallers = CountDownLatch(1)
        val loadEntered = CountDownLatch(1)
        val allowLoadFailure = CountDownLatch(1)
        val loadCalls = AtomicInteger()
        val cell = NativeJpegAvailabilityCell(
            initialLoad = {
                loadCalls.incrementAndGet()
                loadEntered.countDown()
                check(allowLoadFailure.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                throw UnsatisfiedLinkError("missing optional DSO")
            },
        )
        val executor = Executors.newFixedThreadPool(callerCount)

        try {
            val results = List(callerCount) {
                executor.submit<NativeJpegProcess.Availability> {
                    callersReady.countDown()
                    check(startCallers.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    cell.resolve()
                }
            }
            assertTrue(callersReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            startCallers.countDown()
            assertTrue(loadEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            allowLoadFailure.countDown()

            results.forEach { future ->
                assertSame(
                    NativeJpegProcess.Availability.CleanUnavailable,
                    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
            }
            assertEquals(1, loadCalls.get())
        } finally {
            allowLoadFailure.countDown()
            executor.shutdownNow()
        }
    }

    // Verification: ENC-02
    @Test
    fun securityExceptionPublishesCleanUnavailableOnce() {
        val loadCalls = AtomicInteger()
        val cell = NativeJpegAvailabilityCell(
            initialLoad = {
                loadCalls.incrementAndGet()
                throw SecurityException("load denied")
            },
        )

        repeat(2) {
            assertSame(NativeJpegProcess.Availability.CleanUnavailable, cell.resolve())
        }
        assertEquals(1, loadCalls.get())
    }

    // Verification: ENC-02
    @Test
    fun ordinaryExceptionPublishesPoisonedOnce() {
        val loadCalls = AtomicInteger()
        val cell = NativeJpegAvailabilityCell(
            initialLoad = {
                loadCalls.incrementAndGet()
                throw IllegalStateException("loader failed")
            },
        )

        repeat(2) {
            assertSame(NativeJpegProcess.Availability.Poisoned, cell.resolve())
        }
        assertEquals(1, loadCalls.get())
    }

    // Verification: ENC-02
    @Test
    fun unsatisfiedLinkErrorSubclassPropagatesWithoutPublicationAndNextCallRetries() {
        val loadCalls = AtomicInteger()
        val failure = DerivedUnsatisfiedLinkError()
        val cell = NativeJpegAvailabilityCell(
            initialLoad = {
                if (loadCalls.incrementAndGet() == 1) throw failure
                throw UnsatisfiedLinkError("missing optional DSO")
            },
        )

        assertSame(failure, assertThrows(DerivedUnsatisfiedLinkError::class.java) { cell.resolve() })
        assertSame(NativeJpegProcess.Availability.CleanUnavailable, cell.resolve())
        assertEquals(2, loadCalls.get())
    }

    // Verification: ENC-02
    @Test
    fun siblingLinkageErrorPropagatesWithoutPublicationAndNextCallRetries() {
        val loadCalls = AtomicInteger()
        val failure = NoClassDefFoundError("initial load dependency")
        val cell = NativeJpegAvailabilityCell(
            initialLoad = {
                if (loadCalls.incrementAndGet() == 1) throw failure
                throw UnsatisfiedLinkError("missing optional DSO")
            },
        )

        assertSame(failure, assertThrows(NoClassDefFoundError::class.java) { cell.resolve() })
        assertSame(NativeJpegProcess.Availability.CleanUnavailable, cell.resolve())
        assertEquals(2, loadCalls.get())
    }

    // Verification: ENC-02
    @Test
    fun nonLinkageErrorPropagatesWithoutPublicationAndNextCallRetries() {
        val loadCalls = AtomicInteger()
        val failure = AssertionError("initial load failed")
        val cell = NativeJpegAvailabilityCell(
            initialLoad = {
                if (loadCalls.incrementAndGet() == 1) throw failure
                throw UnsatisfiedLinkError("missing optional DSO")
            },
        )

        assertSame(failure, assertThrows(AssertionError::class.java) { cell.resolve() })
        assertSame(NativeJpegProcess.Availability.CleanUnavailable, cell.resolve())
        assertEquals(2, loadCalls.get())
    }

    // Verification: ENC-02
    @Test
    fun availabilityGuardDoesNotLoadAndAcceptsPublishedSuccess() {
        val loadCalls = AtomicInteger()
        val cell = NativeJpegAvailabilityCell(initialLoad = { loadCalls.incrementAndGet() })

        assertThrows(IllegalStateException::class.java) { cell.requireAvailable() }
        assertEquals(0, loadCalls.get())
        assertSame(NativeJpegProcess.Availability.Available, cell.resolve())
        cell.requireAvailable()
        assertEquals(1, loadCalls.get())
    }

    private companion object {
        private const val TIMEOUT_SECONDS: Long = 5L
    }

    private class DerivedUnsatisfiedLinkError : UnsatisfiedLinkError("derived load failure")
}
