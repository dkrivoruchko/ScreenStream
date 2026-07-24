package io.screenstream.engine.internal.storage

import io.screenstream.engine.ScreenCaptureEffectiveParameters
import java.util.concurrent.atomic.AtomicBoolean

internal class UnpublishedEncodedFrame internal constructor(
    internal val productionId: Long,
    internal val transaction: ManagedEncodedTransaction,
    internal val payload: ImmutableEncodedPayload,
) {
    init {
        require(productionId > 0L)
        require(transaction.state == EncodedTransactionState.Committed)
        require(transaction.committedPayload() === payload)
    }
}

internal class PublishedEncodedFrame internal constructor(
    internal val payload: ImmutableEncodedPayload,
    internal val effectiveParameters: ScreenCaptureEffectiveParameters,
    internal val sequence: Long,
    internal val timestampElapsedRealtimeNanos: Long,
) {
    init {
        require(sequence > 0L)
        require(timestampElapsedRealtimeNanos >= 0L)
    }
}

/** A precreated identity fact returned at most once by [EncodedPayloadLease.release]. */
internal class EncodedPayloadLeaseRelease internal constructor(
    private val lease: EncodedPayloadLease,
) {
    private var consumed: Boolean = false

    internal val registrationId: Long
        get() = lease.registrationId

    internal fun names(expectedLease: EncodedPayloadLease): Boolean = lease === expectedLease

    internal fun consume(expectedLease: EncodedPayloadLease): Boolean {
        if (consumed || lease !== expectedLease) return false
        consumed = true
        return true
    }

    /** Physical settlement after [FrameStore.transferTerminal] detached the exact logical lease role. */
    internal fun settleDetached(expectedLease: EncodedPayloadLease): Boolean {
        if (!expectedLease.isReleased() || !consume(expectedLease)) return false
        return expectedLease.detachConsumedPayload()
    }
}

internal class EncodedPayloadLease internal constructor(
    internal val registrationId: Long,
    publishedFrame: PublishedEncodedFrame,
) {
    init {
        require(registrationId > 0L)
    }

    private val released: AtomicBoolean = AtomicBoolean(false)
    private val releaseFact: EncodedPayloadLeaseRelease = EncodedPayloadLeaseRelease(this)
    private var publishedFrameSlot: PublishedEncodedFrame? = publishedFrame

    internal val publishedFrame: PublishedEncodedFrame
        get() = checkNotNull(publishedFrameSlot)

    internal val byteCount: Int
        get() = publishedFrame.payload.byteCount

    internal val effectiveParameters: ScreenCaptureEffectiveParameters
        get() = publishedFrame.effectiveParameters

    internal val sequence: Long
        get() = publishedFrame.sequence

    internal val timestampElapsedRealtimeNanos: Long
        get() = publishedFrame.timestampElapsedRealtimeNanos

    internal fun copyTo(destination: ByteArray, destinationOffset: Int = 0): Int =
        publishedFrame.payload.copyTo(destination, destinationOffset)

    internal fun copyBytes(): ByteArray = publishedFrame.payload.copyBytes()

    internal fun release(): EncodedPayloadLeaseRelease? =
        if (released.compareAndSet(false, true)) releaseFact else null

    internal fun isReleased(): Boolean = released.get()

    internal fun names(payload: ImmutableEncodedPayload): Boolean = publishedFrameSlot?.payload === payload

    internal fun detachConsumedPayload(): Boolean {
        if (!released.get() || publishedFrameSlot == null) return false
        publishedFrameSlot = null
        return true
    }
}

/** Exact outcome of Control's one terminal detachment of all remaining FrameStore roles. */
internal sealed interface FrameStoreTerminalTransfer {
    data object Detached : FrameStoreTerminalTransfer
    data object IdentityMismatch : FrameStoreTerminalTransfer
}

/** Control-confined logical storage for exactly one production, latest, displaced, and delivery lease role. */
internal class FrameStore {
    private sealed interface ProductionRole {
        class Attached(
            val productionId: Long,
            val transaction: ManagedEncodedTransaction,
        ) : ProductionRole

        class Completed(
            val unpublishedFrame: UnpublishedEncodedFrame,
        ) : ProductionRole
    }

