package io.screenstream.engine.internal.session.control

import io.screenstream.engine.internal.settlement.ControlPoisonAuthority
import io.screenstream.engine.internal.settlement.ControlPoisonPublicationDisposition
import io.screenstream.engine.internal.settlement.ControlPoisonClaimOutcome
import io.screenstream.engine.internal.settlement.ControlScheduledTaskRecord
import io.screenstream.engine.internal.settlement.ControlWakeActionPublicationOutcome
import io.screenstream.engine.internal.settlement.ControlWakeOuterRemovalDisposition
import io.screenstream.engine.internal.settlement.ControlWakeScheduleAction
import io.screenstream.engine.internal.settlement.ControlWakeCancellationAction
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.ControlWakeScheduleReturnPublicationOutcome
import io.screenstream.engine.internal.settlement.ControlWakeSuppressionDisposition
import io.screenstream.engine.internal.settlement.FatalThrowablePolicy
import io.screenstream.engine.internal.session.runtime.ControlRuntimeOwnership
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class SessionControlDrainerSetSettledProof internal constructor(
    internal val runtimeOwner: SessionControlRuntimeOwner,
    internal val scheduler: SessionControlScheduler,
    internal val finalCarrier: SessionControlFinalCarrierProof,
    internal val settledRecords: List<SessionControlDrainerResidueSettlement>,
)

/**
 * Mechanical Control-lane owner. It preserves dirty work and scheduler receipts but cannot inspect or mutate
 * Session lifecycle state.
 */
