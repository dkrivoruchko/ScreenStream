package io.screenstream.capture.internal.session.production

import io.screenstream.capture.CaptureOutputInfo
import io.screenstream.capture.FrameRate
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureStats
import io.screenstream.capture.internal.capture.CaptureReadResult
import io.screenstream.capture.internal.encoding.EncodingInput
import io.screenstream.capture.internal.isExactWritableRgbaCarrier
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.storage.ImmutableEncodedPayload
import io.screenstream.capture.internal.storage.PublishedFrame

/**
 * Exclusive semantic owner of materialized production, pacing, the latest immutable frame, output
 * identities, and public statistics for one session.
 *
 * At most one fresh production is materialized. Grants, output candidates, cached-frame candidates, wakes, and
 * terminal snapshots are provisional identity-bearing evidence and must be revalidated before commit. Clearing a
 * record, terminal state, or elapsed time never fabricates a Capture return or settles an Encoding input loan. Fresh
 * capture and output grants keep separate cadence histories. Prefreeze accounting records only evidence already
 * consumed and does not prove every encoder operation drained.
 */
internal class SessionProduction(creationElapsedRealtimeNanos: Long) {
    internal class UnpublishedOutput(
        internal val record: SessionProductionRecord,
        internal val payload: ImmutableEncodedPayload,
    )

    internal sealed interface FreshGrantDecision {
        data object InvalidEvidence : FreshGrantDecision
        class RetainUntil(internal val targetNanos: Long) : FreshGrantDecision
        class Grant(
            private val owner: SessionProduction,
            private val generation: Long,
            internal val grantNanos: Long,
            internal val frameRate: FrameRate,
            internal val nextPhase: Int?,
            internal val nextRequiredGapNanos: Long,
        ) : FreshGrantDecision {
            internal fun isCurrent(expectedOwner: SessionProduction): Boolean =
                (owner === expectedOwner) && (generation == expectedOwner.generation)
        }
    }

    internal sealed interface FreshOutputDecision {
        data object Missing : FreshOutputDecision
        data object InvalidEvidence : FreshOutputDecision
        data object SequenceExhausted : FreshOutputDecision
        class Deferred(internal val targetNanos: Long) : FreshOutputDecision
        class Candidate(
            private val owner: SessionProduction,
            private val generation: Long,
            internal val record: SessionProductionRecord,
            internal val unpublishedOutput: UnpublishedOutput,
            internal val read: SessionReadBridge?,
            internal val previousLatestFrame: PublishedFrame?,
            internal val frame: PublishedFrame,
            internal val frameRate: FrameRate,
            internal val nextPhase: Int?,
            internal val nextRequiredGapNanos: Long,
        ) : FreshOutputDecision {
            internal fun isCurrent(expectedOwner: SessionProduction): Boolean = (owner === expectedOwner) &&
                    (generation == expectedOwner.generation) && (expectedOwner.currentRecord === record) &&
                    (expectedOwner.unpublishedOutput === unpublishedOutput) && (expectedOwner.currentRead === read) &&
                    (expectedOwner.latestFrame === previousLatestFrame)
        }
    }

    internal class CacheCandidate(private val owner: SessionProduction, internal val frame: PublishedFrame) {
        internal fun isCurrent(expectedOwner: SessionProduction): Boolean =
            (owner === expectedOwner) && (expectedOwner.latestFrame === frame)
    }

    internal class StaleOutputCandidate(internal val record: SessionProductionRecord, internal val unpublishedOutput: UnpublishedOutput) {
        internal fun isCurrent(expectedOwner: SessionProduction): Boolean = (expectedOwner.currentRead == null) &&
                (expectedOwner.currentRecord === record) &&
                (expectedOwner.unpublishedOutput === unpublishedOutput)
    }

    internal class StatsCandidate(
        private val owner: SessionProduction,
        private val statsGeneration: Long,
        internal val stats: ScreenCaptureStats,
        internal val publicationNanos: Long,
    ) {
        internal fun isCurrent(expectedOwner: SessionProduction): Boolean = (owner === expectedOwner) &&
                (statsGeneration == expectedOwner.statsGeneration) &&
                expectedOwner.statsAccumulator.hasUnpublishedChanges && !expectedOwner.terminalCommitted
    }

    internal class PacingWake(internal val targetNanos: Long, internal val configRevision: Long)