    private var productionRole: ProductionRole? = null
    private var latestFrame: PublishedEncodedFrame? = null
    private var displacedPayload: ImmutableEncodedPayload? = null
    private var deliveryLease: EncodedPayloadLease? = null

    internal val attachedProduction: ManagedEncodedTransaction?
        get() = (productionRole as? ProductionRole.Attached)?.transaction

    internal val attachedProductionId: Long?
        get() = (productionRole as? ProductionRole.Attached)?.productionId

    internal val unpublishedProduction: UnpublishedEncodedFrame?
        get() = (productionRole as? ProductionRole.Completed)?.unpublishedFrame

    internal val latest: PublishedEncodedFrame?
        get() = latestFrame

    internal val displaced: ImmutableEncodedPayload?
        get() = displacedPayload

    internal val lease: EncodedPayloadLease?
        get() = deliveryLease

    internal fun attachProduction(productionId: Long, transaction: ManagedEncodedTransaction): Boolean {
        if (productionId <= 0L || productionRole != null || !transaction.isFreshOpen) return false
        productionRole = ProductionRole.Attached(productionId, transaction)
        return true
    }

    internal fun completeProduction(
        expectedProductionId: Long,
        expectedTransaction: ManagedEncodedTransaction,
    ): UnpublishedEncodedFrame? {
        val attached = productionRole as? ProductionRole.Attached ?: return null
        if (attached.productionId != expectedProductionId || attached.transaction !== expectedTransaction) return null

        val payload = expectedTransaction.committedPayload() ?: return null
        val unpublished = UnpublishedEncodedFrame(expectedProductionId, expectedTransaction, payload)
        check(expectedTransaction.transferCommittedPayload(payload))
        productionRole = ProductionRole.Completed(unpublished)
        return unpublished
    }

    /** Transfers an attached transaction out of the production role without inferring producer safety. */
    internal fun detachProduction(
        expectedProductionId: Long,
        expectedTransaction: ManagedEncodedTransaction,
    ): ManagedEncodedTransaction? {
        val attached = productionRole as? ProductionRole.Attached ?: return null
        if (attached.productionId != expectedProductionId || attached.transaction !== expectedTransaction) return null
        if (expectedTransaction.state == EncodedTransactionState.Committed) return null
        productionRole = null
        return expectedTransaction
    }

    internal fun retireUnpublished(expectedUnpublished: UnpublishedEncodedFrame): Boolean {
        val completed = productionRole as? ProductionRole.Completed ?: return false
        if (completed.unpublishedFrame !== expectedUnpublished) return false
        productionRole = null
        return true
    }

    internal fun publish(
        expectedUnpublished: UnpublishedEncodedFrame,
        expectedLatest: PublishedEncodedFrame?,
        effectiveParameters: ScreenCaptureEffectiveParameters,
        sequence: Long,
        timestampElapsedRealtimeNanos: Long,
    ): PublishedEncodedFrame? {
        val completed = productionRole as? ProductionRole.Completed ?: return null
        if (completed.unpublishedFrame !== expectedUnpublished || latestFrame !== expectedLatest) return null

        val publication = PublishedEncodedFrame(
            payload = expectedUnpublished.payload,
            effectiveParameters = effectiveParameters,
            sequence = sequence,
            timestampElapsedRealtimeNanos = timestampElapsedRealtimeNanos,
        )
        preserveLeasedLatestBeforeDetaching(publication.payload)
        productionRole = null
        latestFrame = publication
        return publication
    }

    internal fun repeat(
        expectedLatest: PublishedEncodedFrame,
        effectiveParameters: ScreenCaptureEffectiveParameters,
        sequence: Long,
        timestampElapsedRealtimeNanos: Long,
    ): PublishedEncodedFrame? {
        if (latestFrame !== expectedLatest) return null
        val repeated = PublishedEncodedFrame(
            payload = expectedLatest.payload,
            effectiveParameters = effectiveParameters,
            sequence = sequence,
            timestampElapsedRealtimeNanos = timestampElapsedRealtimeNanos,
        )
        latestFrame = repeated
        return repeated
    }

    internal fun retireLatest(expectedLatest: PublishedEncodedFrame): Boolean {
        if (latestFrame !== expectedLatest) return false
        preserveLeasedLatestBeforeDetaching(replacementPayload = null)
        latestFrame = null
        return true
    }