internal class ControlCoordinator internal constructor(
    private val drain: () -> Unit,
    private val failClosed: (Throwable, Boolean) -> Unit,
) : SessionControlSchedulerPort {
    private val dirty = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private val draining = AtomicBoolean(false)
    private val emergencyMode = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val finalTurnGate = ReentrantLock(false)
    private val finalTurnSeal = java.util.concurrent.atomic.AtomicReference<SessionControlDrainerSetSettledProof?>(null)
    private val poison = ControlPoisonAuthority()
    private val startupRecord = SessionControlStartupRecord()
    private val startupCleanup = SessionControlStartupCleanupAction(startupRecord)
    private val runtimeOwner = SessionControlRuntimeOwner()
    private val records: Array<SessionControlDrainerTaskRecord> = arrayOf(
        SessionControlDrainerTaskRecord(poison, Runnable(::runDrain)),
        SessionControlDrainerTaskRecord(poison, Runnable(::runDrain)),
    )

    @Volatile
    private var scheduler: SessionControlScheduler? = null

    internal val owner: SessionControlRuntimeOwner
        get() = runtimeOwner

    internal fun bindCleanupOwner(owner: ControlRuntimeOwnership): SessionControlTerminationReceipt =
        runtimeOwner.terminationRoot.exactReceipt.also { receipt ->
            check(receipt.bindCleanupOwner(owner))
        }

    internal fun start(): ControlStartupResult {
        if (scheduler != null) return ControlStartupResult.AlreadyStarted
        val created = try {
            SessionControlScheduler(runtimeOwner, poison, startupRecord, startupCleanup, this)
        } catch (raw: Throwable) {
            startupRecord.publishConstructionFailure(raw)
            return ControlStartupResult.Failed(raw)
        }
        scheduler = created
        return when (created.prestart()) {
            SessionControlPrestartOutcome.Ready -> when (created.transferReadyRuntimeOwner()) {
                SessionControlRuntimeTransferOutcome.Transferred -> ControlStartupResult.Ready
                SessionControlRuntimeTransferOutcome.NotEligible -> ControlStartupResult.Failed(
                    IllegalStateException("Control runtime ownership transfer failed"),
                )
            }
            SessionControlPrestartOutcome.CleanupRequired -> {
                startupCleanup.execute()
                ControlStartupResult.Failed(
                    startupRecord.startupFailure ?: IllegalStateException("Control worker did not start"),
                )
            }
            SessionControlPrestartOutcome.NotEligible -> ControlStartupResult.Failed(
                IllegalStateException("Control prestart was not eligible"),
            )
        }
    }

    internal fun signal() {
        val admitted = finalTurnGate.withLock {
            if (finalTurnSeal.get() != null) return@withLock false
            dirty.set(true)
            true
        }
        if (!admitted) return
        if (scheduler == null) {
            emergencyMode.set(true)
            drainStable()
            return
        }
        if (terminationReceipt() != null) {
            dirty.set(false)
            return
        }
        if (emergencyMode.get()) {
            drainStable()
            return
        }
        if (scheduled.compareAndSet(false, true)) submitDrain()
    }

    internal fun requestFinalShutdown(): Boolean {
        val exact = scheduler ?: return false
        val action = exact.finalShutdownAction
        if (action.claim() != SessionControlFinalShutdownClaimOutcome.Claimed) return false
        action.execute()
        return true
    }

    internal fun drainerSetSettledProof(): SessionControlDrainerSetSettledProof? {
        val exactScheduler = scheduler ?: return null
        if (!runtimeOwner.ownsThread(Thread.currentThread()) || terminationReceipt() != null ||
            !draining.get() || !scheduled.get() || dirty.get()
        ) {
            return null
        }
        val currentGeneration = generation.get()
        if (currentGeneration <= 0L) return null
        val carrierRecord = records[((currentGeneration - 1L) and 1L).toInt()]
        val carrier = carrierRecord.finalCarrierProof(currentGeneration) ?: return null
        val settled = ArrayList<SessionControlDrainerResidueSettlement>(records.size - 1)
        records.forEach { record ->
            if (record !== carrierRecord) settled += record.residueSettlement() ?: return null
        }
        return SessionControlDrainerSetSettledProof(runtimeOwner, exactScheduler, carrier, settled)
    }

    internal fun acceptsDrainerSetSettledProof(proof: SessionControlDrainerSetSettledProof): Boolean {
        val exactScheduler = scheduler ?: return false
        if (proof.runtimeOwner !== runtimeOwner || proof.scheduler !== exactScheduler ||
            !runtimeOwner.ownsThread(Thread.currentThread()) || terminationReceipt() != null ||
            !draining.get() || !scheduled.get() || dirty.get()
        ) {
            return false
        }
        val currentGeneration = generation.get()
        val carrierRecord = records[((currentGeneration - 1L) and 1L).toInt()]
        if (proof.finalCarrier.record !== carrierRecord ||
            !carrierRecord.acceptsFinalCarrierProof(proof.finalCarrier)
        ) {
            return false
        }
        val otherRecords = records.filter { it !== carrierRecord }
        return proof.settledRecords.size == otherRecords.size &&
                proof.settledRecords.indices.all { index ->
                    val exactRecord = otherRecords[index]
                    val settlement = proof.settledRecords[index]
                    settlement.record === exactRecord && exactRecord.residueSettlement()?.let {
                        it.generation == settlement.generation &&
                                it.submissionDisposition == settlement.submissionDisposition &&
                                it.physicalDisposition == settlement.physicalDisposition &&
                                it.taskDisposition == settlement.taskDisposition && it.afterExecuteApplied
                    } == true
                }
    }

    internal fun sealFinalTurn(proof: SessionControlDrainerSetSettledProof): Boolean {
        if (!acceptsDrainerSetSettledProof(proof)) return false
        return finalTurnGate.withLock {
            if (dirty.get()) return@withLock false
            val existing = finalTurnSeal.get()
            existing === proof || existing == null && finalTurnSeal.compareAndSet(null, proof)
        }
    }

    /** Executes one authority-claimed wake action outside every Session/settlement gate. */
    internal fun scheduleWake(action: ControlWakeScheduleAction, nowNanos: Long): Boolean {
        val exactScheduler = scheduler ?: return false
        if (nowNanos < 0L || action.claimInvocation(poison) != ControlPoisonClaimOutcome.Admitted ||
            action.markInvocationStarted(runtimeOwner) != ControlWakeActionPublicationOutcome.Published
        ) {
            return false
        }
        val delayNanos = if (action.dueNanos <= nowNanos) 0L else action.dueNanos - nowNanos
        val future = try {
            exactScheduler.schedule(action.runner, delayNanos, TimeUnit.NANOSECONDS)
        } catch (raw: Throwable) {
            action.publishInvocationFailure(raw)
            signal()
            if (FatalThrowablePolicy.isDirectFatal(raw)) throw raw
            return false
        }
        val outerWrapper = future as? Runnable ?: run {
            val raw = IllegalStateException("Control scheduler returned a non-Runnable Future")
            action.publishInvocationFailure(raw)
            signal()
            return false
        }
        return when (action.publishReturned(future, outerWrapper)) {
            ControlWakeScheduleReturnPublicationOutcome.Accepted -> true
            ControlWakeScheduleReturnPublicationOutcome.Detached -> {
                settleDetachedWake(exactScheduler, action, future, outerWrapper)
                true
            }
            ControlWakeScheduleReturnPublicationOutcome.NotEligible -> false
        }
    }

    private fun settleDetachedWake(
        exactScheduler: SessionControlScheduler,
        action: ControlWakeScheduleAction,
        future: java.util.concurrent.Future<*>,
        outerWrapper: Runnable,
    ) {
        if (action.claimDetachedCancelInvocation(poison) != ControlPoisonClaimOutcome.Admitted) return
        val cancelReturned: Boolean?
        val cancelFailure: Throwable?
        try {
            cancelReturned = future.cancel(false)
            cancelFailure = null
        } catch (raw: Throwable) {
            cancelReturned = null
            cancelFailure = raw
        }
        val suppression = if (cancelReturned == true && action.trySuppressDetached()) {
            ControlWakeSuppressionDisposition.Succeeded
        } else if (cancelReturned == true) {
            ControlWakeSuppressionDisposition.Failed
        } else {
            ControlWakeSuppressionDisposition.NotAttempted
        }
        if (cancelFailure != null && FatalThrowablePolicy.isDirectFatal(cancelFailure)) {
            action.publishDetachedSettlement(
                null,
                cancelFailure,
                ControlWakeSuppressionDisposition.NotAttempted,
                ControlWakeOuterRemovalDisposition.NotAttempted,
                null,
                null,
            )
            FatalThrowablePolicy.rethrow(cancelFailure)
        }
        val removeClaim = action.claimDetachedRemoveInvocation(poison)
        var removalDisposition = ControlWakeOuterRemovalDisposition.PoisonFenced
        var removalReturned: Boolean? = null
        var removalFailure: Throwable? = null
        if (removeClaim == ControlPoisonClaimOutcome.Admitted) {
            try {
                removalReturned = exactScheduler.remove(outerWrapper)
                removalDisposition = ControlWakeOuterRemovalDisposition.Returned
            } catch (raw: Throwable) {
                removalFailure = raw
                removalDisposition = ControlWakeOuterRemovalDisposition.Thrown
            }
        }
        action.publishDetachedSettlement(
            cancelReturned,
            cancelFailure,
            suppression,
            removalDisposition,
            removalReturned,
            removalFailure,
        )
        signal()
        if (removalFailure != null && FatalThrowablePolicy.isDirectFatal(removalFailure)) {
            FatalThrowablePolicy.rethrow(removalFailure)
        }
    }

    /** Executes one authority-claimed cancellation outside every Session/settlement gate. */
    internal fun cancelWake(link: ControlWakeLink, action: ControlWakeCancellationAction): Boolean {
        val exactScheduler = scheduler ?: return false
        val future = action.future ?: return false
        val outerWrapper = action.outerWrapper ?: return false
        if (action.claimCancelInvocation(poison) != ControlPoisonClaimOutcome.Admitted) return false
        val cancelReturned: Boolean?
        val cancelFailure: Throwable?
        try {
            cancelReturned = future.cancel(false)
            cancelFailure = null
        } catch (raw: Throwable) {
            cancelReturned = null
            cancelFailure = raw
        }
        val suppression = if (cancelReturned == true && action.trySuppress()) {
            ControlWakeSuppressionDisposition.Succeeded
        } else if (cancelReturned == true) {
            ControlWakeSuppressionDisposition.Failed
        } else {
            ControlWakeSuppressionDisposition.NotAttempted
        }
        if (cancelFailure != null && FatalThrowablePolicy.isDirectFatal(cancelFailure)) {
            link.publishCancellation(
                action,
                null,
                cancelFailure,
                ControlWakeSuppressionDisposition.NotAttempted,
                ControlWakeOuterRemovalDisposition.NotAttempted,
                null,
                null,
            )
            FatalThrowablePolicy.rethrow(cancelFailure)
        }
        val removeClaim = action.claimRemoveInvocation(poison)
        var removalDisposition = ControlWakeOuterRemovalDisposition.PoisonFenced
        var removalReturned: Boolean? = null
        var removalFailure: Throwable? = null
        if (removeClaim == ControlPoisonClaimOutcome.Admitted) {
            try {
                removalReturned = exactScheduler.remove(outerWrapper)
                removalDisposition = ControlWakeOuterRemovalDisposition.Returned
            } catch (raw: Throwable) {
                removalFailure = raw
                removalDisposition = ControlWakeOuterRemovalDisposition.Thrown
            }
        }
        val published = link.publishCancellation(
            action,
            cancelReturned,
            cancelFailure,
            suppression,
            removalDisposition,
            removalReturned,
            removalFailure,
        )
        signal()
        if (removalFailure != null && FatalThrowablePolicy.isDirectFatal(removalFailure)) {
            FatalThrowablePolicy.rethrow(removalFailure)
        }
        return published
    }

    internal fun terminationReceipt(): SessionControlTerminationReceipt? =
        runtimeOwner.terminationRoot.observeReleasedReceipt()

    private fun runDrain() {
        drainStable()
        scheduled.set(false)
        if (dirty.get()) signal()
    }

    private fun drainStable() {
        if (!draining.compareAndSet(false, true)) return
        try {
            do {
                dirty.set(false)
                drain()
            } while (dirty.get())
        } finally {
            draining.set(false)
        }
        if (dirty.get()) drainStable()
    }

    private fun submitDrain() {
        val exactScheduler = scheduler
        if (exactScheduler == null) {
            scheduled.set(false)
            return
        }
        val next = generation.incrementAndGet()
        if (next <= 0L) {
            emergencyDrain(CONTROL_GENERATION_EXHAUSTED, false)
            return
        }
        val record = records[((next - 1L) and 1L).toInt()]
        if (!record.prepare(next)) {
            emergencyDrain(CONTROL_DRAINER_REUSE_FAILED, false)
            return
        }
        when (record.claimSubmissionInvocation(next)) {
            SessionControlDrainerInvocationClaimOutcome.Admitted -> Unit
            SessionControlDrainerInvocationClaimOutcome.PoisonFenced,
            SessionControlDrainerInvocationClaimOutcome.ClaimExhausted,
            SessionControlDrainerInvocationClaimOutcome.NotEligible,
                -> {
                emergencyDrain(CONTROL_DRAINER_ADMISSION_FAILED, false)
                return
            }
        }
        try {
            exactScheduler.execute(record.runner)
            record.publishSubmissionAccepted(next)
        } catch (raw: Throwable) {
            record.publishSubmissionFailure(next, raw)
            val direct = FatalThrowablePolicy.isDirectFatal(raw)
            poison.publish(raw)
            emergencyDrain(raw, direct)
            if (direct) throw raw
        }
    }

    override fun onControlTaskOrdinaryEscaped(record: ControlScheduledTaskRecord, raw: Throwable) {
        poison.publish(raw)
        emergencyDrain(raw, false)
    }

    override fun onControlTaskDirectFatal(record: ControlScheduledTaskRecord, raw: Throwable) {
        when (poison.publish(raw)) {
            ControlPoisonPublicationDisposition.Published,
            ControlPoisonPublicationDisposition.AlreadyPublished,
            ControlPoisonPublicationDisposition.ClaimExhausted,
                -> emergencyDrain(raw, true)
        }
    }

    private fun emergencyDrain(raw: Throwable, direct: Boolean) {
        poison.publish(raw)
        emergencyMode.set(true)
        scheduled.set(false)
        dirty.set(true)
        failClosed(raw, direct)
        drainStable()
    }

    private companion object {
        private val CONTROL_GENERATION_EXHAUSTED = IllegalStateException("Control drainer generation exhausted")
        private val CONTROL_DRAINER_REUSE_FAILED = IllegalStateException("Control drainer record reuse failed")
        private val CONTROL_DRAINER_ADMISSION_FAILED = IllegalStateException("Control drainer admission failed")
    }
}

internal sealed interface ControlStartupResult {
    internal object Ready : ControlStartupResult
    internal object AlreadyStarted : ControlStartupResult
    internal class Failed internal constructor(internal val cause: Throwable) : ControlStartupResult
}
