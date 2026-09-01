package io.screenstream.capture.internal.session.production

import io.screenstream.capture.CaptureGeometry
import io.screenstream.capture.FrameRate
import io.screenstream.capture.ImageRect
import io.screenstream.capture.ImageSize
import io.screenstream.capture.ScreenCaptureEffectiveParameters
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.encoding.CarrierDisposition
import io.screenstream.capture.internal.encoding.EncodingInput
import io.screenstream.capture.internal.encoding.EncodingOwner
import io.screenstream.capture.internal.encoding.EncodingProductionReturnPort
import io.screenstream.capture.internal.encoding.EncodingRetirement
import io.screenstream.capture.internal.encoding.ManagedDirectCarrier
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import io.screenstream.capture.internal.storage.ImmutableEncodedPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

internal class SessionProductionStateTest {
    // Verification: SES-06
    @Test
    fun recordIdentityAndOneUnresolvedProductionAreEnforcedAcrossReadConstruction() {
        InputFixture().use { inputs ->
            val production = SessionProduction(creationElapsedRealtimeNanos = 0L)
            val foreignProduction = SessionProduction(creationElapsedRealtimeNanos = 0L)
            val record = production.allocateRecord(configRevision = 1L, jpegQuality = 75)
            val foreignRecord = foreignProduction.allocateRecord(configRevision = 1L, jpegQuality = 75)

            assertTrue(record.belongsTo(production))
            assertFalse(record.belongsTo(foreignProduction))
            assertThrows(IllegalArgumentException::class.java) {
                production.beginReadConstruction(
                    foreignRecord,
                    inputs.input(),
                    expectedByteCount = BYTE_COUNT,
                    returnSink = { _, _ -> },
                )
            }

            val read = production.beginReadConstruction(
                record,
                inputs.input(),
                expectedByteCount = BYTE_COUNT,
                returnSink = { _, _ -> },
            )
            assertTrue(production.hasMaterializedProduction)
            assertFalse(production.currentRecordMatches(record))
            assertFalse(production.currentReadMatches(read))
            assertThrows(IllegalStateException::class.java) {
                production.allocateRecord(configRevision = 2L, jpegQuality = 80)
            }

            val foreignRead = SessionReadBridge(foreignRecord, inputs.input()) { _, _ -> }
            assertNull(production.clearReadConstruction(foreignRead))
            val grant = production.prepareFreshGrant(FrameRate.Auto, nowNanos = 10L)
                    as SessionProduction.FreshGrantDecision.Grant
            production.commitFreshRead(read, grant)
            assertTrue(production.currentRecordMatches(record))
            assertTrue(production.currentReadMatches(read))
            assertNull(production.clearReadConstruction(read))
            assertThrows(IllegalStateException::class.java) {
                production.allocateRecord(configRevision = 2L, jpegQuality = 80)
            }

            assertSame(record, production.clearProduction(read, record))
            assertFalse(production.hasMaterializedProduction)
            val successor = production.allocateRecord(configRevision = 2L, jpegQuality = 80)
            assertTrue(successor.belongsTo(production))
        }
    }

