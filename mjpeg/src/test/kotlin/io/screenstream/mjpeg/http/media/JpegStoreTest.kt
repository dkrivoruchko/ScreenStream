package io.screenstream.mjpeg.http.media

import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModule.CaptureAttemptId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val instance = StreamingModule.InstanceId(StreamingModule.Id("MJPEG"))

    // Cases: MJPEG-IMG-01
    @Test
    fun installedLogoBecomesAvailableOnlyWhenStoppedPolicySelectsIt() = withStore { store ->
        val logo = byteArrayOf(10, 11, 12)
        store.installLogo(logo)
        assertFalse(store.snapshot().hasImage)
        assertNull(store.openReader())
        assertThrows(IllegalStateException::class.java) { store.installLogo(byteArrayOf(99)) }

        store.updateStoppedImagePolicy(PostStopImage.LastAvailableFrame)
        assertNull(store.openReader())
        store.updateStoppedImagePolicy(PostStopImage.Placeholder)
        assertSelected(store, logo)
        withFrame(store) { reader, frame -> assertFalse(reader.isCurrentCapture(frame)) }
        store.updateStoppedImagePolicy(PostStopImage.LastAvailableFrame)
        assertSelected(store, logo)
        store.updateStoppedImagePolicy(PostStopImage.None)
        assertNull(store.openReader())
        store.updateStoppedImagePolicy(PostStopImage.LastAvailableFrame)
        assertNull(store.openReader())
    }

    // Cases: MJPEG-IMG-02, MJPEG-IMG-03
    @Test
    fun publishingJpegReportsOnlyAvailabilityChanges() = withStore { store ->
        val id = attempt()
        val first = byteArrayOf(1, 2)
        val replacement = byteArrayOf(3, 4, 5)
        val logo = byteArrayOf(10)
        store.installLogo(logo)
        activate(store, id)
        assertFalse(store.snapshot().hasImage)

        assertTrue("First live image must announce availability", store.offerJpeg(id, first.size) { first.copyInto(it) })
        assertSelected(store, first)
        assertFalse(
            "Replacing an available image must not announce new availability",
            store.offerJpeg(id, replacement.size) { replacement.copyInto(it) },
        )
        assertSelected(store, replacement)
        assertEquals(Status.Active, store.snapshot().capture)

        assertTrue(store.updateCaptureStatus(id, Status.Stopped))
        assertSelected(store, logo)
        assertEquals(Status.Stopped, store.snapshot().capture)
        assertTrue(store.updateCaptureStatus(id, Status.Active))
        assertFalse("Replacing the selected logo must preserve availability", store.offerJpeg(id, first.size) { first.copyInto(it) })
        assertSelected(store, first)
        withFrame(store) { reader, frame -> assertTrue(reader.isCurrentCapture(frame)) }

        assertTrue(store.updateCaptureStatus(id, Status.Suspended))
        assertFalse(store.snapshot().hasImage)
        assertNull(store.openReader())
        assertTrue(store.updateCaptureStatus(id, Status.Active))
        assertTrue(
            "First image after suspension must announce restored availability",
            store.offerJpeg(id, replacement.size) { replacement.copyInto(it) },
        )
        assertSelected(store, replacement)
        assertEquals(Status.Active, store.snapshot().capture)
    }

    // Cases: MJPEG-IMG-02
    @Test
    fun replacementAttemptRetainsSelectionAndRejectsOldStatusAndProducer() = withStore { store ->
        val oldAttempt = attempt()
        val currentAttempt = attempt()
        val image = byteArrayOf(1, 2, 3)
        store.installLogo(byteArrayOf(10))
        activate(store, oldAttempt)
        offer(store, oldAttempt, image)

        assertTrue(store.registerCapture(currentAttempt))
        assertEquals(Status.Starting, store.snapshot().capture)
        assertSelected(store, image)
        assertFalse(store.updateCaptureStatus(oldAttempt, Status.Failed))
        assertFalse(store.offerJpeg(oldAttempt, image.size) { throw AssertionError("Stale copier entered") })
        assertThrows(IllegalStateException::class.java) { store.installLogo(byteArrayOf(20)) }
        for (status in listOf(Status.WaitingForPermission, Status.Starting, Status.Reconfiguring, Status.Active)) {
            assertTrue(store.updateCaptureStatus(currentAttempt, status))
            assertEquals(status, store.snapshot().capture)
            assertSelected(store, image)
            withFrame(store) { reader, frame -> assertFalse(reader.isCurrentCapture(frame)) }
        }
        val replacement = byteArrayOf(4, 5)
        offer(store, currentAttempt, replacement)
        assertSelected(store, replacement)
        withFrame(store) { reader, frame -> assertTrue(reader.isCurrentCapture(frame)) }
    }

    // Cases: MJPEG-IMG-03
    @Test
    fun livePolicyEditsPreserveImageAndStoppingAppliesLatestPolicy() {
        for (policy in listOf(PostStopImage.None, PostStopImage.Placeholder, PostStopImage.LastAvailableFrame)) {
            withStore { store ->
                val id = attempt()
                val logo = byteArrayOf(10, 11)
                val live = byteArrayOf(1, 2, 3)
                store.installLogo(logo)
                activate(store, id)
                offer(store, id, live)

                store.updateStoppedImagePolicy(policy)
                assertSelected(store, live)
                assertTrue(store.updateCaptureStatus(id, Status.Stopping))
                when (policy) {
                    PostStopImage.None -> assertNull(store.openReader())
                    PostStopImage.Placeholder -> assertSelected(store, logo)
                    PostStopImage.LastAvailableFrame -> assertSelected(store, live)
                }
                store.updateStoppedImagePolicy(PostStopImage.Placeholder)
                assertSelected(store, logo)
                assertTrue(store.updateCaptureStatus(id, Status.Stopped))
                store.updateStoppedImagePolicy(PostStopImage.None)
                store.updateStoppedImagePolicy(PostStopImage.LastAvailableFrame)
                assertFalse(store.snapshot().hasImage)
                assertNull(store.openReader())
            }
        }
    }

    // Cases: MJPEG-IMG-02, MJPEG-IMG-03
    @Test
    fun suspensionAndFailureClearImageAndStoppedPolicyCannotRestoreIt() {
        for (status in listOf(Status.Suspended, Status.Failed)) {
            withStore { store ->
                val id = attempt()
                store.installLogo(byteArrayOf(10))
                activate(store, id)
                offer(store, id, byteArrayOf(1, 2))

                assertTrue(store.updateCaptureStatus(id, status))
                for (policy in listOf(PostStopImage.Placeholder, PostStopImage.LastAvailableFrame, PostStopImage.None)) {
                    store.updateStoppedImagePolicy(policy)
                    assertEquals(status, store.snapshot().capture)
                    assertFalse(store.snapshot().hasImage)
                    assertNull(store.openReader())
                }
                assertFalse(store.offerJpeg(id, 2) { throw AssertionError("Revoked copier entered") })
                assertTrue(store.updateCaptureStatus(id, Status.Active))
                offer(store, id, byteArrayOf(7, 8))
                assertSelected(store, byteArrayOf(7, 8))
            }
        }
    }

    // Cases: MJPEG-IMG-04
    @Test
    fun invalidAdmittedSizesCannotCopyOrReplaceExistingImage() = withStore { store ->
        val id = attempt()
        val image = byteArrayOf(1, 2, 3)
        activate(store, id)
        offer(store, id, image)
        withFrame(store) { _, held ->
            for (size in listOf(0, -1)) {
                assertThrows(IllegalArgumentException::class.java) {
                    store.offerJpeg(id, size) { throw AssertionError("Invalid-size copier entered") }
                }
                assertSelected(store, image)
                assertPayload(image, held)
            }
        }
    }

    // Cases: MJPEG-IMG-04
    @Test
    fun unavailableAndDisposedStoresRejectAdmissionBeforeInvokingCopier() = withStore { store ->
        val id = attempt()
        assertFalse(store.offerJpeg(id, 0) { throw AssertionError("Unregistered copier entered") })
        activate(store, id)
        offer(store, id, byteArrayOf(1))
        assertTrue(store.updateCaptureStatus(id, Status.Suspended))
        assertFalse(store.offerJpeg(id, 0) { throw AssertionError("Suspended copier entered") })

        store.dispose()
        store.dispose()
        assertFalse(store.registerCapture(attempt()))
        assertFalse(store.updateCaptureStatus(id, Status.Active))
        assertFalse(store.offerJpeg(id, 1) { throw AssertionError("Disposed copier entered") })
        store.updateStoppedImagePolicy(PostStopImage.Placeholder)
        assertFalse(store.snapshot().hasImage)
        assertNull(store.openReader())
    }

    // Cases: MJPEG-IMG-05
    @Test
    fun failingCopyRethrowsWithoutPublishingPartialBytes() = withStore { store ->
        val id = attempt()
        val image = byteArrayOf(1, 2, 3)
        activate(store, id)
        offer(store, id, image)
        val failure = IllegalStateException("Fixture copy failure")
        var copies = 0
        withFrame(store) { _, held ->
            val version = held.publicationVersion
            val thrown = assertThrows(IllegalStateException::class.java) {
                store.offerJpeg(id, 3) { destination ->
                    copies++
                    destination[0] = 99
                    throw failure
                }
            }
            assertSame(failure, thrown)
            assertEquals(1, copies)
            assertPayload(image, held)
            withFrame(store) { _, selected ->
                assertPayload(image, selected)
                assertEquals(version, selected.publicationVersion)
            }
        }
        offer(store, id, byteArrayOf(4, 5))
        assertSelected(store, byteArrayOf(4, 5))
    }

    // Cases: MJPEG-IMG-05
    @Test
    fun heldCopyIsOutsideMonitorAndCannotPublishAfterSourceRevocation() {
        for (cutoff in listOf("replacement", "suspend-resume", "stop", "dispose")) {
            withStore { store ->
                val id = attempt()
                val successor = attempt()
                val image = byteArrayOf(1, 2, 3)
                activate(store, id)
                store.updateStoppedImagePolicy(PostStopImage.LastAvailableFrame)
                offer(store, id, image)
                val reader = checkNotNull(store.openReader())
                val held = checkNotNull(reader.acquireFrame())
                val entered = CountDownLatch(1)
                val returnCopy = CountDownLatch(1)
                val workers = Executors.newFixedThreadPool(2)
                var copies = 0
                try {
                    val publication = workers.submit<Boolean> {
                        store.offerJpeg(id, 3) { destination ->
                            copies++
                            entered.countDown()
                            assertTrue("Copier return was not released", returnCopy.await(5, TimeUnit.SECONDS))
                            byteArrayOf(99, 98, 97).copyInto(destination)
                        }
                    }
                    assertTrue("Copier did not enter", entered.await(5, TimeUnit.SECONDS))
                    val cutoffCompleted = workers.submit {
                        assertSelected(store, image)
                        when (cutoff) {
                            "replacement" -> assertTrue(store.registerCapture(successor))
                            "suspend-resume" -> {
                                assertTrue(store.updateCaptureStatus(id, Status.Suspended))
                                assertTrue(store.updateCaptureStatus(id, Status.Active))
                            }

                            "stop" -> assertTrue(store.updateCaptureStatus(id, Status.Stopping))
                            "dispose" -> store.dispose()
                        }
                        assertPayload(image, held)
                    }
                    cutoffCompleted.get(5, TimeUnit.SECONDS)
                    returnCopy.countDown()
                    assertFalse(publication.get(5, TimeUnit.SECONDS))
                    assertEquals(1, copies)
                    assertPayload(image, held)
                    assertFalse(reader.isCurrentCapture(held))
                    if (cutoff == "replacement" || cutoff == "stop") {
                        withFrame(store) { _, selected ->
                            assertPayload(image, selected)
                            assertEquals(held.publicationVersion, selected.publicationVersion)
                        }
                    } else {
                        assertNull(store.openReader())
                    }
                    if (cutoff == "replacement" || cutoff == "suspend-resume") {
                        val nextId = if (cutoff == "replacement") successor else id
                        offer(store, nextId, byteArrayOf(4, 5))
                        assertSelected(store, byteArrayOf(4, 5))
                        withFrame(store) { _, selected -> assertEquals(held.publicationVersion + 1, selected.publicationVersion) }
                    }
                } finally {
                    returnCopy.countDown()
                    workers.shutdownNow()
                    try {
                        assertTrue("Copy workers did not terminate", workers.awaitTermination(5, TimeUnit.SECONDS))
                    } finally {
                        reader.releaseFrame(held)
                        reader.close()
                    }
                }
            }
        }
    }

    // Cases: MJPEG-IMG-06
    @Test
    fun acquiredImageSurvivesReplacementAndReaderClose() = withStore { store ->
        val id = attempt()
        val first = byteArrayOf(1, 2, 3)
        val latest = byteArrayOf(4, 5, 6, 7, 8)
        activate(store, id)
        offer(store, id, first)
        val reader = checkNotNull(store.openReader())
        try {
            offer(store, id, latest)
            assertEquals(first.size, reader.initialByteCount)
            val pinned = checkNotNull(reader.acquireFrame())
            try {
                assertPayload(first, pinned)
                reader.close()
                offer(store, id, byteArrayOf(9, 10, 11))
                assertSelected(store, byteArrayOf(9, 10, 11))
                assertEquals(first.size, reader.initialByteCount)
                assertPayload(first, pinned)
                store.dispose()
                assertEquals(first.size, reader.initialByteCount)
                assertPayload(first, pinned)
                assertNull(reader.acquireFrame())
                assertTrue(reader.isClosed)
            } finally {
                reader.releaseFrame(pinned)
            }
        } finally {
            reader.close()
        }
    }

    // Cases: MJPEG-IMG-06
    @Test
    fun closeBeforeInitialHandoffRevokesAdmission() = withStore { store ->
        val id = attempt()
        activate(store, id)
        offer(store, id, byteArrayOf(1, 2, 3))
        val reader = checkNotNull(store.openReader())
        try {
            offer(store, id, byteArrayOf(4, 5))
            reader.close()
            reader.close()
            assertEquals(3, reader.initialByteCount)
            assertNull(reader.acquireFrame())
            assertTrue(reader.isClosed)
            assertSelected(store, byteArrayOf(4, 5))
        } finally {
            reader.close()
        }
    }

    // Cases: MJPEG-IMG-07
    @Test
    fun smallerPublicationExposesItsValidPrefixAfterLargerImageHistory() = withStore { store ->
        val id = attempt()
        activate(store, id)
        offer(store, id, byteArrayOf(1, 2, 3, 4, 5, 6))
        offer(store, id, byteArrayOf(10, 11, 12, 13, 14, 15))

        store.offerJpeg(id, 3) { destination ->
            byteArrayOf(20, 21, 22).copyInto(destination)
        }

        assertSelected(store, byteArrayOf(20, 21, 22))
    }

    // Cases: MJPEG-IMG-07
    @Test
    fun multipleReadersKeepOldPayloadAndLaterAcquireLatestValidPrefix() = withStore { store ->
        val id = attempt()
        val first = byteArrayOf(1, 2, 3, 4, 5, 6)
        activate(store, id)
        offer(store, id, first)
        val firstReader = checkNotNull(store.openReader())
        val secondReader = checkNotNull(store.openReader())
        val firstLease = checkNotNull(firstReader.acquireFrame())
        val secondLease = checkNotNull(secondReader.acquireFrame())
        var firstReleased = false
        try {
            offer(store, id, byteArrayOf(10, 11, 12, 13, 14, 15))
            assertPayload(first, firstLease)
            firstReader.releaseFrame(firstLease)
            firstReleased = true
            firstReader.close()
            offer(store, id, byteArrayOf(20, 21, 22))
            assertPayload(first, secondLease)
            val latest = checkNotNull(secondReader.acquireFrame())
            try {
                assertPayload(byteArrayOf(20, 21, 22), latest)
                assertTrue(secondReader.isCurrentCapture(latest))
            } finally {
                secondReader.releaseFrame(latest)
            }
        } finally {
            if (!firstReleased) firstReader.releaseFrame(firstLease)
            secondReader.releaseFrame(secondLease)
            firstReader.close()
            secondReader.close()
        }
    }

    // Cases: MJPEG-IMG-07
    @Test
    fun stoppedRetainedImageAndLogoDoNotCountAsLiveCaptureRecovery() = withStore { store ->
        val id = attempt()
        val image = byteArrayOf(1, 2)
        val logo = byteArrayOf(10, 11, 12)
        store.installLogo(logo)
        activate(store, id)
        offer(store, id, image)
        store.updateStoppedImagePolicy(PostStopImage.LastAvailableFrame)
        withFrame(store) { reader, held ->
            assertTrue(reader.isCurrentCapture(held))
            assertTrue(store.updateCaptureStatus(id, Status.Stopped))
            assertPayload(image, held)
            assertFalse(reader.isCurrentCapture(held))
            assertSelected(store, image)
        }
        store.updateStoppedImagePolicy(PostStopImage.Placeholder)
        withFrame(store) { reader, frame ->
            assertPayload(logo, frame)
            assertFalse(reader.isCurrentCapture(frame))
        }
    }

    // Cases: MJPEG-IMG-08
    @Test
    fun publicationOrCutoffBeforeWaitCannotStrandReader() = runTest(timeout = 5.seconds) {
        for (change in listOf("publish", "close", "dispose")) {
            withStore { store ->
                val id = attempt()
                activate(store, id)
                offer(store, id, byteArrayOf(1))
                val reader = checkNotNull(store.openReader())
                try {
                    val initial = checkNotNull(reader.acquireFrame())
                    reader.releaseFrame(initial)
                    assertTrue(store.updateCaptureStatus(id, Status.Suspended))
                    val observedEmpty = CompletableDeferred<Unit>()
                    val beginWait = CompletableDeferred<Unit>()
                    val next = async(start = CoroutineStart.UNDISPATCHED) {
                        nextPayload(reader) {
                            observedEmpty.complete(Unit)
                            beginWait.await()
                        }
                    }
                    observedEmpty.await()
                    changeSelection(store, reader, id, change)
                    beginWait.complete(Unit)
                    if (change == "publish") {
                        assertArrayEquals(byteArrayOf(7, 8), next.await())
                    } else {
                        assertNull(next.await())
                        assertTrue(reader.isClosed)
                    }
                } finally {
                    reader.close()
                }
            }
        }
    }

    // Cases: MJPEG-IMG-08
    @Test
    fun waitingReaderResumesWithNewPayloadOrEndsAfterCutoff() = runTest(timeout = 5.seconds) {
        for (change in listOf("publish", "close", "dispose")) {
            withStore { store ->
                val id = attempt()
                activate(store, id)
                offer(store, id, byteArrayOf(1))
                val reader = checkNotNull(store.openReader())
                try {
                    val initial = checkNotNull(reader.acquireFrame())
                    reader.releaseFrame(initial)
                    assertTrue(store.updateCaptureStatus(id, Status.Suspended))
                    val observedEmpty = CompletableDeferred<Unit>()
                    val next = async(start = CoroutineStart.UNDISPATCHED) {
                        nextPayload(reader) { observedEmpty.complete(Unit) }
                    }
                    observedEmpty.await()
                    changeSelection(store, reader, id, change)
                    if (change == "publish") {
                        assertArrayEquals(byteArrayOf(7, 8), next.await())
                    } else {
                        assertNull(next.await())
                        assertTrue(reader.isClosed)
                    }
                } finally {
                    reader.close()
                }
            }
        }
    }

    private fun attempt(): CaptureAttemptId = CaptureAttemptId(instance)

    private fun activate(store: JpegStore, id: CaptureAttemptId) {
        assertTrue(store.registerCapture(id))
        assertTrue(store.updateCaptureStatus(id, Status.Active))
    }

    private fun offer(store: JpegStore, id: CaptureAttemptId, bytes: ByteArray) {
        store.offerJpeg(id, bytes.size) { bytes.copyInto(it) }
    }

    private fun assertSelected(store: JpegStore, expected: ByteArray) {
        assertTrue(store.snapshot().hasImage)
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

    private fun changeSelection(store: JpegStore, reader: JpegStore.Reader, id: CaptureAttemptId, change: String) {
        when (change) {
            "publish" -> {
                assertTrue(store.updateCaptureStatus(id, Status.Active))
                offer(store, id, byteArrayOf(7, 8))
            }

            "close" -> reader.close()
            "dispose" -> store.dispose()
        }
    }

    private suspend fun nextPayload(reader: JpegStore.Reader, onEmpty: suspend () -> Unit): ByteArray? {
        while (true) {
            val revision = reader.changeRevision.value
            val frame = reader.acquireFrame()
            if (frame != null) {
                try {
                    return frame.bytes.copyOf(frame.byteCount)
                } finally {
                    reader.releaseFrame(frame)
                }
            }
            if (reader.isClosed) return null
            onEmpty()
            reader.changeRevision.first { it != revision }
        }
    }
}
