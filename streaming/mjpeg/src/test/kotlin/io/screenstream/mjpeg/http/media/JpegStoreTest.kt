package io.screenstream.mjpeg.http.media

import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModule.CaptureAttemptId
import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.http.media.JpegStore.ImageKind
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

internal class JpegStoreTest {
    private fun attempt(): CaptureAttemptId = CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))

    // Cases: MJPEG-IMG-01
    @Test
    fun logoIsTheInitialFallbackForBothPoliciesAndInstallationIsOneTimeBeforeCapture() {
        withStore { store ->
            val logo = byteArrayOf(10, 11, 12)
            store.installLogo(logo)
            for (policy in PostStopImage.entries) {
                store.updateFallbackPolicy(policy)
                assertEquals(ImageKind.Logo, store.snapshot().imageKind)
                assertSelected(store, logo)
            }
            assertThrows(IllegalStateException::class.java) { store.installLogo(byteArrayOf(99)) }
            assertSelected(store, logo)
            store.dispose()
            assertThrows(IllegalStateException::class.java) { store.installLogo(logo) }
            assertEquals(ImageKind.None, store.snapshot().imageKind)
        }
        withStore { store ->
            store.registerCapture(attempt(), Any())
            assertThrows(IllegalStateException::class.java) { store.installLogo(byteArrayOf(1)) }
        }
    }

    // Cases: MJPEG-IMG-02, MJPEG-IMG-03
    @Test
    fun phaseGenerationPairsSelectLiveOrRetainedHistoryWithoutErasingIt() {
        for (status in Status.entries) withStore { store ->
            val id = attempt()
            val generation = Any()
            val logo = byteArrayOf(10)
            val live = byteArrayOf(1, 2, 3)
            store.installLogo(logo)
            assertTrue(store.registerCapture(id, generation))
            assertEquals(Status.Starting, store.snapshot().captureStatus)
            assertSame(generation, store.snapshot().captureGeneration)
            assertTrue(store.offerJpeg(generation, live.size) { live.copyInto(it) })
            assertFalse(store.offerJpeg(generation, live.size) { live.copyInto(it) })
            val current = when (status) {
                Status.Suspended, Status.Failed, Status.Stopping, Status.Stopped -> null
                else -> generation
            }
            store.updateCaptureStatus(id, status, current)
            assertEquals(status, store.snapshot().captureStatus)
            assertSame(current, store.snapshot().captureGeneration)
            for (policy in PostStopImage.entries) {
                store.updateFallbackPolicy(policy)
                val realSelected = current != null || policy == PostStopImage.LastAvailableFrame
                assertSelected(store, if (realSelected) live else logo)
                assertEquals(
                    if (current != null) ImageKind.Live else if (realSelected) ImageKind.Retained else ImageKind.Logo,
                    store.snapshot().imageKind,
                )
            }
        }
    }

    // Cases: MJPEG-IMG-02, MJPEG-IMG-03, MJPEG-IMG-04
    @Test
    fun replacementAttemptKeepsRetainedImageAndRejectsOldStatusAndProducer() = withStore { store ->
        val old = attempt()
        val next = attempt()
        val oldGeneration = Any()
        val nextGeneration = Any()
        store.installLogo(byteArrayOf(10))
        store.updateFallbackPolicy(PostStopImage.LastAvailableFrame)
        store.registerCapture(old, oldGeneration)
        publish(store, oldGeneration, byteArrayOf(1, 2))
        store.registerCapture(next, nextGeneration)
        val before = store.snapshot()
        store.updateCaptureStatus(old, Status.Failed, null)
        assertEquals(before, store.snapshot())
        assertEquals(ImageKind.Retained, before.imageKind)
        assertFalse(store.offerJpeg(oldGeneration, 2) { throw AssertionError("Stale copier entered") })
        assertSelected(store, byteArrayOf(1, 2))
        publish(store, nextGeneration, byteArrayOf(3, 4, 5))
        assertEquals(ImageKind.Live, store.snapshot().imageKind)
        store.dispose()
        assertFalse(store.registerCapture(attempt(), Any()))
        store.updateCaptureStatus(next, Status.Active, nextGeneration)
        store.updateFallbackPolicy(PostStopImage.LastAvailableFrame)
        assertEquals(ImageKind.None, store.snapshot().imageKind)
        assertNull(store.snapshot().captureGeneration)
        assertNull(store.openReader())
        assertFalse(store.offerJpeg(nextGeneration, 1) { throw AssertionError("Disposed copier entered") })
    }

    // Cases: MJPEG-IMG-04, MJPEG-IMG-05
    @Test
    fun invalidAndFailedCopiesPreserveImageAndVersionAndReturnReusableStorage() = withStore { store ->
        val generation = Any()
        store.registerCapture(attempt(), generation)
        assertFalse(store.offerJpeg(Any(), 0) { throw AssertionError("Stale copier entered") })
        publish(store, generation, byteArrayOf(1, 2, 3, 4))
        var reserved: ByteArray? = null
        val failure = IllegalStateException("Fixture copy failure")
        withFrame(store) { _, held ->
            val version = held.publicationVersion
            for (size in listOf(0, -1)) {
                assertThrows(IllegalArgumentException::class.java) {
                    store.offerJpeg(generation, size) { throw AssertionError("Invalid copier entered") }
                }
            }
            var copies = 0
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                store.offerJpeg(generation, 4) {
                    copies++
                    reserved = it
                    it[0] = 99
                    throw failure
                }
            })
            assertEquals(1, copies)
            assertPayload(byteArrayOf(1, 2, 3, 4), held)
            withFrame(store) { _, selected -> assertEquals(version, selected.publicationVersion) }
            store.offerJpeg(generation, 3) {
                assertSame(reserved, it)
                byteArrayOf(4, 5, 6).copyInto(it)
            }
            withFrame(store) { _, selected -> assertEquals(version + 1, selected.publicationVersion) }
        }
        assertSelected(store, byteArrayOf(4, 5, 6))
    }

    // Cases: MJPEG-IMG-05
    @Test
    fun heldCopyRunsOutsideMonitorAndCannotPublishAfterGenerationReplacementOrDisposal() {
        for (cutoff in listOf("new-attempt", "suspend-resume", "stop", "dispose")) {
            withStore { store ->
                val id = attempt()
                val generation = Any()
                val successor = Any()
                store.registerCapture(id, generation)
                store.updateFallbackPolicy(PostStopImage.LastAvailableFrame)
                publish(store, generation, byteArrayOf(1, 2, 3))
                withFrame(store) { _, held ->
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val executor = Executors.newFixedThreadPool(2)
                    try {
                        val publication = executor.submit<Boolean> {
                            store.offerJpeg(generation, 3) {
                                entered.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                                byteArrayOf(99, 98, 97).copyInto(it)
                            }
                        }
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        executor.submit {
                            assertSelected(store, byteArrayOf(1, 2, 3))
                            when (cutoff) {
                                "new-attempt" -> store.registerCapture(attempt(), successor)
                                "suspend-resume" -> {
                                    store.updateCaptureStatus(id, Status.Suspended, null)
                                    store.updateCaptureStatus(id, Status.Active, successor)
                                }

                                "stop" -> store.updateCaptureStatus(id, Status.Stopping, null)
                                else -> store.dispose()
                            }
                        }.get(5, TimeUnit.SECONDS)
                        release.countDown()
                        assertFalse(publication.get(5, TimeUnit.SECONDS))
                        assertPayload(byteArrayOf(1, 2, 3), held)
                        if (cutoff == "dispose") assertNull(store.openReader()) else {
                            assertSelected(store, byteArrayOf(1, 2, 3))
                            withFrame(store) { _, selected -> assertEquals(held.publicationVersion, selected.publicationVersion) }
                        }
                        if (cutoff == "new-attempt" || cutoff == "suspend-resume") {
                            publish(store, successor, byteArrayOf(4, 5))
                            withFrame(store) { _, selected -> assertEquals(held.publicationVersion + 1, selected.publicationVersion) }
                        }
                    } finally {
                        release.countDown()
                        executor.shutdownNow()
                        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                    }
                }
            }
        }
    }

    // Cases: MJPEG-IMG-06, MJPEG-IMG-07
    @Test
    fun admissionPinAndConcurrentLeasesSurviveReplacementCloseAndDisposal() = withStore { store ->
        val generation = Any()
        store.registerCapture(attempt(), generation)
        val original = byteArrayOf(1, 2, 3, 4, 5, 6)
        publish(store, generation, original)
        val first = checkNotNull(store.openReader())
        val second = checkNotNull(store.openReader())
        publish(store, generation, byteArrayOf(7, 8, 9))
        val leaseA = checkNotNull(first.acquireFrame())
        val leaseB = checkNotNull(second.acquireFrame())
        try {
            assertEquals(original.size, first.initialByteCount)
            assertPayload(original, leaseA)
            assertPayload(original, leaseB)
            first.close()
            publish(store, generation, byteArrayOf(10, 11, 12))
            val latest = checkNotNull(second.acquireFrame())
            try {
                assertPayload(byteArrayOf(10, 11, 12), latest)
            } finally {
                second.releaseFrame(latest)
            }
            store.dispose()
            assertPayload(original, leaseA)
            assertPayload(original, leaseB)
            assertNull(first.acquireFrame())
            assertNull(second.acquireFrame())
        } finally {
            first.releaseFrame(leaseA)
            second.releaseFrame(leaseB)
            first.close()
            second.close()
        }
    }

    // Cases: MJPEG-IMG-06, MJPEG-IMG-07
    @Test
    fun unclaimedInitialPinClosesAndReusedCapacityNeverChangesValidPrefix() = withStore { store ->
        val generation = Any()
        store.registerCapture(attempt(), generation)
        publish(store, generation, ByteArray(6) { 1 })
        val reader = checkNotNull(store.openReader())
        publish(store, generation, ByteArray(6) { 2 })
        reader.close()
        reader.close()
        assertEquals(6, reader.initialByteCount)
        assertNull(reader.acquireFrame())
        publish(store, generation, byteArrayOf(3, 4, 5))
        assertSelected(store, byteArrayOf(3, 4, 5))
        publish(store, generation, byteArrayOf(6))
        assertSelected(store, byteArrayOf(6))
    }

    // Cases: MJPEG-IMG-04, MJPEG-IMG-05, MJPEG-IMG-07
    @Test
    fun spareReuseHonorsBothSizeBoundsAndInvalidOffersReturnReservedStorage() {
        for (size in listOf(3, 4, 8, 9)) withStore { store ->
            val generation = Any()
            store.registerCapture(attempt(), generation)
            var original: ByteArray? = null
            store.offerJpeg(generation, 8) { original = it; it.fill(1) }
            publish(store, generation, byteArrayOf(2))
            store.offerJpeg(generation, size) {
                if (size == 4 || size == 8) assertSame(original, it) else {
                    assertNotSame(original, it)
                    assertEquals(size, it.size)
                }
                it.fill(3, toIndex = size)
            }
            assertSelected(store, ByteArray(size) { 3 })
        }
        for (invalid in listOf(0, -1)) withStore { store ->
            val generation = Any()
            store.registerCapture(attempt(), generation)
            var original: ByteArray? = null
            store.offerJpeg(generation, 8) { original = it; it.fill(1) }
            publish(store, generation, byteArrayOf(2))
            assertThrows(IllegalArgumentException::class.java) {
                store.offerJpeg(generation, invalid) { throw AssertionError("Invalid copier entered") }
            }
            store.offerJpeg(generation, 4) {
                assertSame(original, it)
                byteArrayOf(3, 4, 5, 6).copyInto(it)
            }
            assertSelected(store, byteArrayOf(3, 4, 5, 6))
        }
    }

    // Cases: MJPEG-IMG-08
    @Test
    fun publicationOrCutoffAtEachReaderWaitBoundaryCannotLoseWakeup() = runTest(timeout = 5.seconds) {
        for (change in listOf("publish", "close", "dispose")) {
            for (point in listOf("before-revision", "before-acquire", "before-wait", "waiting")) {
                val store = JpegStore()
                val id = attempt()
                val generation = Any()
                store.registerCapture(id, generation)
                publish(store, generation, byteArrayOf(1))
                val reader = checkNotNull(store.openReader())
                val initial = checkNotNull(reader.acquireFrame())
                reader.releaseFrame(initial)
                store.updateCaptureStatus(id, Status.Suspended, null)
                val waiting = CompletableDeferred<Unit>()
                fun changeSelection() {
                    when (change) {
                        "publish" -> {
                            val successor = Any()
                            store.updateCaptureStatus(id, Status.Active, successor)
                            publish(store, successor, byteArrayOf(7, 8))
                        }

                        "close" -> reader.close()
                        else -> store.dispose()
                    }
                }
                try {
                    if (point == "before-revision") changeSelection()
                    val result = async(start = CoroutineStart.UNDISPATCHED) {
                        val revision = reader.changeRevision.value
                        if (point == "before-acquire") changeSelection()
                        var frame = reader.acquireFrame()
                        if (frame == null && !reader.isClosed) {
                            if (point == "before-wait") changeSelection()
                            waiting.complete(Unit)
                            reader.changeRevision.first { it != revision }
                            frame = reader.acquireFrame()
                        }
                        frame?.let { acquired ->
                            try {
                                acquired.bytes.copyOf(acquired.byteCount)
                            } finally {
                                reader.releaseFrame(acquired)
                            }
                        }
                    }
                    if (point == "waiting") {
                        waiting.await()
                        changeSelection()
                    }
                    if (change == "publish") assertArrayEquals(byteArrayOf(7, 8), result.await()) else assertNull(result.await())
                } finally {
                    reader.close()
                    store.dispose()
                }
            }
        }
    }

    private fun publish(store: JpegStore, generation: Any, bytes: ByteArray) {
        var copies = 0
        store.offerJpeg(generation, bytes.size) {
            copies++
            bytes.copyInto(it)
        }
        assertEquals(1, copies)
    }

    private fun assertSelected(store: JpegStore, expected: ByteArray) {
        withFrame(store) { _, frame -> assertPayload(expected, frame) }
    }

    private fun assertPayload(expected: ByteArray, frame: JpegStore.Frame) {
        assertEquals(expected.size, frame.byteCount)
        assertArrayEquals(expected, frame.bytes.copyOf(frame.byteCount))
    }

    private inline fun withStore(block: (JpegStore) -> Unit) {
        val store = JpegStore()
        try {
            block(store)
        } finally {
            store.dispose()
        }
    }

    private inline fun withFrame(store: JpegStore, block: (JpegStore.Reader, JpegStore.Frame) -> Unit) {
        val reader = checkNotNull(store.openReader())
        try {
            val frame = checkNotNull(reader.acquireFrame())
            try {
                block(reader, frame)
            } finally {
                reader.releaseFrame(frame)
            }
        } finally {
            reader.close()
        }
    }
}
