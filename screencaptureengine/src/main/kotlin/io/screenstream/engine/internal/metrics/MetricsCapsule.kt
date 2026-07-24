package io.screenstream.engine.internal.metrics

import io.screenstream.engine.CaptureMetricsObserver
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.CaptureMetricsSubscription

internal enum class MetricsEntryState {
    Queued,
    Entered,
    CutoffInert,
    Closed,
}

internal enum class MetricsEntryKind {
    Attachment,
    Close,
}

internal sealed interface MetricsHandleOwnership {
    data object NotAttached : MetricsHandleOwnership
    data object AwaitingReturn : MetricsHandleOwnership
    data object ReturnedWithoutHandle : MetricsHandleOwnership

    class Adopted internal constructor(
        internal val handle: CaptureMetricsSubscription,
    ) : MetricsHandleOwnership

    class CloseQueued internal constructor(
        internal val handle: CaptureMetricsSubscription,
    ) : MetricsHandleOwnership

    class CloseEntered internal constructor(
        internal val handle: CaptureMetricsSubscription,
    ) : MetricsHandleOwnership

    class CloseFailed internal constructor(
        internal val handle: CaptureMetricsSubscription,
    ) : MetricsHandleOwnership

    data object Closed : MetricsHandleOwnership
}

/**
 * Permanent Metrics ownership root. Mutation is serialized by the owning Session gate or by the entered
 * Metrics role before its closed result is offered back to that gate.
 */
internal class MetricsCapsule internal constructor() {
    internal var entryState: MetricsEntryState = MetricsEntryState.Closed
        private set
    internal var entryKind: MetricsEntryKind? = null
        private set
    internal var source: CaptureMetricsSource? = null
        private set
    internal var observer: CaptureMetricsObserver? = null
        private set
    internal var handleOwnership: MetricsHandleOwnership = MetricsHandleOwnership.NotAttached
        private set
    internal var closeRequested: Boolean = false
        private set
    internal var builtInObservation: BuiltInCaptureMetricsObservation? = null
        private set

    internal val hasUnresolvedOwnership: Boolean
        get() = source != null || observer != null ||
                handleOwnership != MetricsHandleOwnership.NotAttached &&
                handleOwnership != MetricsHandleOwnership.Closed

    internal fun queueAttachment(
        source: CaptureMetricsSource,
        observer: CaptureMetricsObserver,
        builtInObservation: BuiltInCaptureMetricsObservation? = null,
    ) {
        check(entryState == MetricsEntryState.Closed)
        check(this.source == null && this.observer == null)
        check(handleOwnership == MetricsHandleOwnership.NotAttached)
        this.source = source
        this.observer = observer
        this.builtInObservation = builtInObservation
        handleOwnership = MetricsHandleOwnership.AwaitingReturn
        entryKind = MetricsEntryKind.Attachment
        entryState = MetricsEntryState.Queued
    }

    internal fun recordAttachmentFailure(expectedSource: CaptureMetricsSource) {
        check(entryState == MetricsEntryState.Entered && entryKind == MetricsEntryKind.Attachment)
        check(source === expectedSource && handleOwnership == MetricsHandleOwnership.AwaitingReturn)
        handleOwnership = MetricsHandleOwnership.ReturnedWithoutHandle
        entryKind = null
        entryState = MetricsEntryState.Closed
    }

    internal fun recordAttachmentNotCalled(expectedSource: CaptureMetricsSource) {
        recordAttachmentFailure(expectedSource)
        source = null
        observer = null
        builtInObservation = null
    }

    internal fun adoptBuiltInObservation(
        expectedSource: CaptureMetricsSource,
        observation: BuiltInCaptureMetricsObservation,
    ) {
        check(entryState == MetricsEntryState.Entered && entryKind == MetricsEntryKind.Attachment)
        check(source === expectedSource && builtInObservation == null)
        builtInObservation = observation
    }

    internal fun markAttachmentEntered(expectedSource: CaptureMetricsSource): Boolean {
        if (entryState != MetricsEntryState.Queued || entryKind != MetricsEntryKind.Attachment) return false
        if (source !== expectedSource) return false
        entryState = MetricsEntryState.Entered
        return true
    }

    internal fun recordAttachmentDispatchFailure(expectedSource: CaptureMetricsSource) {
        check(entryState == MetricsEntryState.Queued && entryKind == MetricsEntryKind.Attachment)
        check(source === expectedSource && handleOwnership == MetricsHandleOwnership.AwaitingReturn)
        handleOwnership = MetricsHandleOwnership.ReturnedWithoutHandle
        source = null
        observer = null
        builtInObservation = null
        entryKind = null
        entryState = MetricsEntryState.Closed
    }