    // Verification: SES-06
    // Verification: STO-01
    @Test
    fun freshAndRepeatCandidatesPreserveExactIdentityAndAllocateSequenceOnlyOnCommit() {
        InputFixture().use { inputs ->
            val production = SessionProduction(creationElapsedRealtimeNanos = 0L)
            val current = production.materializeCurrent(inputs, configRevision = 1L, grantNanos = 10L)
            val payload = payload(1, 2, 3)
            val output = production.completeEncoding(current.record, payload)
                ?: error("encoding output was not retained")

            val firstCandidate = production.prepareFreshOutput(EFFECTIVE_PARAMETERS, 20L, FrameRate.Auto)
                    as SessionProduction.FreshOutputDecision.Candidate
            val duplicateCandidate = production.prepareFreshOutput(EFFECTIVE_PARAMETERS, 20L, FrameRate.Auto)
                    as SessionProduction.FreshOutputDecision.Candidate
            assertSame(current.record, firstCandidate.record)
            assertSame(output, firstCandidate.unpublishedOutput)
            assertEquals(1L, firstCandidate.frame.sequence)
            assertEquals(1L, duplicateCandidate.frame.sequence)

            val fresh = production.commitFreshOutput(firstCandidate) ?: error("fresh candidate was not committed")
            assertEquals(1L, fresh.sequence)
            assertNull(production.commitFreshOutput(duplicateCandidate))

            val repeatEffectiveParameters = ScreenCaptureEffectiveParameters.create(
                appliedParameters = EFFECTIVE_PARAMETERS.appliedParameters.copy(jpegQuality = 74),
                captureGeometry = EFFECTIVE_PARAMETERS.captureGeometry,
                appliedSourceRect = EFFECTIVE_PARAMETERS.appliedSourceRect,
                finalImageSize = EFFECTIVE_PARAMETERS.finalImageSize,
            )
            assertNotEquals(fresh.effectiveParameters, repeatEffectiveParameters)

            val materializedFresh = production.materializeCurrent(inputs, configRevision = 2L, grantNanos = 30L)
            assertSame(
                SessionProduction.RepeatDecision.Missing,
                production.prepareRepeat(
                    repeatEffectiveParameters,
                    FrameRate.Auto,
                    repeatInterval = 1.milliseconds,
                    nowNanos = 1_000_020L,
                ),
            )
            assertSame(
                materializedFresh.record,
                production.clearProduction(materializedFresh.read, materializedFresh.record),
            )

            val firstRepeat = production.prepareRepeat(
                repeatEffectiveParameters,
                FrameRate.Auto,
                repeatInterval = 1.milliseconds,
                nowNanos = 1_000_020L,
            ) as SessionProduction.RepeatDecision.Candidate
            val duplicateRepeat = production.prepareRepeat(
                repeatEffectiveParameters,
                FrameRate.Auto,
                repeatInterval = 1.milliseconds,
                nowNanos = 1_000_020L,
            ) as SessionProduction.RepeatDecision.Candidate
            assertSame(fresh, firstRepeat.previousFrame)
            assertNotSame(fresh, firstRepeat.frame)
            assertSame(fresh.payload, firstRepeat.frame.payload)
            assertSame(repeatEffectiveParameters, firstRepeat.frame.effectiveParameters)
            assertSame(repeatEffectiveParameters, duplicateRepeat.frame.effectiveParameters)
            assertNotSame(fresh.effectiveParameters, firstRepeat.frame.effectiveParameters)
            assertEquals(2L, firstRepeat.frame.sequence)
            assertEquals(1_000_020L, firstRepeat.frame.timestampElapsedRealtimeNanos)
            assertEquals(2L, duplicateRepeat.frame.sequence)

            val repeated = production.commitRepeat(firstRepeat) ?: error("repeat candidate was not committed")
            assertSame(firstRepeat.frame, repeated)
            assertEquals(2L, repeated.sequence)
            assertSame(fresh.payload, repeated.payload)
            assertNull(production.commitRepeat(duplicateRepeat))
        }
    }

    // Verification: SES-05
    // Verification: SES-06
    @Test
    fun freshOutputDeferralRetainsExactProductionUntilExactMaxFpsBoundaryCommits() {
        InputFixture().use { inputs ->
            val production = SessionProduction(creationElapsedRealtimeNanos = 0L)
            val frameRate = FrameRate.MaxFps(2)
            val first = production.materializeCurrent(inputs, configRevision = 1L, grantNanos = 10L)
            production.completeEncoding(first.record, payload(1)) ?: error("first encoding output was not retained")
            val firstFrame = production.commitFreshOutput(
                production.prepareFreshOutput(EFFECTIVE_PARAMETERS, 20L, frameRate)
                        as SessionProduction.FreshOutputDecision.Candidate,
            ) ?: error("first MaxFps output was not committed")

            val second = production.materializeCurrent(inputs, configRevision = 2L, grantNanos = 30L)
            val secondPayload = payload(2, 3)
            val secondOutput = production.completeEncoding(second.record, secondPayload)
                ?: error("second encoding output was not retained")
            val eligibleAtNanos = 500_000_020L

            val deferred = production.prepareFreshOutput(
                EFFECTIVE_PARAMETERS,
                timestampElapsedRealtimeNanos = eligibleAtNanos - 1L,
                frameRate = frameRate,
            ) as SessionProduction.FreshOutputDecision.Deferred

            assertEquals(eligibleAtNanos, deferred.targetNanos)
            assertTrue(production.hasMaterializedProduction)
            assertTrue(production.currentRecordMatches(second.record))
            assertTrue(production.currentReadMatches(second.read))
            assertTrue(production.frameIsLatest(firstFrame))
            assertThrows(IllegalStateException::class.java) {
                production.allocateRecord(configRevision = 3L, jpegQuality = 75)
            }

            val candidate = production.prepareFreshOutput(
                EFFECTIVE_PARAMETERS,
                timestampElapsedRealtimeNanos = eligibleAtNanos,
                frameRate = frameRate,
            ) as SessionProduction.FreshOutputDecision.Candidate

            assertSame(second.record, candidate.record)
            assertSame(second.read, candidate.read)
            assertSame(secondOutput, candidate.unpublishedOutput)
            assertSame(secondPayload, candidate.unpublishedOutput.payload)
            assertSame(secondPayload, candidate.frame.payload)
            assertSame(firstFrame, candidate.previousLatestFrame)
            assertTrue(production.hasMaterializedProduction)

            val committed = production.commitFreshOutput(candidate) ?: error("boundary output was not committed")
            assertSame(secondPayload, committed.payload)
            assertEquals(2L, committed.sequence)
            assertFalse(production.hasMaterializedProduction)
            assertTrue(production.frameIsLatest(committed))
        }
    }

