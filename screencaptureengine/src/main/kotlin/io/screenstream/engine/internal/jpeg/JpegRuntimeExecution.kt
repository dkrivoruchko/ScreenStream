package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.JpegBackendPolicy
import io.screenstream.engine.internal.JpegRuntimeOwner
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import java.nio.ByteBuffer
import kotlin.concurrent.withLock

internal fun JpegRuntimeOwner.executeJpegPreparation(
    occurrence: JpegPreparationOccurrence,
    policy: JpegBackendPolicy,
    byteCount: Int,
) {
    val loadState = try {
        NativeJpegProcess.ensureAvailable()
    } catch (fatal: Throwable) {
        publishPreparationFatalAndRethrow(occurrence, fatal)
    }
    when (loadState) {
        NativeJpegProcess.State.LoadOome ->
            publishFailure(occurrence.operation, JpegRuntimeFailure.ResourceExhausted, NativeJpegProcess.cause() ?: MISSING_LOADER_FAILURE)

        NativeJpegProcess.State.Poisoned ->
            publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, NativeJpegProcess.cause() ?: MISSING_LOADER_FAILURE)

        NativeJpegProcess.State.Available -> prepareWithNativeCarrier(occurrence, policy, byteCount)
        NativeJpegProcess.State.CleanUnavailable -> {
            occurrence.operation.returnCell.evidence.cleanNativeUnavailabilityCause = NativeJpegProcess.cause()
            prepareWithManagedCarrier(occurrence, byteCount)
        }

        NativeJpegProcess.State.Unattempted -> error("loader result cannot remain unattempted")
    }
}

private fun JpegRuntimeOwner.prepareWithNativeCarrier(occurrence: JpegPreparationOccurrence, policy: JpegBackendPolicy, byteCount: Int) {
    val carrier = try {
        createNativeCarrierCandidate(byteCount)
    } catch (failure: Exception) {
        publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
        return
    } catch (fatal: Throwable) {
        publishPreparationFatalAndRethrow(occurrence, fatal)
    }
    val nativeAvailable = if (policy == JpegBackendPolicy.Auto) {
        try {
            NativeJpegProcess.hasWeakCompressor()
        } catch (failure: Exception) {
            publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
            return
        } catch (fatal: Throwable) {
            publishPreparationFatalAndRethrow(occurrence, fatal)
        }
    } else {
        false
    }
    val product: JpegRuntimeProduct = try {
        if (nativeAvailable) {
            JpegRuntimeProduct.NativeEnabled.create(carrier)
        } else {
            JpegRuntimeProduct.FrameworkOnNativeCarrier.create(carrier)
        }
    } catch (failure: Exception) {
        publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
        return
    } catch (fatal: Throwable) {
        publishPreparationFatalAndRethrow(occurrence, fatal)
    }
    occurrence.ownerBag.product = product

    val buffer = try {
        NativeJpegProcess.allocateCarrier(byteCount.toLong())
    } catch (allocationFailure: OutOfMemoryError) {
        publishFailure(occurrence.operation, JpegRuntimeFailure.ResourceExhausted, allocationFailure)
        return
    } catch (failure: Exception) {
        publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
        return
    } catch (fatal: Throwable) {
        publishPreparationFatalAndRethrow(occurrence, fatal)
    }
    attachAndPublishNativePreparation(occurrence, carrier, buffer)
}

private fun JpegRuntimeOwner.prepareWithManagedCarrier(occurrence: JpegPreparationOccurrence, byteCount: Int) {
    val carrier = try {
        createManagedCarrierCandidate(byteCount)
    } catch (failure: Exception) {
        publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
        return
    } catch (fatal: Throwable) {
        publishPreparationFatalAndRethrow(occurrence, fatal)
    }
    occurrence.ownerBag.product = try {
        JpegRuntimeProduct.FrameworkOnManagedCarrier.create(carrier)
    } catch (failure: Exception) {
        publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
        return
    } catch (fatal: Throwable) {
        publishPreparationFatalAndRethrow(occurrence, fatal)
    }
    val buffer = try {
        ByteBuffer.allocateDirect(byteCount)
    } catch (allocationFailure: OutOfMemoryError) {
        publishFailure(occurrence.operation, JpegRuntimeFailure.ResourceExhausted, allocationFailure)
        return
    } catch (failure: Exception) {
        publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
        return
    } catch (fatal: Throwable) {
        publishPreparationFatalAndRethrow(occurrence, fatal)
    }
    attachAndPublishManagedPreparation(occurrence, carrier, buffer)
}