    /** A normally returned handle is adopted even after cutoff or a prior semantic failure. */
    internal fun recordAttachmentReturn(
        expectedSource: CaptureMetricsSource,
        returnedHandle: CaptureMetricsSubscription?,
    ) {
        check(entryState == MetricsEntryState.Entered && entryKind == MetricsEntryKind.Attachment)
        check(source === expectedSource && handleOwnership == MetricsHandleOwnership.AwaitingReturn)
        handleOwnership = if (returnedHandle == null) {
            MetricsHandleOwnership.ReturnedWithoutHandle
        } else {
            MetricsHandleOwnership.Adopted(returnedHandle)
        }
        entryKind = null
        entryState = MetricsEntryState.Closed
    }

    internal fun requestClose() {
        closeRequested = true
    }

    /** Returns the exact handle only when its one close call can now be dispatched. */
    internal fun queueCloseIfReady(): CaptureMetricsSubscription? {
        if (!closeRequested) return null
        if (entryState != MetricsEntryState.Closed || entryKind != null) return null
        val adopted = handleOwnership as? MetricsHandleOwnership.Adopted ?: return null
        val handle = adopted.handle
        handleOwnership = MetricsHandleOwnership.CloseQueued(handle)
        entryKind = MetricsEntryKind.Close
        entryState = MetricsEntryState.Queued
        return handle
    }

    internal fun markCloseEntered(expectedHandle: CaptureMetricsSubscription): Boolean {
        if (entryState != MetricsEntryState.Queued || entryKind != MetricsEntryKind.Close) return false
        val queued = handleOwnership as? MetricsHandleOwnership.CloseQueued ?: return false
        if (queued.handle !== expectedHandle) return false
        handleOwnership = MetricsHandleOwnership.CloseEntered(expectedHandle)
        entryState = MetricsEntryState.Entered
        return true
    }

    internal fun recordCloseDispatchFailure(expectedHandle: CaptureMetricsSubscription) {
        check(entryState == MetricsEntryState.Queued && entryKind == MetricsEntryKind.Close)
        val queued = handleOwnership as? MetricsHandleOwnership.CloseQueued
        check(queued?.handle === expectedHandle)
        handleOwnership = MetricsHandleOwnership.CloseFailed(expectedHandle)
        entryKind = null
        entryState = MetricsEntryState.Closed
    }

    /** Normal return is the sole exact close receipt. */
    internal fun recordCloseReturn(expectedHandle: CaptureMetricsSubscription) {
        check(entryState == MetricsEntryState.Entered && entryKind == MetricsEntryKind.Close)
        val entered = handleOwnership as? MetricsHandleOwnership.CloseEntered
        check(entered?.handle === expectedHandle)
        handleOwnership = MetricsHandleOwnership.Closed
        source = null
        observer = null
        builtInObservation = null
        entryKind = null
        entryState = MetricsEntryState.Closed
    }

    /** A returned failure closes the operation but truthfully retains unresolved handle ownership. */
    internal fun recordCloseFailure(expectedHandle: CaptureMetricsSubscription) {
        check(entryState == MetricsEntryState.Entered && entryKind == MetricsEntryKind.Close)
        val entered = handleOwnership as? MetricsHandleOwnership.CloseEntered
        check(entered?.handle === expectedHandle)
        handleOwnership = MetricsHandleOwnership.CloseFailed(expectedHandle)
        entryKind = null
        entryState = MetricsEntryState.Closed
    }

    internal fun markCutoffInert(): Boolean {
        if (entryState != MetricsEntryState.Queued) return false
        entryState = MetricsEntryState.CutoffInert
        return true
    }

    internal fun closeCutoffInertEntry() {
        check(entryState == MetricsEntryState.CutoffInert)
        entryKind = null
        entryState = MetricsEntryState.Closed
        if (handleOwnership == MetricsHandleOwnership.AwaitingReturn) {
            handleOwnership = MetricsHandleOwnership.ReturnedWithoutHandle
            source = null
            observer = null
            builtInObservation = null
        }
    }
}

internal sealed interface MetricsRetirement {
    data object Closed : MetricsRetirement

    class ReturnExpected internal constructor(
        internal val capsule: MetricsCapsule,
    ) : MetricsRetirement

    class ProcessLifetimeResidue internal constructor(
        internal val capsule: MetricsCapsule,
    ) : MetricsRetirement
}