    // Verification: SES-06
    // Verification: STO-01
    @Test
    fun cacheCandidatesReuseExactLatestFrameAndCannotInvalidateItsReplacement() {
        InputFixture().use { inputs ->
            val production = SessionProduction(creationElapsedRealtimeNanos = 0L)
            val current = production.materializeCurrent(inputs, configRevision = 1L, grantNanos = 10L)
            production.completeEncoding(current.record, payload(4, 5)) ?: error("encoding output was not retained")
            val freshCandidate = production.prepareFreshOutput(EFFECTIVE_PARAMETERS, 20L, FrameRate.Auto)
                    as SessionProduction.FreshOutputDecision.Candidate
            val fresh = production.commitFreshOutput(freshCandidate) ?: error("fresh candidate was not committed")

            val firstCache = production.prepareCache() ?: error("fresh cache was not available")
            val secondCache = production.prepareCache() ?: error("repeated cache lookup was not available")
            assertSame(fresh, firstCache.frame)
            assertSame(fresh, secondCache.frame)

            val repeat = production.prepareRepeat(
                EFFECTIVE_PARAMETERS,
                FrameRate.Auto,
                repeatInterval = 1.milliseconds,
                nowNanos = 1_000_020L,
            ) as SessionProduction.RepeatDecision.Candidate
            val replacement = production.commitRepeat(repeat) ?: error("repeat candidate was not committed")
            assertSame(fresh.payload, replacement.payload)

            production.invalidateCache(firstCache)

            assertTrue(production.frameIsLatest(replacement))
            assertSame(replacement, production.prepareCache()?.frame)
        }
    }

    // Verification: SES-06
    @Test
    fun staleOutputDiscardRequiresRevisionMismatchAndReleasesTheWholeProduction() {
        InputFixture().use { inputs ->
            val production = SessionProduction(creationElapsedRealtimeNanos = 0L)
            val current = production.materializeCurrent(inputs, configRevision = 3L, grantNanos = 10L)
            assertTrue(production.clearReadKeepProduction(current.read, current.record))
            val output = production.completeEncoding(current.record, payload(6, 7, 8))
                ?: error("encoding output was not retained")

            assertNull(production.prepareStaleOutput(expectedRevision = 3L))
            val stale = production.prepareStaleOutput(expectedRevision = 4L)
                ?: error("revision-mismatched output was not identified")
            assertSame(current.record, stale.record)
            assertSame(output, stale.unpublishedOutput)
            production.discardStaleOutput(stale)

            assertFalse(production.hasMaterializedProduction)
            assertFalse(production.currentRecordMatches(current.record))
            assertEquals(1L, production.prepareTerminal().finalStats.droppedFrames.byStaleWork)
        }
    }

    // Verification: SES-05
    @Test
    fun pacingReplacementKeepsEarlierIdentityWhileRepeatRetainsOnePendingIdentity() {
        val production = SessionProduction(creationElapsedRealtimeNanos = 0L)

        val pacing = production.armPacingWake(targetNanos = 100L, configRevision = 1L)
            ?: error("first pacing wake was not armed")
        assertNull(production.armPacingWake(targetNanos = 100L, configRevision = 2L))
        assertNull(production.armPacingWake(targetNanos = 101L, configRevision = 2L))
        assertSame(pacing, production.currentPacingWake())
        val earlierPacing = production.armPacingWake(targetNanos = 90L, configRevision = 2L)
            ?: error("earlier pacing wake did not replace the installed wake")
        assertSame(earlierPacing, production.currentPacingWake())
        assertFalse(production.clearWake(pacing))
        assertTrue(production.clearWake(earlierPacing))

        val repeat = production.armRepeatWake(targetNanos = 200L, configRevision = 3L)
            ?: error("first repeat wake was not armed")
        assertNull(production.armRepeatWake(targetNanos = 150L, configRevision = 4L))
        assertSame(repeat, production.currentRepeatWake())
        assertFalse(production.clearWake(SessionProduction.WakeIdentity.Repeat(200L, 3L)))
        assertTrue(production.clearWake(repeat))
        assertNull(production.currentRepeatWake())
    }

