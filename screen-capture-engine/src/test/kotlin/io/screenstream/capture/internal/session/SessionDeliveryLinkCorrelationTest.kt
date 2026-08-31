package io.screenstream.capture.internal.session

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.screenstream.capture.CaptureGeometry
import io.screenstream.capture.ImageRect
import io.screenstream.capture.ImageSize
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.ScreenCaptureEffectiveParameters
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.internal.delivery.DeliveryFact
import io.screenstream.capture.internal.delivery.DeliveryHandoffToken
import io.screenstream.capture.internal.delivery.DeliveryOffer
import io.screenstream.capture.internal.metrics.SessionMetricsSourceSelection
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import io.screenstream.capture.internal.runtime.HandlerThreadPlatform
import io.screenstream.capture.internal.storage.ImmutableEncodedPayload
import io.screenstream.capture.internal.storage.PublishedFrame
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

internal class SessionDeliveryLinkCorrelationTest {
    // Verification: DEL-02
    @Test
    fun exactTokenCorrelatesOfferReturnBeforeFactAndFactBeforeOfferReturn() {
        LinkFixture().use { fixture ->
            val link = fixture.link
            val first = link.prepareOfferLocked(1L, { }, frame())
            assertSame(first.handoff, link.currentHandoffLocked())
            assertTrue(link.hasPendingOfferLocked())
            assertTrue(link.recordOfferReturnedLocked(first, DeliveryOffer.Accepted(first.handoff)))
            assertFalse(link.hasPendingOfferLocked())

            val firstFailure = DeliveryFact.CallbackFailure(first.handoff, IllegalStateException("callback"))
            assertSame(SessionDeliveryLink.FactAdmission.Recorded, link.recordFactLocked(firstFailure))
            assertSame(firstFailure, link.takeCallbackFailureLocked())
            val firstClosed = DeliveryFact.Closed(first.handoff, DeliveryFact.Closed.Outcome.CallbackReturned)
            assertSame(SessionDeliveryLink.FactAdmission.Recorded, link.recordFactLocked(firstClosed))
            assertTrue(link.markClosedReadyLocked(firstClosed))
            assertSame(firstClosed, link.takeClosedLocked())
            assertTrue(link.clearHandoffLocked(first.handoff))

            val second = link.prepareOfferLocked(2L, { }, frame())
            val earlyClosed = DeliveryFact.Closed(second.handoff, DeliveryFact.Closed.Outcome.CallbackReturned)
            assertSame(SessionDeliveryLink.FactAdmission.Recorded, link.recordFactLocked(earlyClosed))
            assertTrue(link.recordOfferReturnedLocked(second, DeliveryOffer.Accepted(second.handoff)))
            assertTrue(link.markClosedReadyLocked(earlyClosed))
            assertSame(earlyClosed, link.takeClosedLocked())
            assertTrue(link.clearHandoffLocked(second.handoff))
        }
    }

    // Verification: DEL-02
    @Test
    fun numericSubstitutionDuplicateMismatchAndStaleAreDistinct() {
        LinkFixture().use { fixture ->
            val link = fixture.link
            val request = link.prepareOfferLocked(5L, { }, frame())
            val exact = request.handoff

            val lower = DeliveryFact.CallbackFailure(DeliveryHandoffToken(4L), IllegalStateException("lower"))
            assertSame(SessionDeliveryLink.FactAdmission.Stale, link.recordFactLocked(lower))
            val higher = DeliveryFact.CallbackFailure(DeliveryHandoffToken(6L), IllegalStateException("higher"))
            assertSame(SessionDeliveryLink.FactAdmission.Mismatch, link.recordFactLocked(higher))
            val substituted = DeliveryFact.CallbackFailure(DeliveryHandoffToken(5L), IllegalStateException("substituted"))
            assertSame(SessionDeliveryLink.FactAdmission.Mismatch, link.recordFactLocked(substituted))

            val exactFailure = DeliveryFact.CallbackFailure(exact, IllegalStateException("exact"))
            assertSame(SessionDeliveryLink.FactAdmission.Recorded, link.recordFactLocked(exactFailure))
            assertSame(SessionDeliveryLink.FactAdmission.Duplicate, link.recordFactLocked(exactFailure))
            assertSame(exactFailure, link.takeCallbackFailureLocked())

            val closed = DeliveryFact.Closed(exact, DeliveryFact.Closed.Outcome.CallbackReturned)
            assertSame(SessionDeliveryLink.FactAdmission.Recorded, link.recordFactLocked(closed))
            assertSame(SessionDeliveryLink.FactAdmission.Duplicate, link.recordFactLocked(closed))
            assertSame(SessionDeliveryLink.FactAdmission.Mismatch, link.recordFactLocked(exactFailure))
            assertTrue(link.markClosedReadyLocked(closed))
            assertSame(closed, link.takeClosedLocked())
            assertTrue(link.clearHandoffLocked(exact))

            assertSame(SessionDeliveryLink.FactAdmission.Stale, link.recordFactLocked(closed))
        }
    }