    internal class TerminalSnapshot(
        private val owner: SessionProduction,
        private val generation: Long,
        internal val constructingRead: SessionReadBridge?,
        internal val currentRead: SessionReadBridge?,
        internal val currentRecord: SessionProductionRecord?,
        internal val unpublishedOutput: UnpublishedOutput?,
        internal val latestFrame: PublishedFrame?,
        internal val finalStats: ScreenCaptureStats,
    ) {
        internal fun isCurrent(expectedOwner: SessionProduction): Boolean = (owner === expectedOwner) &&
                (generation == expectedOwner.generation) && !expectedOwner.terminalCommitted &&
                (expectedOwner.constructingRead === constructingRead) && (expectedOwner.currentRead === currentRead) &&
                (expectedOwner.currentRecord === currentRecord) &&
                (expectedOwner.unpublishedOutput === unpublishedOutput) &&
                (expectedOwner.latestFrame === latestFrame)
    }

    private val statsAccumulator = SessionStatsAccumulator()
    private var generation = 0L
    private var statsGeneration = 0L
    private var lastStatsPublicationNanos = creationElapsedRealtimeNanos
    private var lastCommittedOutputSequence = 0L
    private var lastFreshGrantNanos: Long? = null
    private var freshCadenceHistory: CadenceHistory? = null
    private var outputCadenceHistory: CadenceHistory? = null
    private var currentRead: SessionReadBridge? = null
    private var constructingRead: SessionReadBridge? = null
    private var currentRecord: SessionProductionRecord? = null
    private var unpublishedOutput: UnpublishedOutput? = null
    private var latestFrame: PublishedFrame? = null
    private var pacingWake: PacingWake? = null
    private var terminalCommitted = false

    init {
        require(creationElapsedRealtimeNanos >= 0L)
    }

    internal fun allocateRecord(configRevision: Long, jpegQuality: Int): SessionProductionRecord {
        require(configRevision > 0L)
        require(jpegQuality in ScreenCaptureParameters.JPEG_QUALITY_RANGE)
        check(!terminalCommitted && !hasMaterializedProduction)
        val record = SessionProductionRecord(this, configRevision, jpegQuality)
        changed()
        return record
    }

    internal fun beginReadConstruction(
        record: SessionProductionRecord,
        input: EncodingInput,
        expectedByteCount: Int,
        returnSink: (SessionReadBridge, CaptureReadResult) -> Unit,
    ): SessionReadBridge {
        check(!terminalCommitted && (constructingRead == null) && (currentRead == null) && (currentRecord == null) && (unpublishedOutput == null))
        require(record.belongsTo(this))
        require(expectedByteCount > 0)
        require(input.byteCount == expectedByteCount)
        val view = input.writableView
        require(view.isExactWritableRgbaCarrier(expectedByteCount))
        val read = SessionReadBridge(record, input, returnSink)
        constructingRead = read
        changed()
        return read
    }

    internal fun clearReadConstruction(expected: SessionReadBridge): SessionReadBridge? {
        if (constructingRead !== expected) return null
        constructingRead = null
        changed()
        return expected
    }

    internal fun prepareFreshGrant(frameRate: FrameRate, nowNanos: Long): FreshGrantDecision {
        if (terminalCommitted) return FreshGrantDecision.InvalidEvidence
        return when (val proposal = PacingCalculator.freshCapture(frameRate, nowNanos, lastFreshGrantNanos, freshCadenceHistory)) {
            PacingDecision.InvalidEvidence -> FreshGrantDecision.InvalidEvidence
            is PacingDecision.Deferred -> FreshGrantDecision.RetainUntil(proposal.eligibleAtNanos)
            is PacingDecision.Eligible -> FreshGrantDecision.Grant(
                owner = this,
                generation = generation,
                grantNanos = nowNanos,
                frameRate = frameRate,
                nextPhase = proposal.nextPhase,
                nextRequiredGapNanos = proposal.nextRequiredGapNanos,
            )
        }
    }

    internal fun commitFreshRead(read: SessionReadBridge, grant: FreshGrantDecision.Grant) {
        check(grant.isCurrent(this) && (constructingRead === read) && (currentRead == null) && (currentRecord == null))
        val cadence = cadenceHistory(grant.frameRate, grant.grantNanos, grant.nextPhase, grant.nextRequiredGapNanos)
        constructingRead = null
        currentRead = read
        currentRecord = read.record
        lastFreshGrantNanos = grant.grantNanos
        freshCadenceHistory = cadence
        pacingWake = null
        changed()
    }

    internal fun clearProduction(expectedRead: SessionReadBridge?, expectedRecord: SessionProductionRecord): SessionProductionRecord? {
        if ((currentRead !== expectedRead) || (currentRecord !== expectedRecord)) return null
        if ((expectedRead != null) && (expectedRead.record !== expectedRecord)) return null
        currentRead = null
        currentRecord = null
        changed()
        return expectedRecord
    }

