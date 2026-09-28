package io.screenstream.streaming.foreground

import android.app.Service
import android.util.Log
import androidx.core.app.ServiceCompat
import io.screenstream.streaming.module.StreamingModuleApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Foreground operations for one attached module launch and exact Android Service object. The
 * instance manager supplies its lock and surviving scope; no work is started by construction.
 */
internal class ServiceForegroundController(
    private val service: Service,
    override val instanceId: StreamingModuleApi.InstanceId,
    private val notificationId: Int,
    private val stateLock: Any,
    private val managerScope: CoroutineScope,
    private val isInstanceCurrent: () -> Boolean,
    private val requestShutdown: () -> Unit,
) : ForegroundControl {
    /** An exact Android operation remains current until its completed worker records the outcome. */
    private class Call {
        var admitted: Boolean = false
        var returned: Boolean = false
        var failure: Throwable? = null
    }

    /** One reservation; a failed release pins this owner until its host disappears. */
    private inner class ReservationOwner(val request: ForegroundControl.Request) {
        val reservation: ForegroundControl.Reservation = object : ForegroundControl.Reservation {
            override fun isCurrent(): Boolean = isReservationCurrent(this@ReservationOwner)
            override suspend fun promote(): ForegroundControl.PromotionResult =
                promoteReservation(this@ReservationOwner).await()

            override suspend fun release(): ForegroundControl.ReleaseResult =
                requestRelease(this@ReservationOwner).await()
        }
        var currentCall: Call? = null
        var promotionResult: CompletableDeferred<ForegroundControl.PromotionResult>? = null
        var promotionAdmitted: Boolean = false
        var releaseResult: CompletableDeferred<ForegroundControl.ReleaseResult>? = null

        /** Terminal evidence is recorded before the public waiter is resumed. */
        var terminalReleaseOutcome: ForegroundControl.ReleaseResult? = null
    }

    /** Mutated only under the instance manager's shared [stateLock]. */
    private var admissionOpen: Boolean = true
    private var hostAlive: Boolean = true
    private var owner: ReservationOwner? = null

    override fun isOpen(): Boolean = synchronized(stateLock) { admissionOpen && hostAlive }

    override fun reserve(request: ForegroundControl.Request): ForegroundControl.ReserveResult {
        if (request.serviceTypes <= 0 || !isInstanceCurrent()) return ForegroundControl.ReserveResult.Rejected
        return synchronized(stateLock) {
            when {
                !admissionOpen || !hostAlive -> ForegroundControl.ReserveResult.Rejected
                owner != null -> ForegroundControl.ReserveResult.Busy
                else -> {
                    val reservedOwner = ReservationOwner(request)
                    owner = reservedOwner
                    ForegroundControl.ReserveResult.Reserved(reservedOwner.reservation)
                }
            }
        }
    }

    /** Caller holds [stateLock]. The first close consumes any already terminal release failure. */
    internal fun closeAdmissionLocked(): Boolean {
        if (!admissionOpen) return false
        admissionOpen = false
        val outcome = owner?.terminalReleaseOutcome
        return outcome is ForegroundControl.ReleaseResult.Failed || outcome == ForegroundControl.ReleaseResult.Unconfirmed
    }

    /** Caller holds [stateLock]; host loss forbids new Android calls but does not settle admitted work. */
    internal fun markHostDestroyedLocked() {
        hostAlive = false
    }

    /** Final cleanup result after the current owner's release reaches terminal public-call evidence. */
    internal suspend fun releaseAndAwaitCleanup(): Boolean {
        val currentOwner = synchronized(stateLock) { owner } ?: return true
        val outcome = requestRelease(currentOwner).await()
        return outcome !is ForegroundControl.ReleaseResult.Failed && outcome != ForegroundControl.ReleaseResult.Unconfirmed
    }

    private fun isReservationCurrent(currentOwner: ReservationOwner): Boolean = synchronized(stateLock) {
        owner === currentOwner && currentOwner.releaseResult == null && admissionOpen && hostAlive
    }

    private fun promoteReservation(currentOwner: ReservationOwner): CompletableDeferred<ForegroundControl.PromotionResult> {
        var call: Call? = null
        var immediateRejection = false
        val result = synchronized(stateLock) {
            currentOwner.promotionResult ?: CompletableDeferred<ForegroundControl.PromotionResult>().also { newResult ->
                currentOwner.promotionResult = newResult
                if (owner !== currentOwner || !hostAlive || !admissionOpen || currentOwner.releaseResult != null) {
                    immediateRejection = true
                } else {
                    call = Call().also { currentOwner.currentCall = it }
                }
            }
        }
        if (immediateRejection) result.complete(ForegroundControl.PromotionResult.Rejected)
        call?.let { startPromotion(currentOwner, it) }
        return result
    }

    private fun requestRelease(currentOwner: ReservationOwner): CompletableDeferred<ForegroundControl.ReleaseResult> {
        val result = synchronized(stateLock) {
            currentOwner.releaseResult ?: CompletableDeferred<ForegroundControl.ReleaseResult>().also {
                currentOwner.releaseResult = it
            }
        }
        advanceRelease(currentOwner)
        return result
    }

    private fun advanceRelease(currentOwner: ReservationOwner) {
        val call = synchronized(stateLock) {
            if (currentOwner.releaseResult == null) return
            if (currentOwner.terminalReleaseOutcome != null || currentOwner.currentCall != null) return
            Call().also { currentOwner.currentCall = it }
        }
        startRelease(currentOwner, call)
    }

    private fun startPromotion(currentOwner: ReservationOwner, call: Call) {
        val job = managerScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runPromotion(currentOwner, call)
        }
        job.invokeOnCompletion { cause -> finishPromotion(currentOwner, call, cause) }
        job.start()
    }

    private fun runPromotion(currentOwner: ReservationOwner, call: Call) {
        val admitted = synchronized(stateLock) {
            val canAdmit = currentOwner.currentCall === call && owner === currentOwner && hostAlive &&
                    currentOwner.releaseResult == null && admissionOpen
            if (canAdmit) {
                call.admitted = true
                currentOwner.promotionAdmitted = true
            }
            canAdmit
        }
        if (!admitted) return

        try {
            ServiceCompat.startForeground(
                service,
                notificationId,
                currentOwner.request.notification,
                currentOwner.request.serviceTypes,
            )
            synchronized(stateLock) { call.returned = true }
        } catch (failure: Throwable) {
            synchronized(stateLock) { call.failure = failure }
            Log.e(TAG, "Android foreground promotion failed", failure)
        }
    }

    private fun finishPromotion(currentOwner: ReservationOwner, call: Call, cause: Throwable?) {
        val outcome: ForegroundControl.PromotionResult
        val result: CompletableDeferred<ForegroundControl.PromotionResult>
        val shouldAdvanceRelease: Boolean
        synchronized(stateLock) {
            if (currentOwner.currentCall !== call) return
            currentOwner.currentCall = null
            outcome = when {
                call.failure != null -> ForegroundControl.PromotionResult.Failed(checkNotNull(call.failure))
                call.returned -> ForegroundControl.PromotionResult.ApiCompleted
                call.admitted -> ForegroundControl.PromotionResult.Failed(
                    cause ?: IllegalStateException("Foreground promotion did not return"),
                )

                else -> ForegroundControl.PromotionResult.Rejected
            }
            result = checkNotNull(currentOwner.promotionResult)
            shouldAdvanceRelease = currentOwner.releaseResult != null
        }

        // A release requested during promotion begins before an unconfined waiter sees its result.
        if (shouldAdvanceRelease) advanceRelease(currentOwner)
        result.complete(outcome)
    }

    private fun startRelease(currentOwner: ReservationOwner, call: Call) {
        val job = managerScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runRelease(currentOwner, call)
        }
        job.invokeOnCompletion { cause -> finishRelease(currentOwner, call, cause) }
        job.start()
    }

    private fun runRelease(currentOwner: ReservationOwner, call: Call) {
        val admitted = synchronized(stateLock) {
            val canAdmit = currentOwner.currentCall === call && owner === currentOwner && hostAlive &&
                    currentOwner.releaseResult != null && currentOwner.promotionAdmitted
            if (canAdmit) call.admitted = true
            canAdmit
        }
        if (!admitted) return

        try {
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            synchronized(stateLock) { call.returned = true }
        } catch (failure: Throwable) {
            synchronized(stateLock) { call.failure = failure }
            Log.e(TAG, "Android foreground release failed", failure)
        }
    }

    private fun finishRelease(currentOwner: ReservationOwner, call: Call, cause: Throwable?) {
        val outcome: ForegroundControl.ReleaseResult
        val result: CompletableDeferred<ForegroundControl.ReleaseResult>
        val shouldShutdown: Boolean
        synchronized(stateLock) {
            if (currentOwner.currentCall !== call) return
            currentOwner.currentCall = null
            outcome = when {
                call.failure != null -> ForegroundControl.ReleaseResult.Failed(checkNotNull(call.failure))
                call.returned -> ForegroundControl.ReleaseResult.ApiCompleted
                !call.admitted && !currentOwner.promotionAdmitted -> ForegroundControl.ReleaseResult.NotRequired
                !call.admitted && !hostAlive -> ForegroundControl.ReleaseResult.Unconfirmed
                else -> ForegroundControl.ReleaseResult.Failed(
                    cause ?: IllegalStateException("Foreground release did not return"),
                )
            }
            result = checkNotNull(currentOwner.releaseResult)
            check(currentOwner.terminalReleaseOutcome == null)
            currentOwner.terminalReleaseOutcome = outcome
            val failed = outcome is ForegroundControl.ReleaseResult.Failed || outcome == ForegroundControl.ReleaseResult.Unconfirmed
            if (!failed && owner === currentOwner) owner = null
            shouldShutdown = failed && admissionOpen
        }
        if (shouldShutdown) requestShutdown()
        result.complete(outcome)
    }

    private companion object {
        private const val TAG: String = "ServiceForegroundController"
    }
}