    // Verification: SES-06
    @Test
    fun terminalSnapshotFreezesExactProductionRootsAndDisablesSuccessors() {
        InputFixture().use { inputs ->
            val production = SessionProduction(creationElapsedRealtimeNanos = 0L)
            val first = production.materializeCurrent(inputs, configRevision = 1L, grantNanos = 10L)
            production.completeEncoding(first.record, payload(9)) ?: error("first encoding output was not retained")
            val published = production.commitFreshOutput(
                production.prepareFreshOutput(EFFECTIVE_PARAMETERS, 20L, FrameRate.Auto)
                        as SessionProduction.FreshOutputDecision.Candidate,
            ) ?: error("first frame was not published")

            val terminalCurrent = production.materializeCurrent(inputs, configRevision = 2L, grantNanos = 30L)
            assertTrue(production.clearReadKeepProduction(terminalCurrent.read, terminalCurrent.record))
            val terminalOutput = production.completeEncoding(terminalCurrent.record, payload(10, 11))
                ?: error("terminal encoding output was not retained")
            val staleSnapshot = production.prepareTerminal()
            production.armPacingWake(targetNanos = 100L, configRevision = 2L)
                ?: error("terminal pacing wake was not armed")
            assertFalse(staleSnapshot.isCurrent(production))
            assertThrows(IllegalStateException::class.java) { production.commitTerminal(staleSnapshot) }

            val repeatWake = production.armRepeatWake(targetNanos = 200L, configRevision = 2L)
                ?: error("terminal repeat wake was not armed")
            val terminal = production.prepareTerminal()
            assertSame(terminalCurrent.record, terminal.currentRecord)
            assertNull(terminal.currentRead)
            assertSame(terminalOutput, terminal.unpublishedOutput)
            assertSame(published, terminal.latestFrame)
            assertSame(repeatWake, production.currentRepeatWake())
            assertTrue(terminal.isCurrent(production))

            production.commitTerminal(terminal)

            assertSame(terminalCurrent.record, terminal.currentRecord)
            assertSame(terminalOutput, terminal.unpublishedOutput)
            assertSame(published, terminal.latestFrame)
            assertFalse(production.hasMaterializedProduction)
            assertNull(production.prepareCache())
            assertNull(production.currentPacingWake())
            assertNull(production.currentRepeatWake())
            assertNull(production.armPacingWake(targetNanos = 50L, configRevision = 3L))
            assertSame(
                SessionProduction.FreshGrantDecision.InvalidEvidence,
                production.prepareFreshGrant(FrameRate.Auto, nowNanos = 40L),
            )
            assertThrows(IllegalStateException::class.java) {
                production.allocateRecord(configRevision = 3L, jpegQuality = 75)
            }
        }
    }

    private class InputFixture : AutoCloseable {
        private val owner = EncodingOwner(FailFastNonInlineDispatcher, ZeroClock)
        private val loans = mutableListOf<Pair<ManagedDirectCarrier, EncodingInput>>()

        fun input(): EncodingInput {
            val carrier = ManagedDirectCarrier(Rgba8888Layout.create(2, 2))
            check(carrier.allocateIntoPendingOwner() is ManagedDirectCarrier.Creation.Created)
            val input = carrier.lend(owner, NoOpEncodingReturnPort) ?: error("carrier did not lend")
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

    private class CurrentProduction(
        val record: SessionProductionRecord,
        val read: SessionReadBridge,
    )

    private fun SessionProduction.materializeCurrent(
        inputs: InputFixture,
        configRevision: Long,
        grantNanos: Long,
    ): CurrentProduction {
        val record = allocateRecord(configRevision, jpegQuality = 75)
        val read = beginReadConstruction(record, inputs.input(), BYTE_COUNT) { _, _ -> }
        val grant = prepareFreshGrant(FrameRate.Auto, grantNanos) as SessionProduction.FreshGrantDecision.Grant
        commitFreshRead(read, grant)
        return CurrentProduction(record, read)
    }

    private object NoOpEncodingReturnPort : EncodingProductionReturnPort {
        override fun onReturned(result: io.screenstream.capture.internal.encoding.EncodingResult) = Unit
    }

    private object FailFastNonInlineDispatcher : NonInlineDispatcher {
        override fun tryDispatch(task: Runnable): Boolean = throw AssertionError("worker dispatch was not expected")
    }

    private object ZeroClock : ElapsedRealtimeClock {
        override fun nowNanos(): Long = 0L
    }

    private companion object {
        private const val BYTE_COUNT = 16

        private val EFFECTIVE_PARAMETERS = ScreenCaptureEffectiveParameters.create(
            appliedParameters = ScreenCaptureParameters.DEFAULT,
            captureGeometry = CaptureGeometry.create(widthPx = 2, heightPx = 2, densityDpi = 320),
            appliedSourceRect = ImageRect.create(leftPx = 0, topPx = 0, rightPx = 2, bottomPx = 2),
            finalImageSize = ImageSize.create(widthPx = 2, heightPx = 2),
        )

        private fun payload(vararg bytes: Int): ImmutableEncodedPayload {
            val segment = ByteArray(bytes.size) { index -> bytes[index].toByte() }
            return ImmutableEncodedPayload(arrayOf(segment), byteCount = segment.size)
        }
    }
}