    internal fun clearReadKeepProduction(expectedRead: SessionReadBridge, expectedRecord: SessionProductionRecord): Boolean {
        if ((currentRead !== expectedRead) || (currentRecord !== expectedRecord)) return false
        currentRead = null
        changed()
        return true
    }

    internal fun completeEncoding(expectedRecord: SessionProductionRecord, payload: ImmutableEncodedPayload): UnpublishedOutput? {
        if ((currentRecord !== expectedRecord) || (unpublishedOutput != null)) return null
        val output = UnpublishedOutput(expectedRecord, payload)
        unpublishedOutput = output
        changed()
        return output
    }

    internal fun discardUnpublishedOutput(expected: UnpublishedOutput) {
        if (unpublishedOutput !== expected) return
        unpublishedOutput = null
        changed()
    }

    internal fun prepareStaleOutput(expectedRevision: Long): StaleOutputCandidate? {
        require(expectedRevision > 0L)
        val record = currentRecord ?: return null
        val output = unpublishedOutput ?: return null
        if ((currentRead != null) || (record.configRevision == expectedRevision) || (output.record !== record)) return null
        return StaleOutputCandidate(record, output)
    }

    internal fun discardStaleOutput(candidate: StaleOutputCandidate) {
        check(candidate.isCurrent(this))
        unpublishedOutput = null
        currentRecord = null
        statsAccumulator.recordStaleWork()
        statsChanged()
        changed()
    }

    internal fun prepareFreshOutput(
        outputInfo: CaptureOutputInfo,
        outputTimestampElapsedRealtimeNanos: Long,
        frameRate: FrameRate,
    ): FreshOutputDecision {
        val unpublishedOutput = this.unpublishedOutput ?: return FreshOutputDecision.Missing
        val record = currentRecord ?: return FreshOutputDecision.InvalidEvidence
        if (unpublishedOutput.record !== record) return FreshOutputDecision.InvalidEvidence
        val read = currentRead
        if ((read != null) && (read.record !== record)) return FreshOutputDecision.InvalidEvidence
        val eligibleProposal = when (val proposal = PacingCalculator.freshOutput(frameRate, outputTimestampElapsedRealtimeNanos, outputCadenceHistory)) {
            is PacingDecision.Deferred -> return FreshOutputDecision.Deferred(proposal.eligibleAtNanos)
            is PacingDecision.Eligible -> proposal
            PacingDecision.InvalidEvidence -> return FreshOutputDecision.InvalidEvidence
        }
        if (lastCommittedOutputSequence == Long.MAX_VALUE) return FreshOutputDecision.SequenceExhausted
        val frame = PublishedFrame(
            payload = unpublishedOutput.payload,
            outputInfo = outputInfo,
            sequence = lastCommittedOutputSequence + 1L,
            outputTimestampElapsedRealtimeNanos = outputTimestampElapsedRealtimeNanos,
        )
        return FreshOutputDecision.Candidate(
            owner = this,
            generation = generation,
            record = record,
            unpublishedOutput = unpublishedOutput,
            read = read,
            previousLatestFrame = latestFrame,
            frame = frame,
            frameRate = frameRate,
            nextPhase = eligibleProposal.nextPhase,
            nextRequiredGapNanos = eligibleProposal.nextRequiredGapNanos,
        )
    }

    internal fun commitFreshOutput(candidate: FreshOutputDecision.Candidate): PublishedFrame? {
        if (!candidate.isCurrent(this)) return null
        val cadence = cadenceHistory(
            candidate.frameRate,
            candidate.frame.outputTimestampElapsedRealtimeNanos,
            candidate.nextPhase,
            candidate.nextRequiredGapNanos,
        )
        lastCommittedOutputSequence = candidate.frame.sequence
        unpublishedOutput = null
        latestFrame = candidate.frame
        outputCadenceHistory = cadence
        currentRead = null
        currentRecord = null
        statsAccumulator.recordProducedFrame(candidate.frame.outputTimestampElapsedRealtimeNanos)
        statsChanged()
        pacingWake = null
        changed()
        return candidate.frame
    }

    internal fun resetForFrameRateChange() {
        lastFreshGrantNanos = null
        freshCadenceHistory = null
        outputCadenceHistory = null
        pacingWake = null
        changed()
    }

    internal val hasMaterializedProduction: Boolean
        get() = (constructingRead != null) || (currentRecord != null) || (unpublishedOutput != null)

    internal fun currentRecordMatches(expected: SessionProductionRecord): Boolean = currentRecord === expected

