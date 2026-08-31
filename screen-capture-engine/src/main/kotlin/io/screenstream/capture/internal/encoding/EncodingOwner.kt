package io.screenstream.capture.internal.encoding

import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import io.screenstream.capture.internal.runtime.SerialTaskSlot

/**
 * Sole per-session owner of the RGBA carrier, its exact input loan, JPEG runtime/backend state, encoded
 * transactions, and physical Encoding retirement.
 *
 * Reconcile and production use one queue-less operation slot and never overlap an outstanding input loan. An
 * [EncodingInput] is both the carrier capability and its settlement identity; only that exact input may be encoded
 * or discarded. Physical settlement and slot release precede result delivery. Retirement closes new work but cannot
 * infer that a loaned carrier or entered operation returned.
 */
internal class EncodingOwner(
    workerDispatcher: NonInlineDispatcher,
    private val clock: ElapsedRealtimeClock,
    private val nativeJpeg: NativeJpegFacade = NativeJpegProcess,
    private val productionFactory: EncodingProductionFactory = DefaultEncodingProductionFactory,
) {
    private sealed interface OperationOccupancy

    private abstract inner class Operation : OperationOccupancy {
        abstract fun executeEntered()
        abstract fun returnResult()

        fun run() {
            val mayEnter = synchronized(gate) {
                (!retired) && (operation === this)
            }
            if (!mayEnter) {
                settleCutoff()
                return
            }
            try {
                executeEntered()
            } catch (failure: Exception) {
                recordOrdinaryFailure(failure)
            }
        }

        open fun settleCutoff() = Unit

        abstract fun recordOrdinaryFailure(failure: Exception)

        fun onReleased() {
            val cutoff = synchronized(gate) {
                if (operation === this) operation = null
                retired
            }
            try {
                returnResult()
            } finally {
                if (cutoff) requestPhysicalRetirement()
            }
        }
    }

    private inner class ReconcileOperation(
        private val plan: Rgba8888Layout,
        private val policy: JpegBackendPolicy,
        private val returnPort: EncodingReconcileReturnPort,
    ) : Operation() {
        private var result: EncodingReconcileResult = EncodingReconcileResult.CutoffInert

        override fun executeEntered() {
            result = reconcileEntered(plan, policy)
            if (synchronized(gate) { retired }) {
                result = EncodingReconcileResult.CutoffInert
            }
        }

        override fun recordOrdinaryFailure(failure: Exception) {
            result = EncodingReconcileResult.Failed(ScreenCaptureProblem.InternalFailure, failure)
        }

        override fun returnResult() {
            returnPort.onReturned(result)
        }
    }

    private inner class ProductionOperation(
        private val production: EncoderProductionTask,
    ) : Operation() {
        private var result: EncodingResult = EncodingResult.CutoffInert

        override fun executeEntered() {
            production.execute(clock)
            result = settleProductionResult(production)
            if (synchronized(gate) { retired }) result = EncodingResult.CutoffInert
        }

        override fun settleCutoff() {
            val skipFailure = try {
                production.skipBeforeEntry()
                null
            } catch (failure: Exception) {
                failure
            }
            settleProductionAfterFailure(production, skipFailure)?.let { failure ->
                result = EncodingResult.Failed(ScreenCaptureProblem.InternalFailure, failure)
            }
        }

        override fun recordOrdinaryFailure(failure: Exception) {
            settleProductionAfterFailure(production, failure)
            result = EncodingResult.Failed(ScreenCaptureProblem.InternalFailure, failure)
        }

        override fun returnResult() {
            production.input.returnPort.onReturned(result)
        }
    }

    private inner class RetirementOperation : OperationOccupancy {
        fun executeRetirement() {
            try {
                retirePhysicalRoots()
            } catch (_: Exception) {
            }
        }

        fun onReleased() {
            synchronized(gate) {
                if (operation === this) operation = null
            }
        }
    }

    private val gate: Any = Any()
    private val serialSlot = SerialTaskSlot(workerDispatcher)
    private var retired: Boolean = false
    private var operation: OperationOccupancy? = null
    private var runtime: EncoderRuntime? = null
    private var currentPolicy: JpegBackendPolicy? = null
    private var nativeHealth: NativeHealthCell? = null
    private var activeInput: EncodingInput? = null

    internal fun reconcile(
        plan: Rgba8888Layout,
        policy: JpegBackendPolicy,
        returnPort: EncodingReconcileReturnPort,
    ): EncodingReconcileSubmission {
        val request = synchronized(gate) {
            if ((retired || operation != null || activeInput != null)) {
                return EncodingReconcileSubmission.Rejected(cause = null)
            }
            ReconcileOperation(plan, policy, returnPort).also { operation = it }
        }
        val accepted = try {
            submit(request)
        } catch (failure: Exception) {
            requestPhysicalRetirement()
            return EncodingReconcileSubmission.Rejected(failure)
        }
        return if (accepted) {
            EncodingReconcileSubmission.Accepted
        } else {
            requestPhysicalRetirement()
            EncodingReconcileSubmission.Rejected(cause = null)
        }
    }

    internal fun acquireInput(returnPort: EncodingProductionReturnPort): EncodingInputResult = synchronized(gate) {
        if (retired || operation != null || activeInput != null) {
            return@synchronized EncodingInputResult.Failed(ScreenCaptureProblem.InternalFailure, null)
        }
        val exactRuntime = runtime
            ?: return@synchronized EncodingInputResult.Failed(ScreenCaptureProblem.InternalFailure, null)
        val input = try {
            exactRuntime.lendCarrier(this, returnPort)
        } catch (failure: Exception) {
            return@synchronized EncodingInputResult.Failed(ScreenCaptureProblem.InternalFailure, failure)
        } ?: return@synchronized EncodingInputResult.Failed(ScreenCaptureProblem.InternalFailure, null)
        activeInput = input
        input
    }

    internal fun discardInput(input: EncodingInput): EncodingInputSettlement {
        val exact = synchronized(gate) {
            val candidate = activeInput
                ?: return EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, null)
            if (candidate !== input) {
                return EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, null)
            }
            candidate
        }
        val settled = try {
            exact.carrier.settle(exact, CarrierDisposition.Discarded)
        } catch (failure: Exception) {
            return EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, failure)
        }
        if (settled !== exact) {
            return EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, null)
        }
        return finishDirectInputSettlement(exact, EncodingInputSettlement.Settled)
    }

    internal fun encodeInput(input: EncodingInput, jpegQuality: Int): EncodingInputSettlement {
        val exact = synchronized(gate) {
            val candidate = activeInput
                ?: return EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, null)
            if (candidate !== input) {
                return EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, null)
            }
            candidate
        }
        val settled = try {
            exact.carrier.settle(exact, CarrierDisposition.Filled)
        } catch (failure: Exception) {
            return EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, failure)
        }
        if (settled !== exact) {
            return EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, null)
        }
        if (synchronized(gate) { retired }) {
            return settleReadyInputAfterCutoff(exact)
        }
        if (jpegQuality !in ScreenCaptureParameters.JPEG_QUALITY_RANGE) {
            return finishDirectInputSettlement(exact, failReadyInput(exact, null))
        }
        return prepareAndSubmitProduction(exact, jpegQuality)
    }

    internal fun retire() {
        synchronized(gate) {
            if (retired) return
            retired = true
        }
        requestPhysicalRetirement()
    }

    private fun submit(operation: Operation, rejectedInput: EncodingInput? = null): Boolean {
        val submission = try {
            serialSlot.trySubmit(task = operation::run, afterTaskReleased = operation::onReleased)
        } catch (failure: Exception) {
            restoreRejectedSubmission(operation, rejectedInput)
            throw failure
        }
        return when (submission) {
            SerialTaskSlot.Submission.Accepted -> true
            SerialTaskSlot.Submission.Occupied -> {
                restoreRejectedSubmission(operation, rejectedInput)
                false
            }

            is SerialTaskSlot.Submission.Rejected -> {
                restoreRejectedSubmission(operation, rejectedInput)
                submission.cause?.let { throw it }
                false
            }
        }
    }

    private fun restoreRejectedSubmission(operation: Operation, rejectedInput: EncodingInput?) {
        synchronized(gate) {
            check(this.operation === operation)
            this.operation = null
            if (rejectedInput != null) {
                check(activeInput == null)
                activeInput = rejectedInput
            }
        }
    }

    private fun reconcileEntered(plan: Rgba8888Layout, policy: JpegBackendPolicy): EncodingReconcileResult {
        val installedRuntime = synchronized(gate) { runtime }
        if (installedRuntime != null && synchronized(gate) { currentPolicy == policy }) {
            if (installedRuntime.isCompatible(plan)) return EncodingReconcileResult.Ready
            if (installedRuntime.canPrepareFrameworkOwner(plan)) {
                return prepareAndInstallFrameworkOwner(installedRuntime)
            }
        }
        installedRuntime?.let { runtimeToRetire ->
            val retirementFailure = retireRuntime(runtimeToRetire)
            retirementFailure?.let { retirementCause ->
                return EncodingReconcileResult.Failed(ScreenCaptureProblem.InternalFailure, retirementCause)
            }
            synchronized(gate) {
                if (runtime === runtimeToRetire) {
                    runtime = null
                    currentPolicy = null
                }
            }
        }

        val preparation = EncoderRuntime.prepareBackend(policy, synchronized(gate) { nativeHealth }, nativeJpeg)
        synchronized(gate) {
            preparation.nativeHealthCell?.let { returned ->
                val existing = nativeHealth
                check(existing == null || existing === returned)
                nativeHealth = returned
            }
        }
        (preparation as? EncoderBackendPreparation.Failed)?.let {
            return EncodingReconcileResult.Failed(it.problem, it.cause)
        }
        val creation = when (preparation) {
            is EncoderBackendPreparation.NativeCarrier ->
                EncoderRuntime.allocateNativeRuntime(plan, preparation, NativeMallocCarrier(plan, nativeJpeg))

            is EncoderBackendPreparation.ManagedCarrier ->
                EncoderRuntime.allocateManagedRuntime(plan, ManagedDirectCarrier(plan))

            is EncoderBackendPreparation.Failed -> error("handled above")
        }
        val createdRuntime = when (creation) {
            is EncoderRuntimeCreation.Created -> creation.runtime
            is EncoderRuntimeCreation.Failed -> {
                creation.retainedRuntime?.let { synchronized(gate) { runtime = it } }
                return EncodingReconcileResult.Failed(creation.problem, creation.cause)
            }
        }
        if (createdRuntime.backendState !is EncoderBackendState.NativeOnNativeCarrier) {
            val frameworkReconcileResult = prepareAndInstallFrameworkOwner(createdRuntime)
            if (frameworkReconcileResult != EncodingReconcileResult.Ready) return frameworkReconcileResult
        }
        synchronized(gate) {
            runtime = createdRuntime
            currentPolicy = policy
        }
        return EncodingReconcileResult.Ready
    }

    private fun prepareAndInstallFrameworkOwner(exactRuntime: EncoderRuntime): EncodingReconcileResult {
        return when (val creation = exactRuntime.prepareFrameworkOwner(exactRuntime.newFrameworkOwnerCandidate())) {
            is FrameworkBitmapOwner.Creation.Created -> {
                check(exactRuntime.installFrameworkOwner(creation))
                EncodingReconcileResult.Ready
            }

            is FrameworkBitmapOwner.Creation.Failed -> {
                if (creation.ownerResidue != null) check(exactRuntime.retainFrameworkOwnerResidue(creation))
                synchronized(gate) { runtime = exactRuntime }
                EncodingReconcileResult.Failed(creation.problem, creation.cause)
            }
        }
    }

    private fun prepareAndSubmitProduction(exact: EncodingInput, jpegQuality: Int): EncodingInputSettlement {
        val exactRuntime = synchronized(gate) { runtime }
            ?: return finishDirectInputSettlement(exact, failReadyInput(exact, null))
        val healthCell = synchronized(gate) { nativeHealth }
            ?: return finishDirectInputSettlement(exact, failReadyInput(exact, null))
        val production = when (exactRuntime.backendState) {
            is EncoderBackendState.NativeOnNativeCarrier -> {
                val transaction = try {
                    productionFactory.createNativeTransaction()
                } catch (failure: OutOfMemoryError) {
                    return finishDirectInputSettlement(
                        exact,
                        failReadyInput(exact, failure, ScreenCaptureProblem.ResourceExhausted),
                    )
                }
                try {
                    productionFactory.createNativeProduction(
                        runtime = exactRuntime,
                        expectedInput = exact,
                        transaction = transaction,
                        jpegQuality = jpegQuality,
                        healthCell = healthCell,
                        nativeJpeg = nativeJpeg,
                    )
                } catch (failure: Exception) {
                    return finishDirectInputSettlement(exact, failReadyInput(exact, failure))
                }
            }

            is EncoderBackendState.Framework -> {
                val transaction = try {
                    productionFactory.createFrameworkTransaction()
                } catch (failure: OutOfMemoryError) {
                    return finishDirectInputSettlement(
                        exact,
                        failReadyInput(exact, failure, ScreenCaptureProblem.ResourceExhausted),
                    )
                }
                try {
                    productionFactory.createFrameworkProduction(
                        runtime = exactRuntime,
                        expectedInput = exact,
                        transaction = transaction,
                        jpegQuality = jpegQuality,
                    )
                } catch (failure: Exception) {
                    return finishDirectInputSettlement(exact, failReadyInput(exact, failure))
                }
            }
        } ?: return finishDirectInputSettlement(exact, failReadyInput(exact, null))

        var cutoffBeforeSubmission = false
        val request = try {
            synchronized(gate) {
                if (retired) {
                    cutoffBeforeSubmission = true
                    null
                } else if (operation == null && activeInput === exact) {
                    ProductionOperation(production).also {
                        activeInput = null
                        operation = it
                    }
                } else {
                    null
                }
            }
        } catch (failure: Exception) {
            return failProductionBeforeSubmission(exact, production, failure)
        } ?: return if (cutoffBeforeSubmission) {
            val failure = settleProductionAfterFailure(production, cause = null)
            finishDirectInputSettlement(
                exact,
                if (failure == null) EncodingInputSettlement.Settled else
                    EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, failure),
            )
        } else {
            failProductionBeforeSubmission(exact, production, null)
        }
        val accepted = try {
            submit(request, rejectedInput = exact)
        } catch (failure: Exception) {
            return failProductionBeforeSubmission(exact, production, failure)
        }
        if (accepted) return EncodingInputSettlement.Accepted
        return failProductionBeforeSubmission(exact, production, null)
    }

    private fun finishDirectInputSettlement(exact: EncodingInput, settlement: EncodingInputSettlement): EncodingInputSettlement {
        val cutoff = synchronized(gate) {
            if (activeInput !== exact) {
                return EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, null)
            }
            activeInput = null
            retired
        }
        if (cutoff) requestPhysicalRetirement()
        return settlement
    }

    private fun failReadyInput(
        exact: EncodingInput,
        cause: Throwable?,
        failureProblem: ScreenCaptureProblem = ScreenCaptureProblem.InternalFailure,
    ): EncodingInputSettlement {
        return try {
            val discarded = exact.carrier.discardReady(exact)
            if (discarded === exact) {
                EncodingInputSettlement.Failed(failureProblem, cause)
            } else {
                EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, cause ?: encoderCleanupMismatch)
            }
        } catch (failure: Exception) {
            EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, cause ?: failure)
        }
    }

    private fun settleReadyInputAfterCutoff(exact: EncodingInput): EncodingInputSettlement {
        val failure = try {
            encoderCleanupMismatch.takeUnless { exact.carrier.discardReady(exact) === exact }
        } catch (cause: Exception) {
            cause
        }
        return finishDirectInputSettlement(
            exact,
            if (failure == null) EncodingInputSettlement.Settled else
                EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, failure),
        )
    }

    private fun failProductionBeforeSubmission(
        exact: EncodingInput,
        production: EncoderProductionTask,
        cause: Throwable?,
    ): EncodingInputSettlement {
        val settlement = settleProductionAfterFailure(production, cause)
        return finishDirectInputSettlement(
            exact,
            EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, settlement ?: cause),
        )
    }

    private fun settleProductionAfterFailure(production: EncoderProductionTask, cause: Throwable?): Throwable? {
        return try {
            val physicalFailure = production.settlePhysical()
            val detachedFailure = if (production.hasLeafResult) production.settleDetachedLeaf() else null
            cause ?: physicalFailure ?: detachedFailure
        } catch (failure: Exception) {
            cause ?: failure
        }
    }

    private fun settleProductionResult(production: EncoderProductionTask): EncodingResult {
        val transferFailure = production.settleDetachedLeaf()
        if (transferFailure != null) {
            return EncodingResult.Failed(ScreenCaptureProblem.InternalFailure, transferFailure)
        }
        return when (production) {
            is FrameworkJpegProduction -> when (val jpeg = checkNotNull(production.result)) {
                is FrameworkJpegResult.Success -> EncodingResult.Encoded(jpeg.payload, jpeg.encodeDurationNanos)
                is FrameworkJpegResult.Failure -> when (jpeg.jpegProblem) {
                    FrameworkJpegResult.Problem.CompressionRejected -> EncodingResult.FrameFailed
                    FrameworkJpegResult.Problem.ResourceExhausted -> EncodingResult.Failed(ScreenCaptureProblem.ResourceExhausted, jpeg.cause)
                    FrameworkJpegResult.Problem.InternalFailure -> EncodingResult.Failed(ScreenCaptureProblem.InternalFailure, jpeg.cause)
                }

                is FrameworkJpegResult.Skipped -> EncodingResult.CutoffInert
            }

            is NativeJpegProduction -> with(checkNotNull(production.result)) {
                when (requireDisposition()) {
                    NativeJpegDisposition.Returned.CompleteTransfer ->
                        EncodingResult.Encoded(checkNotNull(payload), encodeDurationNanos)

                    NativeJpegDisposition.Returned.SafeCompressorRejection -> {
                        val fallbackCommitted = synchronized(gate) {
                            if (retired || runtime !== production.runtime || nativeHealth !== production.healthCell) false
                            else production.healthCell.disable() && production.runtime.switchNativeToFramework()
                        }
                        if (fallbackCommitted) EncodingResult.ReadinessChanged
                        else EncodingResult.Failed(ScreenCaptureProblem.InternalFailure, null)
                    }

                    NativeJpegDisposition.Returned.RequiredResourceExhaustion ->
                        EncodingResult.Failed(ScreenCaptureProblem.ResourceExhausted, null)

                    NativeJpegDisposition.Returned.UnsafeInternalFailure ->
                        EncodingResult.Failed(ScreenCaptureProblem.InternalFailure, null)

                    NativeJpegDisposition.SkippedBeforeEntry -> EncodingResult.CutoffInert
                }
            }
        }
    }

    private fun requestPhysicalRetirement() {
        val request = synchronized(gate) {
            if (!retired || operation != null || activeInput != null || runtime == null) return
            RetirementOperation().also { operation = it }
        }
        val accepted = try {
            when (val submission = serialSlot.trySubmit(task = request::executeRetirement, afterTaskReleased = request::onReleased)) {
                SerialTaskSlot.Submission.Accepted -> true
                SerialTaskSlot.Submission.Occupied -> false
                is SerialTaskSlot.Submission.Rejected -> {
                    submission.cause?.let { throw it }
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
        if (!accepted) synchronized(gate) {
            if (operation === request) operation = null
        }
    }

    private fun retirePhysicalRoots() {
        val exactRuntime = synchronized(gate) { runtime } ?: return
        if (retireRuntime(exactRuntime) == null) synchronized(gate) {
            if (runtime === exactRuntime) {
                runtime = null
                currentPolicy = null
            }
        }
    }

    private fun retireRuntime(exactRuntime: EncoderRuntime): Throwable? {
        when (val frameworkRetirement = exactRuntime.retireFrameworkOwner()) {
            EncodingRetirement.Closed -> Unit
            is EncodingRetirement.Retained -> return frameworkRetirement.cause ?: encoderCleanupMismatch
        }
        return when (val carrierRetirement = exactRuntime.retireCarrier()) {
            EncodingRetirement.Closed -> null
            is EncodingRetirement.Retained -> carrierRetirement.cause ?: encoderCleanupMismatch
        }
    }
}
