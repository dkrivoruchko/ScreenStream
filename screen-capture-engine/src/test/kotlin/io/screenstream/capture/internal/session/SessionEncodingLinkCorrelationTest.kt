package io.screenstream.capture.internal.session

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.encoding.CarrierDisposition
import io.screenstream.capture.internal.encoding.EncodingInput
import io.screenstream.capture.internal.encoding.EncodingInputSettlement
import io.screenstream.capture.internal.encoding.EncodingOwner
import io.screenstream.capture.internal.encoding.EncodingReconcileResult
import io.screenstream.capture.internal.encoding.EncodingReconcileSubmission
import io.screenstream.capture.internal.encoding.EncodingResult
import io.screenstream.capture.internal.encoding.EncodingRetirement
import io.screenstream.capture.internal.encoding.ManagedDirectCarrier
import io.screenstream.capture.internal.encoding.NativeJpegProcess
import io.screenstream.capture.internal.metrics.SessionMetricsSourceSelection
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import io.screenstream.capture.internal.runtime.HandlerThreadPlatform
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import io.screenstream.capture.internal.session.production.SessionProduction
import io.screenstream.capture.internal.session.production.SessionProductionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SessionEncodingLinkCorrelationTest {
    // Verification: SES-03
    @Test
    fun reconcileCorrelatesCallbackBeforeSubmissionReturnAndReturnBeforeCallback() {
        LinkFixture().use { fixture ->
            val link = fixture.link
            val firstPlan = Rgba8888Layout.create(2, 2)
            val callbackFirst = link.prepareReconcileLocked(configRevision = 7L, plan = firstPlan)

            assertSame(firstPlan, callbackFirst.plan)
            assertSame(
                SessionEncodingLink.FactAdmission.Recorded,
                link.recordReconcileCallbackLocked(callbackFirst, EncodingReconcileResult.Ready),
            )
            val earlyFact = link.takeReconcileFactLocked() ?: error("early reconcile callback was not retained")
            assertSame(callbackFirst, earlyFact.request)
            assertSame(EncodingReconcileResult.Ready, earlyFact.result)
            assertTrue(link.recordReconcileSubmissionLocked(callbackFirst, EncodingReconcileSubmission.Accepted))

            val returnFirst = link.prepareReconcileLocked(configRevision = 8L, plan = Rgba8888Layout.create(3, 2))
            assertTrue(link.recordReconcileSubmissionLocked(returnFirst, EncodingReconcileSubmission.Accepted))
            assertSame(
                SessionEncodingLink.FactAdmission.Recorded,
                link.recordReconcileCallbackLocked(returnFirst, EncodingReconcileResult.CutoffInert),
            )
            val lateFact = link.takeReconcileFactLocked() ?: error("late reconcile callback was not retained")
            assertSame(returnFirst, lateFact.request)
            assertSame(EncodingReconcileResult.CutoffInert, lateFact.result)

            val successor = link.prepareReconcileLocked(configRevision = 9L, plan = firstPlan)
            assertEquals(9L, successor.configRevision)
        }
    }

    // Verification: SES-03
    @Test
    fun reconcileRequiresExactRequestAndRejectsDuplicateFactsAndReturns() {
        LinkFixture().use { fixture ->
            val link = fixture.link
            val exact = link.prepareReconcileLocked(configRevision = 11L, plan = Rgba8888Layout.create(2, 2))
            val foreign = fixture.foreignLink.prepareReconcileLocked(
                configRevision = exact.configRevision,
                plan = exact.plan,
            )

            assertFalse(link.recordReconcileSubmissionLocked(foreign, EncodingReconcileSubmission.Accepted))
            assertSame(
                SessionEncodingLink.FactAdmission.MismatchOrDuplicate,
                link.recordReconcileCallbackLocked(foreign, EncodingReconcileResult.Ready),
            )
            assertSame(
                SessionEncodingLink.FactAdmission.Recorded,
                link.recordReconcileCallbackLocked(exact, EncodingReconcileResult.Ready),
            )
            assertSame(
                SessionEncodingLink.FactAdmission.MismatchOrDuplicate,
                link.recordReconcileCallbackLocked(exact, EncodingReconcileResult.CutoffInert),
            )
            assertTrue(link.recordReconcileSubmissionLocked(exact, EncodingReconcileSubmission.Accepted))
            assertFalse(link.recordReconcileSubmissionLocked(exact, EncodingReconcileSubmission.Accepted))

            val fact = link.takeReconcileFactLocked() ?: error("exact reconcile fact was not retained")
            assertSame(exact, fact.request)
            assertSame(EncodingReconcileResult.Ready, fact.result)
        }
    }

    // Verification: SES-03
    @Test
    fun productionTracksExactRequestAcrossLoanSettlementAndCallbackOrderings() {
        LinkFixture().use { fixture ->
            val link = fixture.link
            val firstRecord = fixture.record(configRevision = 21L)
            val callbackFirst = link.prepareProductionLocked(firstRecord)
            val firstInput = fixture.input(callbackFirst)

            assertSame(
                SessionEncodingLink.FactAdmission.MismatchOrDuplicate,
                link.recordProductionCallbackLocked(callbackFirst, EncodingResult.FrameFailed),
            )
            assertTrue(link.recordAcquireReturnedLocked(callbackFirst, firstInput))
            assertSame(firstInput, callbackFirst.input)
            assertSame(SessionEncodingLink.ProductionPhase.Loaned, callbackFirst.phase)
            assertFalse(link.beginSettlementLocked(fixture.record(configRevision = 22L), firstInput, shouldEncode = true))
            assertTrue(link.beginSettlementLocked(firstRecord, firstInput, shouldEncode = true))
            assertSame(SessionEncodingLink.ProductionPhase.Settling, callbackFirst.phase)
            assertSame(
                SessionEncodingLink.FactAdmission.Recorded,
                link.recordProductionCallbackLocked(callbackFirst, EncodingResult.FrameFailed),
            )
            assertSame(
                SessionEncodingLink.FactAdmission.MismatchOrDuplicate,
                link.recordProductionCallbackLocked(callbackFirst, EncodingResult.ReadinessChanged),
            )
            assertTrue(
                link.recordSettlementReturnedLocked(
                    firstRecord,
                    firstInput,
                    EncodingInputSettlement.Accepted,
                ),
            )
            assertSame(SessionEncodingLink.ProductionPhase.Accepted, callbackFirst.phase)
            assertFalse(link.isProductionSlotFree)
            val firstFact = link.takeProductionFactLocked() ?: error("callback-first production fact was not retained")
            assertSame(callbackFirst, firstFact.request)
            assertSame(EncodingResult.FrameFailed, firstFact.result)
            assertTrue(link.isProductionSlotFree)

            val secondRecord = fixture.record(configRevision = 23L)
            val returnFirst = link.prepareProductionLocked(secondRecord)
            val secondInput = fixture.input(returnFirst)
            assertTrue(link.recordAcquireReturnedLocked(returnFirst, secondInput))
            assertTrue(link.beginSettlementLocked(secondRecord, secondInput, shouldEncode = true))
            assertTrue(
                link.recordSettlementReturnedLocked(
                    secondRecord,
                    secondInput,
                    EncodingInputSettlement.Accepted,
                ),
            )
            assertSame(SessionEncodingLink.ProductionPhase.Accepted, returnFirst.phase)
            assertSame(
                SessionEncodingLink.FactAdmission.Recorded,
                link.recordProductionCallbackLocked(returnFirst, EncodingResult.ReadinessChanged),
            )
            assertSame(returnFirst, link.takeProductionFactLocked()?.request)
            assertTrue(link.isProductionSlotFree)
        }
    }

    // Verification: SES-03
    @Test
    fun terminalFreezeRequiresExactProductionLoan() {
        LinkFixture().use { fixture ->
            val link = fixture.link
            val record = fixture.record(configRevision = 31L)
            val exact = link.prepareProductionLocked(record)
            val exactInput = fixture.input(exact)
            val foreignRecord = fixture.record(configRevision = 31L)
            val foreignRequest = fixture.foreignLink.prepareProductionLocked(foreignRecord)

            assertFalse(link.recordAcquireReturnedLocked(foreignRequest, exactInput))
            assertTrue(link.recordAcquireReturnedLocked(exact, exactInput))
            assertFalse(link.canFreezeTerminalLocked(preservedRecord = null, preservedInput = null))
            assertFalse(link.canFreezeTerminalLocked(record, preservedInput = null))
            assertFalse(link.canFreezeTerminalLocked(record, fixture.input(foreignRequest)))
            assertFalse(link.canFreezeTerminalLocked(foreignRecord, exactInput))
            assertTrue(link.canFreezeTerminalLocked(record, exactInput))

            link.freezeTerminalLocked(record, exactInput)

            assertFalse(link.isProductionSlotFree)
            assertTrue(link.beginSettlementLocked(record, exactInput, shouldEncode = false))
            assertTrue(
                link.recordSettlementReturnedLocked(
                    record,
                    exactInput,
                    EncodingInputSettlement.Settled,
                ),
            )
            assertTrue(link.isProductionSlotFree)
            assertSame(
                SessionEncodingLink.FactAdmission.MismatchOrDuplicate,
                link.recordProductionCallbackLocked(exact, EncodingResult.CutoffInert),
            )
        }
    }

    private class LinkFixture : AutoCloseable {
        private val coordinator = SessionCoordinator(
            metricsSourceSelection = SessionMetricsSourceSelection.Explicit { AutoCloseable { } },
            jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly,
            workerDispatcher = FailFastNonInlineDispatcher,
            handlerThreadPlatform = FailFastHandlerThreadPlatform,
            handlerTaskPoster = FailFastHandlerTaskPoster,
            delayedEntryScheduler = { _, _ -> throw AssertionError("delayed scheduling was not expected") },
            executionClock = { 0L },
            currentEpochMillis = { 0L },
            platformSdkInt = 37,
        )
        private val inputOwner = EncodingOwner(FailFastNonInlineDispatcher, ZeroClock)
        private val loans = mutableListOf<Pair<ManagedDirectCarrier, EncodingInput>>()

        val link = SessionEncodingLink(coordinator, FailFastNonInlineDispatcher, ZeroClock, NativeJpegProcess)
        val foreignLink = SessionEncodingLink(coordinator, FailFastNonInlineDispatcher, ZeroClock, NativeJpegProcess)

        fun record(configRevision: Long): SessionProductionRecord =
            SessionProduction(creationElapsedRealtimeNanos = 0L).allocateRecord(configRevision, jpegQuality = 75)

        fun input(request: SessionEncodingLink.ProductionRequest): EncodingInput {
            val carrier = ManagedDirectCarrier(Rgba8888Layout.create(2, 2))
            check(carrier.allocateIntoPendingOwner() is ManagedDirectCarrier.Creation.Created)
            val input = carrier.lend(inputOwner, request) ?: error("carrier did not lend")
            loans += carrier to input
            return input
        }

        override fun close() {
            loans.forEach { (carrier, input) ->
                require(carrier.ownsCaptureLoan(input))
                require(carrier.settle(input, CarrierDisposition.Discarded) === input)
                require(carrier.retireIfIdle() === EncodingRetirement.Closed)
            }
            loans.clear()
        }
    }

    private object FailFastNonInlineDispatcher : NonInlineDispatcher {
        override fun tryDispatch(task: Runnable): Boolean = throw AssertionError("worker dispatch was not expected")
    }

    private object ZeroClock : ElapsedRealtimeClock {
        override fun nowNanos(): Long = 0L
    }

    private object FailFastHandlerThreadPlatform : HandlerThreadPlatform {
        override fun newThread(name: String): HandlerThread = throw AssertionError("HandlerThread creation was not expected")

        override fun start(thread: HandlerThread) = throw AssertionError("HandlerThread start was not expected")

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
}
