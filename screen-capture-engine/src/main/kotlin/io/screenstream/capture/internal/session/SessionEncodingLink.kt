package io.screenstream.capture.internal.session

import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.encoding.EncodingInput
import io.screenstream.capture.internal.encoding.EncodingInputResult
import io.screenstream.capture.internal.encoding.EncodingInputSettlement
import io.screenstream.capture.internal.encoding.EncodingOwner
import io.screenstream.capture.internal.encoding.EncodingProductionReturnPort
import io.screenstream.capture.internal.encoding.EncodingReconcileResult
import io.screenstream.capture.internal.encoding.EncodingReconcileReturnPort
import io.screenstream.capture.internal.encoding.EncodingReconcileSubmission
import io.screenstream.capture.internal.encoding.EncodingResult
import io.screenstream.capture.internal.encoding.NativeJpegFacade
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import io.screenstream.capture.internal.session.production.SessionProductionRecord

/**
 * Correlates bounded encoding submissions, callbacks, and the exact input-loan phase.
 *
 * This link is bookkeeping rather than policy: [SessionCoordinator] decides whether recorded facts
 * are current and what they mean for the session. Terminal freezing may preserve only the exact
 * detached production record and input that are still in the loaned phase; all other pending facts
 * become semantically irrelevant.
 */