private fun JpegRuntimeOwner.attachAndPublishNativePreparation(occurrence: JpegPreparationOccurrence, carrier: NativeMallocCarrier, buffer: ByteBuffer) {
    val gate = occurrence.operation.settlementGate
    gate.withLock {
        if (!carrier.attachReturnedBufferLocked(gate, buffer)) throw ATTACHMENT_STATE_VIOLATION
        occurrence.ownerBag.nativeCarrier = carrier
    }
    try {
        inspectBuffer(buffer, carrier.byteCount, occurrence.validation)
    } catch (fatal: Throwable) {
        publishPreparationFatalAndRethrow(occurrence, fatal)
    }
    val validation = occurrence.validation
    val validationFailure = validation.failure
    val published = gate.withLock {
        if (!carrier.completeAttachmentLocked(gate, validation)) throw ATTACHMENT_STATE_VIOLATION
        occurrence.operation.returnCell.evidence.returnedOwner = carrier
        if (validationFailure == null) {
            occurrence.operation.publishNormalReturn()
        } else {
            occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.InternalFailure
            occurrence.operation.returnCell.evidence.failureCause = validationFailure
            publishInternalFailure(occurrence.operation, validationFailure)
        }
    }
    if (published) signalJpegIoSettlement()
}

private fun JpegRuntimeOwner.attachAndPublishManagedPreparation(occurrence: JpegPreparationOccurrence, carrier: ManagedDirectCarrier, buffer: ByteBuffer) {
    val gate = occurrence.operation.settlementGate
    gate.withLock {
        if (!carrier.attachReturnedBufferLocked(gate, buffer)) throw ATTACHMENT_STATE_VIOLATION
        occurrence.ownerBag.managedCarrier = carrier
    }
    try {
        inspectBuffer(buffer, carrier.byteCount, occurrence.validation)
    } catch (fatal: Throwable) {
        publishPreparationFatalAndRethrow(occurrence, fatal)
    }
    val validation = occurrence.validation
    val validationFailure = validation.failure
    val published = gate.withLock {
        if (!carrier.completeAttachmentLocked(gate, validation)) throw ATTACHMENT_STATE_VIOLATION
        occurrence.operation.returnCell.evidence.returnedOwner = carrier
        if (validationFailure == null) {
            occurrence.operation.publishNormalReturn()
        } else {
            occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.InternalFailure
            occurrence.operation.returnCell.evidence.failureCause = validationFailure
            publishInternalFailure(occurrence.operation, validationFailure)
        }
    }
    if (published) signalJpegIoSettlement()
}

internal fun JpegRuntimeOwner.executeNativeCarrierFree(occurrence: NativeCarrierFreeOccurrence) {
    val published = try {
        NativeJpegProcess.freeCarrier(occurrence.ownerBag.buffer)
        occurrence.operation.returnCell.evidence.receipt = occurrence.operation.returnCell.evidence.normalReceipt
        occurrence.operation.publishNormalReturn()
    } catch (failure: Exception) {
        occurrence.operation.publishThrownReturn(failure)
    } catch (fatal: Throwable) {
        publishNativeFreeFatalAndRethrow(occurrence, fatal)
    }
    if (published) signalJpegIoSettlement()
}

