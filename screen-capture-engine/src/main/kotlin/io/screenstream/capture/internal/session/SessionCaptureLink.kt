package io.screenstream.capture.internal.session

import android.media.projection.MediaProjection
import io.screenstream.capture.internal.capture.CaptureApplyResult
import io.screenstream.capture.internal.capture.CaptureOpenResult
import io.screenstream.capture.internal.capture.CapturePlan
import io.screenstream.capture.internal.capture.CaptureProjectionIdentity
import io.screenstream.capture.internal.capture.CaptureReadResult
import io.screenstream.capture.internal.capture.CaptureSourceIdentity
import io.screenstream.capture.internal.capture.SessionCaptureFactPort
import io.screenstream.capture.internal.capture.SessionCaptureOwner
import io.screenstream.capture.internal.session.production.SessionReadBridge

/**
 * Correlates the coordinator's bounded capture requests with exact returned capture facts.
 *
 * The link records request identity, source opportunity, resize evidence, and the one outstanding
 * raw-frame read. It deliberately owns no capture policy or session-currentness decision; those
 * remain with [SessionCoordinator]. A detached read is retained only until its exact late real return or matching
 * definite pre-entry dispatch rejection settles its loan, without reviving terminal session state.
 */
internal class SessionCaptureLink(
    private val coordinator: SessionCoordinator,
) : SessionCaptureFactPort {
    internal class OpenRequest(internal val plan: CapturePlan)

    internal class OpenFact(internal val request: OpenRequest, internal val result: CaptureOpenResult)

    internal class ApplyRequest(internal val configRevision: Long, internal val plan: CapturePlan)

    internal class ApplyFact(internal val request: ApplyRequest, internal val result: CaptureApplyResult)

    internal class ReadRequest(
        internal val read: SessionReadBridge,
        internal val plan: CapturePlan,
        internal val sourceIdentity: CaptureSourceIdentity,
    )

    internal class ResizeFact(internal val projectionIdentity: CaptureProjectionIdentity, internal val widthPx: Int, internal val heightPx: Int)

    internal enum class ReadReturnAdmission { Recorded, Detached, MismatchOrDuplicate, }

    private lateinit var owner: SessionCaptureOwner
    private var pendingOpen: OpenRequest? = null
    private var pendingApply: ApplyRequest? = null
    private var pendingRead: ReadRequest? = null
    private var openFact: OpenFact? = null
    private var applyFact: ApplyFact? = null
    private var currentPlan: CapturePlan? = null
    private var currentSourceIdentity: CaptureSourceIdentity? = null
    private var projectionIdentity: CaptureProjectionIdentity? = null
    private var latestResizeFact: ResizeFact? = null
    private var latestVisibility: Boolean? = null
    private var sourceOpportunityAvailable = false
    private var returnedRead: SessionReadBridge? = null
    private var rejectedRead: ReadRequest? = null
    private var detachedRead: SessionReadBridge? = null
    private var terminalFrozen = false

    internal fun bindOwnerLocked(candidate: SessionCaptureOwner, projection: MediaProjection) {
        check(!::owner.isInitialized)
        candidate.adoptProjection(projection)
        owner = candidate
    }

    internal fun prepareOpenLocked(plan: CapturePlan): OpenRequest {
        check(!terminalFrozen)
        check((::owner.isInitialized) && (pendingOpen == null) && (pendingApply == null) && (pendingRead == null))
        check((returnedRead == null) && (rejectedRead == null) && (detachedRead == null))
        return OpenRequest(plan).also { pendingOpen = it }
    }

    internal fun executeOpen(request: OpenRequest): Boolean = owner.open(request.plan)

    internal fun settleOpenRejectedLocked(request: OpenRequest): Boolean {
        if (pendingOpen !== request) return false
        pendingOpen = null
        return true
    }

    internal fun recordOpenReturnedLocked(result: CaptureOpenResult): OpenFact? {
        if (terminalFrozen) return null
        val request = pendingOpen ?: return null
        pendingOpen = null
        sourceOpportunityAvailable = false
        when (result) {
            is CaptureOpenResult.Opened -> {
                val existingProjection = projectionIdentity
                check(existingProjection == null || existingProjection === result.projectionIdentity)
                projectionIdentity = result.projectionIdentity
                currentPlan = result.plan
                currentSourceIdentity = result.sourceIdentity
            }

            is CaptureOpenResult.Failed -> {
                projectionIdentity = null
                currentPlan = null
                currentSourceIdentity = null
                latestResizeFact = null
            }

            CaptureOpenResult.CutoffInert -> Unit
        }
        check(openFact == null)
        return OpenFact(request, result).also { openFact = it }
    }

    internal fun takeOpenFactLocked(): OpenFact? = openFact.also { openFact = null }

    internal fun prepareApplyLocked(configRevision: Long, plan: CapturePlan): ApplyRequest {
        check(!terminalFrozen)
        check(::owner.isInitialized && currentPlan != null && currentSourceIdentity != null)
        check(pendingOpen == null && pendingApply == null && pendingRead == null)
        check(returnedRead == null && rejectedRead == null && detachedRead == null)
        return ApplyRequest(configRevision, plan).also { pendingApply = it }
    }

    internal fun executeApply(request: ApplyRequest): Boolean = owner.apply(request.plan)

    internal fun settleApplyRejectedLocked(request: ApplyRequest): Boolean {
        if (pendingApply !== request) return false
        pendingApply = null
        return true
    }

    internal fun recordApplyReturnedLocked(result: CaptureApplyResult): ApplyFact? {
        if (terminalFrozen) return null
        val request = pendingApply ?: return null
        pendingApply = null
        when (result) {
            is CaptureApplyResult.Applied -> {
                currentPlan = result.plan
                val sourceReplaced = currentSourceIdentity !== result.sourceIdentity
                currentSourceIdentity = result.sourceIdentity
                if (sourceReplaced) sourceOpportunityAvailable = false
            }

            is CaptureApplyResult.Failed,
            is CaptureApplyResult.ResourceDenied,
            CaptureApplyResult.CutoffInert,
                -> Unit
        }
        check(applyFact == null)
        return ApplyFact(request, result).also { applyFact = it }
    }

    internal fun takeApplyFactLocked(): ApplyFact? = applyFact.also { applyFact = null }

    internal fun installReadLocked(read: SessionReadBridge): ReadRequest? {
        if (terminalFrozen) return null
        if (pendingOpen != null || pendingApply != null || pendingRead != null || returnedRead != null ||
            rejectedRead != null || detachedRead != null
        ) {
            return null
        }
        if (!sourceOpportunityAvailable) return null
        val plan = currentPlan ?: return null
        val sourceIdentity = currentSourceIdentity ?: return null
        sourceOpportunityAvailable = false
        return ReadRequest(read, plan, sourceIdentity).also { pendingRead = it }
    }

    internal fun executeRead(request: ReadRequest): Boolean =
        owner.read(
            plan = request.plan,
            sourceIdentity = request.sourceIdentity,
            writableCarrier = request.read.writableView,
            returnPort = request.read,
        )

    internal fun settleReadRejectedLocked(read: SessionReadBridge): ReadRequest? {
        val request = pendingRead
        if (request?.read === read) {
            pendingRead = null
            check(read.claimRejectedBeforeEntryLocked())
            check(rejectedRead == null)
            rejectedRead = request
            return request
        }
        return null
    }

    internal fun settleDetachedReadRejectedLocked(read: SessionReadBridge): Boolean {
        if (detachedRead !== read) return false
        detachedRead = null
        check(read.claimRejectedBeforeEntryLocked())
        return true
    }

    internal fun settleRejectedReadLocked(request: ReadRequest, restoreOpportunity: Boolean): Boolean {
        if (rejectedRead !== request) return false
        rejectedRead = null
        if (restoreOpportunity && pendingRead == null && currentPlan === request.plan && currentSourceIdentity === request.sourceIdentity) {
            sourceOpportunityAvailable = true
        }
        return true
    }

    internal fun recordReadReturnedLocked(
        read: SessionReadBridge,
        result: CaptureReadResult,
        restoreSourceOpportunity: Boolean,
    ): ReadReturnAdmission {
        val request = pendingRead
        if (request?.read === read) {
            if (!read.recordReturnLocked(result)) return ReadReturnAdmission.MismatchOrDuplicate
            check(returnedRead == null)
            pendingRead = null
            returnedRead = read
            if (restoreSourceOpportunity && result is CaptureReadResult.Failed && !result.sourceConsumed &&
                currentPlan === request.plan && currentSourceIdentity === request.sourceIdentity
            ) {
                sourceOpportunityAvailable = true
            }
            return ReadReturnAdmission.Recorded
        }
        if (detachedRead === read && read.isDetachedLocked()) {
            if (!read.recordReturnLocked(result)) return ReadReturnAdmission.MismatchOrDuplicate
            detachedRead = null
            return ReadReturnAdmission.Detached
        }
        return ReadReturnAdmission.MismatchOrDuplicate
    }

    internal fun takeReturnedReadLocked(): SessionReadBridge? {
        val read = returnedRead ?: return null
        check(read.claimReturnedLocked())
        returnedRead = null
        return read
    }

    internal fun prevalidateTerminalFreezeLocked(activeRead: SessionReadBridge?): Boolean {
        if (terminalFrozen || returnedRead != null || rejectedRead != null || detachedRead != null) return false
        val pending = pendingRead?.read
        return if (activeRead == null) {
            pending == null
        } else {
            pending === activeRead && activeRead.canDetachLocked()
        }
    }

    internal fun freezeTerminalLocked(activeRead: SessionReadBridge?) {
        check(prevalidateTerminalFreezeLocked(activeRead))
        terminalFrozen = true
        pendingOpen = null
        pendingApply = null
        openFact = null
        applyFact = null
        currentPlan = null
        currentSourceIdentity = null
        projectionIdentity = null
        latestResizeFact = null
        latestVisibility = null
        sourceOpportunityAvailable = false
        if (activeRead != null) {
            pendingRead = null
            activeRead.detachLocked()
            detachedRead = activeRead
        }
    }

    internal fun recordSourceAvailableLocked(identity: CaptureSourceIdentity): Boolean {
        if (terminalFrozen) return false
        if (currentSourceIdentity !== identity) return false
        sourceOpportunityAvailable = true
        return true
    }

    internal fun sourceOpportunityAvailableLocked(): Boolean = sourceOpportunityAvailable

    internal fun correlateProjectionIdentityLocked(identity: CaptureProjectionIdentity): Boolean {
        if (terminalFrozen) return false
        val current = projectionIdentity
        if (current == null && pendingOpen != null) {
            projectionIdentity = identity
            return true
        }
        return current === identity
    }

    internal fun recordResizeFactLocked(identity: CaptureProjectionIdentity, widthPx: Int, heightPx: Int): ResizeFact? {
        if (!correlateProjectionIdentityLocked(identity) || widthPx <= 0 || heightPx <= 0) return null
        return ResizeFact(identity, widthPx, heightPx).also { latestResizeFact = it }
    }

    internal fun takeResizeFactLocked(): ResizeFact? = latestResizeFact.also { latestResizeFact = null }

    internal fun recordVisibilityLocked(identity: CaptureProjectionIdentity, isVisible: Boolean): Boolean {
        if (!correlateProjectionIdentityLocked(identity)) return false
        latestVisibility = isVisible
        return true
    }

    internal fun takeVisibilityLocked(): Boolean? = latestVisibility.also { latestVisibility = null }

    internal fun retire() = owner.retire()

    override fun onOpenReturned(result: CaptureOpenResult) = coordinator.onCaptureOpenReturned(this, result)

    override fun onApplyReturned(result: CaptureApplyResult) = coordinator.onCaptureApplyReturned(this, result)

    override fun onSourceAvailable(sourceIdentity: CaptureSourceIdentity) =
        coordinator.onCaptureSourceAvailable(this, sourceIdentity)

    override fun onProjectionStopped(projectionIdentity: CaptureProjectionIdentity) =
        coordinator.onCaptureProjectionStopped(this, projectionIdentity)

    override fun onCapturedContentResize(projectionIdentity: CaptureProjectionIdentity, widthPx: Int, heightPx: Int) =
        coordinator.onCapturedContentResize(this, projectionIdentity, widthPx, heightPx)

    override fun onCapturedContentVisibilityChanged(projectionIdentity: CaptureProjectionIdentity, isVisible: Boolean) =
        coordinator.onCapturedContentVisibilityChanged(this, projectionIdentity, isVisible)

    override fun onCaptureFailure(failure: Exception) = coordinator.onCaptureFailure(this, failure)
}