internal class SessionEncodingLink(
    private val coordinator: SessionCoordinator,
    workerDispatcher: NonInlineDispatcher,
    clock: ElapsedRealtimeClock,
    nativeJpeg: NativeJpegFacade,
) {
    internal class ReconcileRequest(
        private val link: SessionEncodingLink,
        internal val configRevision: Long,
        internal val plan: Rgba8888Layout,
    ) : EncodingReconcileReturnPort {
        internal var submissionReturned = false
        internal var callbackRecorded = false

        override fun onReturned(result: EncodingReconcileResult) =
            link.coordinator.onEncodingReconcileReturned(link, this, result)
    }

    internal class ReconcileFact(internal val request: ReconcileRequest, internal val result: EncodingReconcileResult)

    internal class ProductionRequest(
        private val link: SessionEncodingLink,
        internal val record: SessionProductionRecord,
    ) : EncodingProductionReturnPort {
        internal var phase: ProductionPhase = ProductionPhase.Acquiring
        internal var input: EncodingInput? = null
        internal var shouldEncode: Boolean? = null
        internal var callbackRecorded = false

        override fun onReturned(result: EncodingResult) =
            link.coordinator.onEncodingProductionReturned(link, this, result)
    }

    internal class ProductionFact(internal val request: ProductionRequest, internal val result: EncodingResult)

    internal enum class FactAdmission { Recorded, MismatchOrDuplicate, }

    internal enum class ProductionPhase { Acquiring, Loaned, Settling, Accepted, }

    private val owner = EncodingOwner(workerDispatcher = workerDispatcher, clock = clock, nativeJpeg = nativeJpeg)
    private var pendingReconcile: ReconcileRequest? = null
    private var reconcileFact: ReconcileFact? = null
    private var pendingProduction: ProductionRequest? = null
    private var productionFact: ProductionFact? = null

    internal fun prepareReconcileLocked(configRevision: Long, plan: Rgba8888Layout): ReconcileRequest {
        check((pendingReconcile == null) && (reconcileFact == null))
        return ReconcileRequest(this, configRevision, plan).also { pendingReconcile = it }
    }

    internal fun executeReconcile(request: ReconcileRequest, policy: JpegBackendPolicy): EncodingReconcileSubmission =
        owner.reconcile(request.plan, policy, request)

    internal fun recordReconcileSubmissionLocked(request: ReconcileRequest, submission: EncodingReconcileSubmission): Boolean {
        if ((pendingReconcile !== request) || (request.submissionReturned)) return false
        request.submissionReturned = true
        when (submission) {
            EncodingReconcileSubmission.Accepted -> if (request.callbackRecorded) pendingReconcile = null
            is EncodingReconcileSubmission.Rejected -> {
                if (request.callbackRecorded) return false
                pendingReconcile = null
            }
        }
        return true
    }

    internal fun recordReconcileCallbackLocked(request: ReconcileRequest, result: EncodingReconcileResult): FactAdmission {
        if (pendingReconcile !== request || request.callbackRecorded || reconcileFact != null) {
            return FactAdmission.MismatchOrDuplicate
        }
        request.callbackRecorded = true
        reconcileFact = ReconcileFact(request, result)
        if (request.submissionReturned) pendingReconcile = null
        return FactAdmission.Recorded
    }

    internal fun takeReconcileFactLocked(): ReconcileFact? = reconcileFact.also { reconcileFact = null }

    internal fun prepareProductionLocked(record: SessionProductionRecord): ProductionRequest {
        check(pendingProduction == null && productionFact == null)
        return ProductionRequest(this, record).also { pendingProduction = it }
    }

    internal fun executeAcquire(request: ProductionRequest): EncodingInputResult = owner.acquireInput(request)

    internal fun recordAcquireReturnedLocked(request: ProductionRequest, result: EncodingInputResult): Boolean {
        if (pendingProduction !== request || request.phase != ProductionPhase.Acquiring || request.callbackRecorded) {
            return false
        }
        when (result) {
            is EncodingInput -> {
                request.input = result
                request.phase = ProductionPhase.Loaned
            }

            is EncodingInputResult.Failed -> {
                pendingProduction = null
            }
        }
        return true
    }

    internal fun beginSettlementLocked(record: SessionProductionRecord, input: EncodingInput, shouldEncode: Boolean): Boolean {
        val request = pendingProduction ?: return false
        if (request.record !== record || request.input !== input || request.phase != ProductionPhase.Loaned) return false
        request.shouldEncode = shouldEncode
        request.phase = ProductionPhase.Settling
        return true
    }

    internal fun recordSettlementReturnedLocked(record: SessionProductionRecord, input: EncodingInput, result: EncodingInputSettlement): Boolean {
        val request = pendingProduction ?: return false
        if (request.record !== record || request.input !== input || request.phase != ProductionPhase.Settling) return false
        return when (result) {
            EncodingInputSettlement.Accepted -> {
                if (request.shouldEncode != true) return false
                request.phase = ProductionPhase.Accepted
                if (request.callbackRecorded) pendingProduction = null
                true
            }

            EncodingInputSettlement.Settled, is EncodingInputSettlement.Failed -> {
                if (request.callbackRecorded) return false
                pendingProduction = null
                true
            }
        }
    }

    internal fun recordProductionCallbackLocked(request: ProductionRequest, result: EncodingResult): FactAdmission {
        if (pendingProduction !== request || request.callbackRecorded || productionFact != null ||
            request.phase != ProductionPhase.Settling && request.phase != ProductionPhase.Accepted
        ) {
            return FactAdmission.MismatchOrDuplicate
        }
        request.callbackRecorded = true
        productionFact = ProductionFact(request, result)
        if (request.phase == ProductionPhase.Accepted) pendingProduction = null
        return FactAdmission.Recorded
    }

    internal fun takeProductionFactLocked(): ProductionFact? = productionFact.also { productionFact = null }

    internal val isProductionSlotFree: Boolean
        get() = (pendingProduction == null) && (productionFact == null)

    internal fun canFreezeTerminalLocked(preservedRecord: SessionProductionRecord?, preservedInput: EncodingInput?): Boolean {
        if ((preservedRecord == null) != (preservedInput == null)) return false
        val request = pendingProduction
        if (request?.phase != ProductionPhase.Loaned) return preservedRecord == null
        return request.record === preservedRecord && request.input === preservedInput
    }

    internal fun freezeTerminalLocked(preservedRecord: SessionProductionRecord?, preservedInput: EncodingInput?) {
        check(canFreezeTerminalLocked(preservedRecord, preservedInput))
        pendingReconcile = null
        reconcileFact = null
        productionFact = null
        if (pendingProduction?.phase != ProductionPhase.Loaned) pendingProduction = null
    }

    internal fun retire() = owner.retire()
}