internal fun JpegRuntimeOwner.executeNativeCarrierReplacement(
    occurrence: NativeCarrierReplacementAllocationOccurrence,
    byteCount: Int,
) {
    val carrier = occurrence.carrierCandidate
    val buffer = try {
        NativeJpegProcess.allocateCarrier(byteCount.toLong())
    } catch (allocationFailure: OutOfMemoryError) {
        occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.ResourceExhausted
        occurrence.operation.returnCell.evidence.failureCause = allocationFailure
        if (occurrence.operation.publishNormalReturn()) signalJpegIoSettlement()
        return
    } catch (failure: Exception) {
        occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.InternalFailure
        occurrence.operation.returnCell.evidence.failureCause = failure
        if (occurrence.operation.publishThrownReturn(failure)) signalJpegIoSettlement()
        return
    } catch (fatal: Throwable) {
        publishNativeReplacementFatalAndRethrow(occurrence, fatal)
    }
    attachAndPublishNativeReplacement(occurrence, carrier, buffer)
}

private fun JpegRuntimeOwner.attachAndPublishNativeReplacement(
    occurrence: NativeCarrierReplacementAllocationOccurrence,
    carrier: NativeMallocCarrier,
    buffer: ByteBuffer,
) {
    val gate = occurrence.operation.settlementGate
    gate.withLock {
        if (!carrier.attachReturnedBufferLocked(gate, buffer)) throw ATTACHMENT_STATE_VIOLATION
        occurrence.ownerBag.carrier = carrier
    }
    try {
        inspectBuffer(buffer, carrier.byteCount, occurrence.validation)
    } catch (fatal: Throwable) {
        publishNativeReplacementFatalAndRethrow(occurrence, fatal)
    }
    val validation = occurrence.validation
    val validationFailure = validation.failure
    val published = gate.withLock {
        if (!carrier.completeAttachmentLocked(gate, validation)) throw ATTACHMENT_STATE_VIOLATION
        occurrence.operation.returnCell.evidence.returnedOwner = carrier
        if (validationFailure == null) {
            occurrence.operation.publishNormalReturn()
        } else {
            occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.InternalFailure
            occurrence.operation.returnCell.evidence.failureCause = validationFailure
            publishInternalFailure(occurrence.operation, validationFailure)
        }
    }
    if (published) signalJpegIoSettlement()
}

internal fun JpegRuntimeOwner.executeManagedCarrierReplacement(
    occurrence: ManagedDirectCarrierReplacementAllocationOccurrence,
    byteCount: Int,
) {
    val carrier = occurrence.carrierCandidate
    val buffer = try {
        ByteBuffer.allocateDirect(byteCount)
    } catch (allocationFailure: OutOfMemoryError) {
        occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.ResourceExhausted
        occurrence.operation.returnCell.evidence.failureCause = allocationFailure
        if (occurrence.operation.publishNormalReturn()) signalJpegIoSettlement()
        return
    } catch (failure: Exception) {
        occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.InternalFailure
        occurrence.operation.returnCell.evidence.failureCause = failure
        if (occurrence.operation.publishThrownReturn(failure)) signalJpegIoSettlement()
        return
    } catch (fatal: Throwable) {
        publishManagedReplacementFatalAndRethrow(occurrence, fatal)
    }
    attachAndPublishManagedReplacement(occurrence, carrier, buffer)
}

private fun JpegRuntimeOwner.attachAndPublishManagedReplacement(
    occurrence: ManagedDirectCarrierReplacementAllocationOccurrence,
    carrier: ManagedDirectCarrier,
    buffer: ByteBuffer,
) {
    val gate = occurrence.operation.settlementGate
    gate.withLock {
        if (!carrier.attachReturnedBufferLocked(gate, buffer)) throw ATTACHMENT_STATE_VIOLATION
        occurrence.ownerBag.carrier = carrier
    }
    try {
        inspectBuffer(buffer, carrier.byteCount, occurrence.validation)
    } catch (fatal: Throwable) {
        publishManagedReplacementFatalAndRethrow(occurrence, fatal)
    }
    val validation = occurrence.validation
    val validationFailure = validation.failure
    val published = gate.withLock {
        if (!carrier.completeAttachmentLocked(gate, validation)) throw ATTACHMENT_STATE_VIOLATION
        occurrence.operation.returnCell.evidence.returnedOwner = carrier
        if (validationFailure == null) {
            occurrence.operation.publishNormalReturn()
        } else {
            occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.InternalFailure
            occurrence.operation.returnCell.evidence.failureCause = validationFailure
            publishInternalFailure(occurrence.operation, validationFailure)
        }
    }
    if (published) signalJpegIoSettlement()
}

