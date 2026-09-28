package io.screenstream.streaming.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResult
import io.screenstream.streaming.foreground.ForegroundControl
import io.screenstream.streaming.module.StreamingModuleApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.core.annotation.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.uuid.Uuid

/**
 * Process owner for one screen-consent and projection acquisition at a time. An accepted operation
 * keeps its own worker and cleanup responsibility when the caller stops waiting; only an atomic
 * final claim transfers projection and foreground ownership to the module's setup record.
 */
@Singleton(binds = [ScreenCaptureAcquisition::class])
internal class ScreenCaptureAcquisitionImpl(
    context: Context,
    private val consentBridge: ScreenCaptureConsentBridge,
) : ScreenCaptureAcquisition {
    private enum class Phase { AWAITING_CONSENT, PRODUCING, OFFERED, ROLLING_BACK, TRANSFERRED, FINISHED }

    private class ConsentCarrier(
        val instanceId: StreamingModuleApi.InstanceId,
        val resultData: Intent,
    ) : ScreenCaptureAcquisition.ReusableConsent

    private class Operation(
        val attempt: StreamingModuleApi.CaptureAttemptId,
        val request: ForegroundControl.Request,
        val foregroundControl: ForegroundControl,
        val isAttemptCurrent: () -> Boolean,
        val reports: ScreenCaptureAcquisition.Reports,
        initialConsent: ConsentCarrier?,
    ) {
        var phase: Phase = if (initialConsent == null) Phase.AWAITING_CONSENT else Phase.PRODUCING
        var revoked: Boolean = false
        var continuation: CancellableContinuation<ScreenCaptureAcquisition.Result>? = null
        var settled: Boolean = false
        var settledResult: ScreenCaptureAcquisition.Result? = null
        var producerStarted: Boolean = false
        var requestKey: String? = null
        var resultData: Intent? = initialConsent?.resultData
        var reusableConsent: ConsentCarrier? = initialConsent
        var reservation: ForegroundControl.Reservation? = null
        var projection: MediaProjection? = null
        var offered: ScreenCaptureAcquisition.Result.Acquired? = null
        var primaryFailure: ScreenCaptureAcquisition.Result.Failed? = null
        var publicResult: ScreenCaptureAcquisition.Result? = null
    }

    private val projectionManager: MediaProjectionManager =
        checkNotNull(context.applicationContext.getSystemService(MediaProjectionManager::class.java))
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock: Any = Any()
    private var activeOperation: Operation? = null

    override suspend fun acquire(
        attempt: StreamingModuleApi.CaptureAttemptId,
        request: ForegroundControl.Request,
        foregroundControl: ForegroundControl,
        isAttemptCurrent: () -> Boolean,
        reports: ScreenCaptureAcquisition.Reports,
        reusableConsent: ScreenCaptureAcquisition.ReusableConsent?,
    ): ScreenCaptureAcquisition.Result {
        if (attempt.instanceId != foregroundControl.instanceId || !isAttemptCurrent() || !foregroundControl.isOpen()) {
            return ScreenCaptureAcquisition.Result.Rejected
        }

        val priorConsent = (reusableConsent as? ConsentCarrier)?.takeIf {
            Build.VERSION.SDK_INT in Build.VERSION_CODES.N..Build.VERSION_CODES.TIRAMISU &&
                    it.instanceId == attempt.instanceId
        }
        val operation = synchronized(lock) {
            if (activeOperation != null) null else Operation(
                attempt = attempt,
                request = request,
                foregroundControl = foregroundControl,
                isAttemptCurrent = isAttemptCurrent,
                reports = reports,
                initialConsent = priorConsent,
            ).also { activeOperation = it }
        } ?: return ScreenCaptureAcquisition.Result.Busy
        val result = try {
            suspendCancellableCoroutine { continuation: CancellableContinuation<ScreenCaptureAcquisition.Result> ->
                val settled = synchronized(lock) {
                    operation.continuation = continuation
                    operation.settled to operation.settledResult
                }
                continuation.invokeOnCancellation { revoke(operation) }
                if (settled.first) {
                    deliverRollbackResult(continuation, settled.second)
                    return@suspendCancellableCoroutine
                }
                if (priorConsent != null) {
                    if (isProducing(operation)) reports.reportConsentAccepted()
                    synchronized(lock) { operation.producerStarted = true }
                    scope.launch(Dispatchers.IO) { produce(operation) }
                } else {
                    scope.launch(Dispatchers.Main) { requestFreshConsent(operation) }
                }
            }
        } catch (cause: CancellationException) {
            revoke(operation)
            if (priorConsent != null && synchronized(lock) { !operation.producerStarted }) {
                finishProduction(operation, null)
            }
            throw cause
        }

        if (result is ScreenCaptureAcquisition.Result.Acquired && !claim(operation, result)) {
            throw CancellationException("Screen capture acquisition was revoked before transfer")
        }
        return result
    }

    override fun cancelIfAwaitingConsent(attempt: StreamingModuleApi.CaptureAttemptId): Boolean {
        val operation = synchronized(lock) {
            activeOperation?.takeIf { it.attempt == attempt && it.phase == Phase.AWAITING_CONSENT }
        } ?: return false
        return revoke(operation, awaitingOnly = true)
    }

    private fun isProducing(operation: Operation): Boolean = synchronized(lock) {
        activeOperation === operation && operation.phase == Phase.PRODUCING && !operation.revoked
    }

    private fun requestFreshConsent(operation: Operation) {
        if (!isAwaitingConsent(operation)) return
        if (!operation.isAttemptCurrent()) {
            revoke(operation)
            return
        }

        val request = try {
            projectionManager.createScreenCaptureIntent()
        } catch (cause: Throwable) {
            failAwaitingConsent(operation, cause)
            return
        }
        val key = "screen-capture:${operation.attempt.instanceId.uuid}:${operation.attempt.uuid}:${Uuid.random()}"
        val canLaunch = synchronized(lock) {
            if (activeOperation !== operation || operation.phase != Phase.AWAITING_CONSENT || operation.revoked) {
                false
            } else {
                operation.requestKey = key
                true
            }
        }
        if (!canLaunch) return

        try {
            val requestAccepted = consentBridge.requestConsent(key, request) { outcome: Result<ActivityResult> ->
                outcome.fold(
                    onSuccess = { result -> onConsentResult(operation, key, result.resultCode, result.data) },
                    onFailure = { cause -> failAwaitingConsent(operation, cause) },
                )
            }
            if (!requestAccepted) {
                failAwaitingConsent(operation, IllegalStateException("No resumed Activity for screen consent"))
            }
            if (!requestAccepted || !isAwaitingConsent(operation)) consentBridge.abandon(key)
        } catch (cause: Throwable) {
            consentBridge.abandon(key)
            failAwaitingConsent(operation, cause)
        }
    }

    private fun isAwaitingConsent(operation: Operation): Boolean = synchronized(lock) {
        activeOperation === operation && operation.phase == Phase.AWAITING_CONSENT && !operation.revoked
    }

    private fun onConsentResult(operation: Operation, key: String, resultCode: Int, data: Intent?) {
        if (!isAwaitingConsent(operation) || synchronized(lock) { operation.requestKey != key }) return
        if (resultCode != Activity.RESULT_OK) {
            finishAwaitingConsent(operation, ScreenCaptureAcquisition.Result.Declined, null)
            return
        }
        if (data == null) {
            failAwaitingConsent(operation, IllegalStateException("Screen consent returned no result data"))
            return
        }
        if (!operation.isAttemptCurrent()) {
            revoke(operation)
            return
        }

        val resultData = try {
            Intent(data)
        } catch (cause: Throwable) {
            failAwaitingConsent(operation, cause)
            return
        }
        val accepted = synchronized(lock) {
            if (activeOperation !== operation || operation.phase != Phase.AWAITING_CONSENT || operation.revoked || operation.requestKey != key) {
                false
            } else {
                operation.phase = Phase.PRODUCING
                operation.resultData = resultData
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    operation.reusableConsent = ConsentCarrier(operation.attempt.instanceId, resultData)
                }
                true
            }
        }
        if (accepted) {
            operation.reports.reportConsentAccepted()
            scope.launch(Dispatchers.IO) { produce(operation) }
        }
    }

    private fun failAwaitingConsent(operation: Operation, cause: Throwable) {
        val failure = ScreenCaptureAcquisition.Result.Failed(ScreenCaptureAcquisition.Stage.CONSENT, cause)
        finishAwaitingConsent(operation, failure, failure)
    }

    private fun finishAwaitingConsent(
        operation: Operation,
        result: ScreenCaptureAcquisition.Result,
        failure: ScreenCaptureAcquisition.Result.Failed?,
    ) {
        val shouldRollback = synchronized(lock) {
            if (activeOperation !== operation || operation.phase != Phase.AWAITING_CONSENT || operation.revoked) {
                false
            } else {
                operation.phase = Phase.ROLLING_BACK
                operation.primaryFailure = failure
                operation.publicResult = result
                true
            }
        }
        if (shouldRollback) startRollback(operation)
    }

    private suspend fun produce(operation: Operation) {
        if (!isProducing(operation)) {
            finishProduction(operation, null)
            return
        }
        if (!operation.isAttemptCurrent()) {
            finishProduction(operation, failure(ScreenCaptureAcquisition.Stage.FOREGROUND, "Capture attempt became stale"))
            return
        }

        val reservation = try {
            when (val reserved = operation.foregroundControl.reserve(operation.request)) {
                is ForegroundControl.ReserveResult.Reserved -> reserved.reservation
                ForegroundControl.ReserveResult.Busy -> {
                    finishProduction(operation, failure(ScreenCaptureAcquisition.Stage.FOREGROUND, "Foreground host is busy"))
                    return
                }

                ForegroundControl.ReserveResult.Rejected -> {
                    finishProduction(operation, failure(ScreenCaptureAcquisition.Stage.FOREGROUND, "Foreground reservation was rejected"))
                    return
                }
            }
        } catch (cause: Throwable) {
            finishProduction(operation, ScreenCaptureAcquisition.Result.Failed(ScreenCaptureAcquisition.Stage.FOREGROUND, cause))
            return
        }
        synchronized(lock) { operation.reservation = reservation }
        if (isRevoked(operation)) {
            finishProduction(operation, null)
            return
        }
        if (!reservation.isCurrent() || !operation.isAttemptCurrent()) {
            finishProduction(operation, failure(ScreenCaptureAcquisition.Stage.FOREGROUND, "Foreground owner or capture attempt became stale"))
            return
        }

        val promotion = try {
            reservation.promote()
        } catch (cause: Throwable) {
            finishProduction(operation, ScreenCaptureAcquisition.Result.Failed(ScreenCaptureAcquisition.Stage.FOREGROUND, cause))
            return
        }
        if (promotion is ForegroundControl.PromotionResult.Failed) {
            finishProduction(operation, ScreenCaptureAcquisition.Result.Failed(ScreenCaptureAcquisition.Stage.FOREGROUND, promotion.cause))
            return
        }
        if (isRevoked(operation)) {
            finishProduction(operation, null)
            return
        }
        when (promotion) {
            ForegroundControl.PromotionResult.ApiCompleted -> Unit
            ForegroundControl.PromotionResult.Rejected -> {
                finishProduction(operation, failure(ScreenCaptureAcquisition.Stage.FOREGROUND, "Foreground promotion was rejected"))
                return
            }

            is ForegroundControl.PromotionResult.Failed -> error("Handled above")
        }
        if (!reservation.isCurrent() || !operation.isAttemptCurrent()) {
            finishProduction(operation, failure(ScreenCaptureAcquisition.Stage.FOREGROUND, "Foreground owner or capture attempt became stale"))
            return
        }

        val resultData = synchronized(lock) { operation.resultData }
        if (resultData == null) {
            finishProduction(operation, failure(ScreenCaptureAcquisition.Stage.CONSENT, "Accepted screen consent was lost"))
            return
        }
        val projection = try {
            projectionManager.getMediaProjection(Activity.RESULT_OK, resultData)
                ?: throw IllegalStateException("MediaProjectionManager returned no projection")
        } catch (cause: Throwable) {
            finishProduction(operation, ScreenCaptureAcquisition.Result.Failed(ScreenCaptureAcquisition.Stage.PROJECTION, cause))
            return
        }
        synchronized(lock) { operation.projection = projection }
        if (isRevoked(operation)) {
            finishProduction(operation, null)
            return
        }
        if (!reservation.isCurrent() || !operation.isAttemptCurrent()) {
            finishProduction(operation, failure(ScreenCaptureAcquisition.Stage.PROJECTION, "Projection owner or capture attempt became stale"))
            return
        }
        offer(operation, projection, reservation)
    }

    private fun failure(stage: ScreenCaptureAcquisition.Stage, message: String): ScreenCaptureAcquisition.Result.Failed =
        ScreenCaptureAcquisition.Result.Failed(stage, IllegalStateException(message))

    private fun isRevoked(operation: Operation): Boolean = synchronized(lock) { operation.revoked }

    private fun offer(operation: Operation, projection: MediaProjection, reservation: ForegroundControl.Reservation) {
        val acquired = ScreenCaptureAcquisition.Result.Acquired(projection, reservation, synchronized(lock) { operation.reusableConsent })
        var recipient: CancellableContinuation<ScreenCaptureAcquisition.Result>? = null
        val shouldOffer = synchronized(lock) {
            if (activeOperation !== operation || operation.phase != Phase.PRODUCING || operation.revoked) {
                false
            } else {
                operation.phase = Phase.OFFERED
                operation.offered = acquired
                recipient = operation.continuation
                true
            }
        }
        if (!shouldOffer) {
            finishProduction(operation, null)
            return
        }
        val continuation = recipient ?: run {
            revoke(operation)
            return
        }
        try {
            continuation.resume(acquired) { _, _, _ -> revoke(operation) }
        } catch (cause: Throwable) {
            Log.e(TAG, "Screen capture transfer delivery failed", cause)
            revoke(operation)
        }
    }

    private fun claim(operation: Operation, result: ScreenCaptureAcquisition.Result.Acquired): Boolean = synchronized(lock) {
        if (activeOperation !== operation || operation.phase != Phase.OFFERED ||
            operation.revoked || operation.offered !== result
        ) {
            false
        } else {
            operation.phase = Phase.TRANSFERRED
            activeOperation = null
            clearResources(operation)
            true
        }
    }

    private fun revoke(operation: Operation, awaitingOnly: Boolean = false): Boolean {
        var abandonKey: String? = null
        var shouldRollback = false
        val revokedNow = synchronized(lock) {
            if (activeOperation !== operation || operation.phase == Phase.TRANSFERRED || operation.phase == Phase.FINISHED ||
                (awaitingOnly && operation.phase != Phase.AWAITING_CONSENT)
            ) {
                false
            } else {
                operation.revoked = true
                when (operation.phase) {
                    Phase.AWAITING_CONSENT -> {
                        abandonKey = operation.requestKey
                        operation.phase = Phase.ROLLING_BACK
                        // No resource worker exists yet; replacement may occupy the slot now.
                        activeOperation = null
                        shouldRollback = true
                    }

                    Phase.OFFERED -> {
                        operation.phase = Phase.ROLLING_BACK
                        shouldRollback = true
                    }

                    Phase.PRODUCING, Phase.ROLLING_BACK -> Unit
                    Phase.TRANSFERRED, Phase.FINISHED -> error("Terminal phase checked above")
                }
                true
            }
        }
        abandonKey?.let(::abandonRoute)
        if (shouldRollback) startRollback(operation)
        return revokedNow
    }

    private fun abandonRoute(key: String) {
        scope.launch(Dispatchers.Main) { consentBridge.abandon(key) }
    }

    private fun finishProduction(operation: Operation, failure: ScreenCaptureAcquisition.Result.Failed?) {
        val shouldRollback = synchronized(lock) {
            if (activeOperation !== operation || operation.phase != Phase.PRODUCING) {
                false
            } else {
                operation.phase = Phase.ROLLING_BACK
                operation.primaryFailure = failure
                operation.publicResult = if (operation.revoked) null else failure
                true
            }
        }
        if (shouldRollback) startRollback(operation)
    }

    private fun startRollback(operation: Operation) {
        scope.launch(Dispatchers.IO) { rollback(operation) }
    }

    private suspend fun rollback(operation: Operation) {
        val projection = synchronized(lock) { operation.projection }
        val projectionOutcome = if (projection == null) {
            ScreenCaptureAcquisition.ProjectionStopResult.NotRequired
        } else {
            try {
                projection.stop()
                ScreenCaptureAcquisition.ProjectionStopResult.ApiCompleted
            } catch (cause: Throwable) {
                ScreenCaptureAcquisition.ProjectionStopResult.Failed(cause)
            }
        }
        val reservation = synchronized(lock) { operation.reservation }
        val foregroundOutcome = if (reservation == null) {
            ForegroundControl.ReleaseResult.NotRequired
        } else {
            try {
                reservation.release()
            } catch (cause: Throwable) {
                ForegroundControl.ReleaseResult.Failed(cause)
            }
        }
        val (report, result, wasOffered) = synchronized(lock) {
            val terminalReport = ScreenCaptureAcquisition.RollbackResult(
                primaryFailure = operation.primaryFailure,
                projectionStop = projectionOutcome,
                foregroundRelease = foregroundOutcome,
            )
            if (operation.phase == Phase.ROLLING_BACK) {
                operation.phase = Phase.FINISHED
                if (activeOperation === operation) activeOperation = null
            }
            val finalResult = if (operation.revoked) null else operation.publicResult
            val offered = operation.offered != null
            clearResources(operation)
            Triple(terminalReport, finalResult, offered)
        }
        try {
            operation.reports.reportRollbackFinished(report)
        } catch (cause: Throwable) {
            Log.e(TAG, "Screen capture rollback report failed", cause)
        }
        val continuation = synchronized(lock) {
            operation.settledResult = result
            operation.settled = true
            operation.continuation.also { operation.continuation = null }
        }
        if (!wasOffered && continuation != null) deliverRollbackResult(continuation, result)
    }

    private fun deliverRollbackResult(
        continuation: CancellableContinuation<ScreenCaptureAcquisition.Result>,
        result: ScreenCaptureAcquisition.Result?,
    ) {
        if (result == null) {
            continuation.resumeWithException(CancellationException("Screen capture acquisition was revoked"))
        } else {
            continuation.resume(result)
        }
    }

    /** Caller holds [lock]. No raw authority remains in a retired operation. */
    private fun clearResources(operation: Operation) {
        operation.requestKey = null
        operation.resultData = null
        operation.reusableConsent = null
        operation.reservation = null
        operation.projection = null
        operation.offered = null
        operation.publicResult = null
    }

    private companion object {
        private const val TAG: String = "ScreenCaptureAcquisition"
    }
}
