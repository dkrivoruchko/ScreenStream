package io.screenstream.engine.internal.capture

import android.media.projection.MediaProjection

internal enum class CaptureEntryState {
    Queued,
    Entered,
    CutoffInert,
    Closed,
}

/** Permanent Capture ownership root. All mutation is serialized by the owning Session gate/Capture lane. */
internal class CaptureCapsule internal constructor() {
    internal var entryState: CaptureEntryState = CaptureEntryState.Closed
        private set
    internal var openCommand: OpenCapture? = null
        private set
    internal var acceptedProjection: MediaProjection? = null
        private set
    internal var owner: CaptureOwner? = null
        private set
    internal var currentCommand: CaptureCommand? = null
        private set
    internal var sourceCandidate: SourceCandidate? = null
        private set
    internal var closeRequested: CloseCapture? = null
        private set

    internal val hasUnresolvedOwnership: Boolean
        get() = acceptedProjection != null || owner != null || openCommand != null ||
                currentCommand != null || sourceCandidate != null || closeRequested != null

    internal fun adoptAcceptedProjection(mediaProjection: MediaProjection) {
        check(!hasUnresolvedOwnership && entryState == CaptureEntryState.Closed)
        acceptedProjection = mediaProjection
    }

    internal fun adopt(command: OpenCapture, physicalOwner: CaptureOwner) {
        check(entryState == CaptureEntryState.Closed)
        check(openCommand == null && currentCommand == null && closeRequested == null)
        check(acceptedProjection === command.mediaProjection)
        check(physicalOwner.mediaProjection === command.mediaProjection)
        openCommand = command
        owner = physicalOwner
        currentCommand = command
        entryState = CaptureEntryState.Queued
    }

    internal fun adoptOpened(result: Opened) {
        check(entryState == CaptureEntryState.Entered)
        check(openCommand === result.command && currentCommand === result.command)
        check(result.owner.mediaProjection === result.command.mediaProjection)
        check(owner === result.owner)
        sourceCandidate = SourceCandidate(result.owner.source)
        acceptedProjection = null
        openCommand = null
        currentCommand = null
        entryState = CaptureEntryState.Closed
    }

    internal fun adoptOpenFailed(result: OpenFailed) {
        check(entryState == CaptureEntryState.Entered)
        check(openCommand === result.command && currentCommand === result.command)
        when (val retirement = result.retirement) {
            OpenFailureRetirement.FullyRetired -> {
                acceptedProjection = null
                owner = null
            }

            is OpenFailureRetirement.RetainedLocally -> {
                check(owner === retirement.owner)
                check(retirement.owner.mediaProjection === result.command.mediaProjection)
            }
        }
        openCommand = null
        currentCommand = null
        entryState = CaptureEntryState.Closed
    }

    internal fun queue(command: ApplyPlan) {
        queuePostOpen(command)
    }

    internal fun queue(command: ReadFrame) {
        queuePostOpen(command)
    }

    private fun queuePostOpen(command: CaptureCommand) {
        check(owner != null && acceptedProjection == null && openCommand == null)
        check(entryState == CaptureEntryState.Closed && currentCommand == null && closeRequested == null)
        currentCommand = command
        entryState = CaptureEntryState.Queued
    }

    internal fun markEntered(expected: CaptureCommand): Boolean {
        if (entryState != CaptureEntryState.Queued || currentCommand !== expected) return false
        entryState = CaptureEntryState.Entered
        return true
    }

    internal fun markCutoffInert(expected: CaptureCommand): Boolean {
        if (entryState != CaptureEntryState.Queued || currentCommand !== expected) return false
        entryState = CaptureEntryState.CutoffInert
        return true
    }

    internal fun markCutoffInert(): Boolean {
        val expected = currentCommand ?: return false
        return markCutoffInert(expected)
    }

    internal fun settleCutoff(expected: OpenCapture) {
        check(entryState == CaptureEntryState.CutoffInert)
        check(openCommand === expected && currentCommand === expected)
        openCommand = null
        currentCommand = null
        entryState = CaptureEntryState.Closed
    }

    internal fun settleCutoff(expected: ApplyPlan) {
        settlePostOpenCutoff(expected)
    }

    internal fun settleCutoff(expected: ReadFrame) {
        settlePostOpenCutoff(expected)
    }

    private fun settlePostOpenCutoff(expected: CaptureCommand) {
        check(entryState == CaptureEntryState.CutoffInert && currentCommand === expected)
        currentCommand = null
        entryState = CaptureEntryState.Closed
    }

    internal fun retainCloseCutoff(expected: CloseCapture) {
        check(entryState == CaptureEntryState.CutoffInert)
        check(closeRequested === expected && currentCommand === expected)
        currentCommand = null
    }

    internal fun retainSource(source: CaptureSourceToken): Boolean {
        val candidate = sourceCandidate ?: return false
        if (candidate.source !== source) return false
        candidate.markAvailable()
        return true
    }

    internal fun requestClose(command: CloseCapture) {
        check(owner != null || acceptedProjection != null)
        check(entryState == CaptureEntryState.Closed && currentCommand == null && closeRequested == null)
        closeRequested = command
        currentCommand = command
        entryState = CaptureEntryState.Queued
    }

    internal fun closeOperationAfterRealReturn(result: ApplyPlanResult) {
        val expected = result.command
        check(entryState == CaptureEntryState.Entered || entryState == CaptureEntryState.CutoffInert)
        check(currentCommand === expected)
        if (result is Applied) {
            val currentSource = checkNotNull(sourceCandidate)
            if (currentSource.source !== result.source) {
                sourceCandidate = SourceCandidate(result.source)
            }
        }
        currentCommand = null
        entryState = CaptureEntryState.Closed
    }

    internal fun closeOperationAfterRealReturn(result: ReadFrameResult) {
        closeOperationAfterRealReturn(result.command)
    }

    private fun closeOperationAfterRealReturn(expected: CaptureCommand) {
        check(entryState == CaptureEntryState.Entered || entryState == CaptureEntryState.CutoffInert)
        check(currentCommand === expected)
        currentCommand = null
        entryState = CaptureEntryState.Closed
    }

    internal fun closeCaptureAfterRealReturn(result: CaptureClosed) {
        val expected = result.command
        check(entryState == CaptureEntryState.Entered)
        check(closeRequested === expected && currentCommand === expected)
        currentCommand = null
        closeRequested = null
        openCommand = null
        acceptedProjection = null
        owner = null
        sourceCandidate = null
        entryState = CaptureEntryState.Closed
    }

    internal fun retainCaptureAfterRealReturn(result: CaptureRetainedLocally) {
        val expected = result.command
        check(entryState == CaptureEntryState.Entered)
        check(closeRequested === expected && currentCommand === expected)
        currentCommand = null
        entryState = CaptureEntryState.Closed
    }
}

internal sealed interface CaptureRetirement {
    data object Closed : CaptureRetirement

    class ReturnExpected internal constructor(
        internal val capsule: CaptureCapsule,
    ) : CaptureRetirement

    class ProcessLifetimeResidue internal constructor(
        internal val capsule: CaptureCapsule,
    ) : CaptureRetirement
}