private fun JpegRuntimeOwner.publishFailure(operation: OperationOccurrence<JpegPreparationEvidence>, failure: JpegRuntimeFailure, cause: Throwable) {
    operation.returnCell.evidence.failure = failure
    operation.returnCell.evidence.failureCause = cause
    val published = when (cause) {
        is Exception -> operation.publishThrownReturn(cause)
        is OutOfMemoryError -> {
            check(failure == JpegRuntimeFailure.ResourceExhausted)
            operation.publishNormalReturn()
        }

        else -> {
            operation.publishDirectFatalReturn(cause)
            throw cause
        }
    }
    if (published) signalJpegIoSettlement()
}

private fun <R : OperationEvidence> JpegRuntimeOwner.publishInternalFailure(
    operation: OperationOccurrence<R>,
    cause: Throwable,
): Boolean = when (cause) {
    is Exception -> operation.publishThrownReturn(cause)
    else -> {
        operation.publishDirectFatalReturn(cause)
        throw cause
    }
}

private fun JpegRuntimeOwner.publishPreparationFatalAndRethrow(
    occurrence: JpegPreparationOccurrence,
    fatal: Throwable,
): Nothing {
    if (occurrence.operation.publishDirectFatalReturn(fatal)) signalJpegIoSettlement()
    throw fatal
}

private fun JpegRuntimeOwner.publishNativeFreeFatalAndRethrow(
    occurrence: NativeCarrierFreeOccurrence,
    fatal: Throwable,
): Nothing {
    if (occurrence.operation.publishDirectFatalReturn(fatal)) signalJpegIoSettlement()
    throw fatal
}

private fun JpegRuntimeOwner.publishNativeReplacementFatalAndRethrow(
    occurrence: NativeCarrierReplacementAllocationOccurrence,
    fatal: Throwable,
): Nothing {
    if (occurrence.operation.publishDirectFatalReturn(fatal)) signalJpegIoSettlement()
    throw fatal
}

private fun JpegRuntimeOwner.publishManagedReplacementFatalAndRethrow(
    occurrence: ManagedDirectCarrierReplacementAllocationOccurrence,
    fatal: Throwable,
): Nothing {
    if (occurrence.operation.publishDirectFatalReturn(fatal)) signalJpegIoSettlement()
    throw fatal
}

private fun JpegRuntimeOwner.inspectBuffer(buffer: ByteBuffer, expectedByteCount: Int, validation: CarrierValidation) {
    try {
        val direct = buffer.isDirect
        val readOnly = buffer.isReadOnly
        val capacity = buffer.capacity()
        val position = buffer.position()
        val limit = buffer.limit()
        val remaining = buffer.remaining()
        val structurallyFreeable = direct && !readOnly && capacity == expectedByteCount
        val ready = structurallyFreeable && position == 0 && limit == expectedByteCount && remaining == expectedByteCount
        validation.structurallyFreeable = structurallyFreeable
        validation.ready = ready
        validation.failure = if (ready) null else INVALID_CARRIER_BUFFER
    } catch (failure: Exception) {
        validation.structurallyFreeable = false
        validation.ready = false
        validation.failure = failure
    } catch (fatal: Throwable) {
        validation.structurallyFreeable = false
        validation.ready = false
        validation.failure = fatal
        throw fatal
    }
}

private val INVALID_CARRIER_BUFFER: IllegalStateException =
    IllegalStateException("carrier allocator returned a malformed direct buffer")
private val MISSING_LOADER_FAILURE: IllegalStateException =
    IllegalStateException("native JPEG loader failed without a cause")
private val ATTACHMENT_STATE_VIOLATION: IllegalStateException =
    IllegalStateException("carrier attachment transaction is not current")