    internal fun acquireLease(
        expectedLatest: PublishedEncodedFrame,
        registrationId: Long,
    ): EncodedPayloadLease? {
        if (registrationId <= 0L || deliveryLease != null || latestFrame !== expectedLatest) return null
        val acquired = EncodedPayloadLease(registrationId, expectedLatest)
        deliveryLease = acquired
        return acquired
    }

    internal fun consumeLeaseRelease(
        expectedRegistrationId: Long,
        release: EncodedPayloadLeaseRelease,
    ): Boolean {
        val active = deliveryLease ?: return false
        if (active.registrationId != expectedRegistrationId ||
            release.registrationId != expectedRegistrationId ||
            !release.names(active) ||
            !active.isReleased()
        ) {
            return false
        }
        if (!release.consume(active)) return false

        deliveryLease = null
        if (displacedPayload != null && active.names(checkNotNull(displacedPayload))) {
            displacedPayload = null
        }
        check(active.detachConsumedPayload())
        return true
    }

    /**
     * Control-only terminal transfer. Every expected role is validated before the first mutation.
     * An outstanding lease remains physically rooted by its exact Delivery handoff after this store
     * detaches its logical lease and displaced/latest suffix.
     */
    internal fun transferTerminal(
        expectedProductionId: Long?,
        expectedAttachedTransaction: ManagedEncodedTransaction?,
        expectedUnpublished: UnpublishedEncodedFrame?,
        expectedLatest: PublishedEncodedFrame?,
        expectedDisplaced: ImmutableEncodedPayload?,
        expectedDeliveryLease: EncodedPayloadLease?,
    ): FrameStoreTerminalTransfer {
        if (!terminalProductionMatches(
                expectedProductionId,
                expectedAttachedTransaction,
                expectedUnpublished,
            ) ||
            latestFrame !== expectedLatest ||
            displacedPayload !== expectedDisplaced ||
            deliveryLease !== expectedDeliveryLease ||
            !terminalLeaseSuffixIsCoherent(expectedLatest, expectedDisplaced, expectedDeliveryLease)
        ) {
            return FrameStoreTerminalTransfer.IdentityMismatch
        }

        productionRole = null
        latestFrame = null
        displacedPayload = null
        deliveryLease = null
        return FrameStoreTerminalTransfer.Detached
    }

    private fun terminalProductionMatches(
        expectedProductionId: Long?,
        expectedAttachedTransaction: ManagedEncodedTransaction?,
        expectedUnpublished: UnpublishedEncodedFrame?,
    ): Boolean = when (val current = productionRole) {
        null -> expectedProductionId == null && expectedAttachedTransaction == null && expectedUnpublished == null
        is ProductionRole.Attached -> expectedProductionId == current.productionId &&
                expectedAttachedTransaction === current.transaction && expectedUnpublished == null

        is ProductionRole.Completed -> expectedProductionId == current.unpublishedFrame.productionId &&
                expectedAttachedTransaction == null && expectedUnpublished === current.unpublishedFrame
    }

    private fun terminalLeaseSuffixIsCoherent(
        expectedLatest: PublishedEncodedFrame?,
        expectedDisplaced: ImmutableEncodedPayload?,
        expectedDeliveryLease: EncodedPayloadLease?,
    ): Boolean {
        if (expectedDeliveryLease == null) return expectedDisplaced == null
        val namesLatest = expectedLatest?.let { expectedDeliveryLease.names(it.payload) } == true
        val namesDisplaced = expectedDisplaced?.let(expectedDeliveryLease::names) == true
        if (!namesLatest && !namesDisplaced) return false
        return expectedDisplaced == null || namesDisplaced
    }

    private fun preserveLeasedLatestBeforeDetaching(replacementPayload: ImmutableEncodedPayload?) {
        val currentLatest = latestFrame ?: return
        if (currentLatest.payload === replacementPayload) return

        val active = deliveryLease
        if (active != null && active.names(currentLatest.payload)) {
            check(displacedPayload == null || displacedPayload === currentLatest.payload)
            displacedPayload = currentLatest.payload
        }
    }
}