    // Verification: DEL-02
    @Test
    fun offerReturnMustMatchExactRequestAndTokenBeforeItCanClearCorrelation() {
        LinkFixture().use { fixture ->
            val link = fixture.link
            val request = link.prepareOfferLocked(9L, { }, frame())
            val substituted = DeliveryOffer.Accepted(DeliveryHandoffToken(9L))
            assertFalse(link.recordOfferReturnedLocked(request, substituted))
            assertTrue(link.hasPendingOfferLocked())
            assertSame(request.handoff, link.currentHandoffLocked())

            assertTrue(link.recordOfferReturnedLocked(request, DeliveryOffer.Rejected(request.handoff, cause = null)))
            assertFalse(link.hasPendingOfferLocked())
            assertTrue(link.currentHandoffLocked() == null)

            val successor = link.prepareOfferLocked(10L, { }, frame())
            assertTrue(link.recordOfferReturnedLocked(successor, DeliveryOffer.Occupied))
            assertTrue(link.currentHandoffLocked() == null)
        }
    }

    // Verification: DEL-02
    @Test
    fun closedIsConsumableOnlyAfterReadyAndTerminalFreezeMakesLateReadyCleanupOnly() {
        LinkFixture().use { fixture ->
            val link = fixture.link
            val request = link.prepareOfferLocked(12L, { }, frame())
            val closed = DeliveryFact.Closed(request.handoff, DeliveryFact.Closed.Outcome.CutoffBeforeEntry)
            assertSame(SessionDeliveryLink.FactAdmission.Recorded, link.recordFactLocked(closed))
            assertTrue(link.takeClosedLocked() == null)

            link.freezeTerminalLocked()
            assertFalse(link.hasPendingOfferLocked())
            assertTrue(link.currentHandoffLocked() == null)
            assertFalse(link.markClosedReadyLocked(closed))
            assertTrue(link.takeClosedLocked() == null)
            assertSame(SessionDeliveryLink.FactAdmission.Stale, link.recordFactLocked(closed))

            try {
                link.prepareOfferLocked(13L, { }, frame())
                fail("terminal-frozen link prepared a successor offer")
            } catch (_: IllegalStateException) {
            }
        }
    }

    // Verification: DEL-02
    @Test
    fun clearRequiresExactHandoffAndNoRetainedFacts() {
        LinkFixture().use { fixture ->
            val link = fixture.link
            val request = link.prepareOfferLocked(15L, { }, frame())
            assertFalse(link.clearHandoffLocked(DeliveryHandoffToken(15L)))

            val failure = DeliveryFact.CallbackFailure(request.handoff, IllegalStateException("callback"))
            assertSame(SessionDeliveryLink.FactAdmission.Recorded, link.recordFactLocked(failure))
            assertFalse(link.clearHandoffLocked(request.handoff))
            assertSame(failure, link.takeCallbackFailureLocked())
            assertTrue(link.clearHandoffLocked(request.handoff))
            assertTrue(link.currentHandoffLocked() == null)
        }
    }

    private class LinkFixture : AutoCloseable {
        private val dispatcher = ControlledNonInlineDispatcher()
        private val coordinator = SessionCoordinator(
            metricsSourceSelection = SessionMetricsSourceSelection.Explicit { AutoCloseable { } },
            jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly,
            workerDispatcher = dispatcher,
            handlerThreadPlatform = FailFastHandlerThreadPlatform,
            handlerTaskPoster = FailFastHandlerTaskPoster,
            delayedEntryScheduler = { _, _ -> throw AssertionError("delayed scheduling was not expected") },
            executionClock = { 0L },
            currentEpochMillis = { 0L },
            platformSdkInt = 37,
        )

        val link = SessionDeliveryLink(coordinator, dispatcher)

        override fun close() {
            dispatcher.close()
        }
    }

    private object FailFastHandlerThreadPlatform : HandlerThreadPlatform {
        override fun newThread(name: String): HandlerThread = throw AssertionError("HandlerThread creation was not expected")

        override fun start(thread: HandlerThread) = throw AssertionError("HandlerThread start was not expected")

        // HandlerThreadPlatform deliberately exposes a nullable looper; this fixture must fail fast instead.
        @Suppress("RedundantNullableReturnType")
        override fun looper(thread: HandlerThread): Looper? = throw AssertionError("Looper access was not expected")

        override fun handler(looper: Looper): Handler = throw AssertionError("Handler creation was not expected")
    }

    private object FailFastHandlerTaskPoster : HandlerTaskPoster {
        override fun post(handler: Handler, task: Runnable): Boolean = throw AssertionError("Handler post was not expected")

        override fun postDelayed(handler: Handler, task: Runnable, delayMillis: Long): Boolean =
            throw AssertionError("Handler delayed post was not expected")

        override fun removeCallbacks(handler: Handler, task: Runnable) =
            throw AssertionError("Handler callback removal was not expected")
    }

    private companion object {
        private val EFFECTIVE_PARAMETERS = ScreenCaptureEffectiveParameters.create(
            appliedParameters = ScreenCaptureParameters.DEFAULT,
            captureGeometry = CaptureGeometry.create(widthPx = 2, heightPx = 2, densityDpi = 320),
            appliedSourceRect = ImageRect.create(leftPx = 0, topPx = 0, rightPx = 2, bottomPx = 2),
            finalImageSize = ImageSize.create(widthPx = 2, heightPx = 2),
        )

        private fun frame(): PublishedFrame = PublishedFrame(
            payload = ImmutableEncodedPayload(arrayOf(byteArrayOf(1, 2, 3)), byteCount = 3),
            effectiveParameters = EFFECTIVE_PARAMETERS,
            sequence = 1L,
            timestampElapsedRealtimeNanos = 2L,
        )
    }
}