    internal fun currentReadMatches(expected: SessionReadBridge): Boolean = currentRead === expected

    internal fun prepareCache(): CacheCandidate? = latestFrame?.let { CacheCandidate(this, it) }

    internal fun frameIsLatest(expected: PublishedFrame): Boolean = latestFrame === expected

    internal fun invalidateCache(candidate: CacheCandidate) {
        if (!candidate.isCurrent(this)) return
        latestFrame = null
        changed()
    }

    internal fun armPacingWake(targetNanos: Long, configRevision: Long): PacingWake? {
        require((targetNanos >= 0L) && (configRevision > 0L))
        if (terminalCommitted) return null
        val installed = pacingWake
        if ((installed != null) && (installed.targetNanos <= targetNanos)) return null
        val wake = PacingWake(targetNanos, configRevision)
        pacingWake = wake
        changed()
        return wake
    }

    internal fun currentPacingWake(): PacingWake? = pacingWake

    internal fun clearPacingWake(expected: PacingWake): Boolean {
        if (pacingWake !== expected) return false
        pacingWake = null
        changed()
        return true
    }

    internal fun suppressPacingWake() {
        if (pacingWake == null) return
        pacingWake = null
        changed()
    }

    internal fun recordReadback(durationNanos: Long) {
        if (terminalCommitted) return
        statsAccumulator.recordReadback(durationNanos)
        statsChanged()
        changed()
    }

    internal fun recordEncodeSuccess(durationNanos: Long, encodedByteCount: Int) {
        if (terminalCommitted) return
        statsAccumulator.recordEncodeSuccess(durationNanos, encodedByteCount)
        statsChanged()
        changed()
    }

    internal fun recordStaleWork() = recordStatsMutation(statsAccumulator::recordStaleWork)

    internal fun recordProductionFailure() = recordStatsMutation(statsAccumulator::recordProductionFailure)

    internal fun recordConsumerBusy() = recordStatsMutation(statsAccumulator::recordConsumerBusy)

    internal fun recordCallbackFailure() = recordStatsMutation(statsAccumulator::recordCallbackFailure)

    internal fun prepareStats(publicationNanos: Long): StatsCandidate? {
        // Activity samples the interval from the previous publication (or construction); there is no periodic timer.
        require(publicationNanos >= 0L)
        if (!statsAccumulator.hasUnpublishedChanges || terminalCommitted) return null
        val eligibleAt = Math.addExact(lastStatsPublicationNanos, ElapsedRealtimeClock.NANOS_PER_SECOND)
        if (publicationNanos < eligibleAt) return null
        return StatsCandidate(this, statsGeneration, statsAccumulator.snapshot(), publicationNanos)
    }

    internal fun commitStatsPublication(candidate: StatsCandidate) {
        check(candidate.isCurrent(this))
        statsAccumulator.markPublished()
        lastStatsPublicationNanos = candidate.publicationNanos
        statsChanged()
        changed()
    }

    internal fun prepareTerminal(): TerminalSnapshot {
        check(!terminalCommitted)
        check((constructingRead == null) || ((currentRead == null) && (currentRecord == null) && (unpublishedOutput == null)))
        check((currentRead == null) || (currentRead?.record === currentRecord))
        check((unpublishedOutput == null) || (unpublishedOutput?.record === currentRecord))
        val finalStats = statsAccumulator.snapshot()
        return TerminalSnapshot(
            owner = this,
            generation = generation,
            constructingRead = constructingRead,
            currentRead = currentRead,
            currentRecord = currentRecord,
            unpublishedOutput = unpublishedOutput,
            latestFrame = latestFrame,
            finalStats = finalStats,
        )
    }

    internal fun commitTerminal(candidate: TerminalSnapshot) {
        check(candidate.isCurrent(this))
        constructingRead = null
        currentRead = null
        currentRecord = null
        unpublishedOutput = null
        latestFrame = null
        pacingWake = null
        terminalCommitted = true
        changed()
    }

    private fun cadenceHistory(frameRate: FrameRate, grantNanos: Long, nextPhase: Int?, nextRequiredGapNanos: Long): CadenceHistory? =
        if (frameRate is FrameRate.MaxFps) {
            CadenceHistory(grantNanos, checkNotNull(nextPhase), nextRequiredGapNanos)
        } else {
            null
        }

    private inline fun recordStatsMutation(mutation: () -> Unit) {
        if (terminalCommitted) return
        mutation()
        statsChanged()
        changed()
    }

    private fun statsChanged() {
        statsGeneration += 1L
    }

    private fun changed() {
        generation += 1L
    }
}
