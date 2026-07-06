package dev.dmkr.screencaptureengine.internal.session

import dev.dmkr.screencaptureengine.EncodedImageFormat
import dev.dmkr.screencaptureengine.EncodedImageFrame
import dev.dmkr.screencaptureengine.FrameSubscription
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates latest-only frame callback delivery for one active session.
 *
 * The coordinator owns subscription bookkeeping, public snapshot leases, delivery records, dispatcher handoff, watchdogs, and delivery drop classification.
 * Its lock is held only for short state transitions; dispatchers and callback bodies always run outside the lock.
 */
internal class ScreenCaptureFrameDeliveryCoordinator(
    private val config: ScreenCaptureConfig,
    private val callbackEntryGate: Any,
    private val isSessionTerminal: () -> Boolean,
    private val latestFrameBySequence: (Long) -> LatestEncodedFrame?,
    private val onDeliveryDrop: (DeliveryDropKind) -> Unit,
    private val onFrameDeliveryFailure: (ScreenCaptureProblemKind, String, Throwable) -> Unit,
    private val onSlowConsumerPressure: (String) -> Unit,
    private val onSubscriptionStatsChanged: (activeFrameSubscriptions: Int, slowConsumers: Int, version: Long) -> Unit,
    private val coordinationDispatcher: ScreenCaptureFrameDeliveryDispatcher = ScreenCaptureFrameDeliveryDispatcher.EngineOwned(
        threadNamePrefix = "ScreenCaptureDeliveryCoordinator",
        queueCapacity = ENGINE_OWNED_DELIVERY_QUEUE_CAPACITY,
        workerCount = ENGINE_OWNED_COORDINATOR_THREADS,
    ),
    private val deliveryTaskDispatcher: ScreenCaptureFrameDeliveryDispatcher = ScreenCaptureFrameDeliveryDispatcher.EngineOwned(
        threadNamePrefix = "ScreenCaptureDeliveryTask",
        queueCapacity = ENGINE_OWNED_DELIVERY_QUEUE_CAPACITY,
        workerCount = ENGINE_OWNED_COORDINATOR_THREADS,
    ),
    private val defaultCallbackDispatcher: ScreenCaptureFrameDeliveryDispatcher = ScreenCaptureFrameDeliveryDispatcher.EngineOwned(
        threadNamePrefix = "ScreenCaptureFrameDelivery",
        queueCapacity = ENGINE_OWNED_DELIVERY_QUEUE_CAPACITY,
        workerCount = ENGINE_OWNED_CALLBACK_THREADS,
    ),
    private val watchdogSchedulingEnabled: Boolean = true,
) {
    private val lock = Any()
    private val watchdogExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "ScreenCaptureDeliveryWatchdog").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }
    private val callbackDispatcher = config.frameCallbackDispatcher
        ?.let(ScreenCaptureFrameDeliveryDispatcher::CallerOwned)
        ?: defaultCallbackDispatcher
    private val snapshotPool = SnapshotLeasePool(config.publishedSnapshotSlotCount)
    private val subscriptions = LinkedHashMap<Long, CoreFrameSubscription>()
    private val pendingSnapshotCopies = LinkedHashSet<PendingSnapshotCopy>()
    private val activeDeliveryRecords = LinkedHashSet<DeliveryRecord>()
    private var latestSignalSequence: Long? = null
    private var coordinatorTurnQueued = false
    private var closed = false
    private var nextSubscriptionId = 0L
    private var nextDeliveryId = 0L
    private var nextSubscriptionStatsVersion = 0L

    fun register(callback: (EncodedImageFrame) -> Unit): FrameSubscription {
        val subscription: CoreFrameSubscription
        val stats: SubscriptionStats
        synchronized(lock) {
            check(subscriptions.size < MAX_ACTIVE_FRAME_SUBSCRIPTIONS) {
                "A session supports at most $MAX_ACTIVE_FRAME_SUBSCRIPTIONS active frame subscriptions."
            }
            val id = ++nextSubscriptionId
            subscription = CoreFrameSubscription(id = id, callback = callback, owner = this)
            subscriptions[id] = subscription
            stats = subscriptionStatsLocked()
        }
        onSubscriptionStatsChanged(stats.activeFrameSubscriptions, stats.slowConsumers, stats.version)
        return subscription
    }

    fun signalLatestFramePublished(sequence: Long) {
        val shouldDispatch = synchronized(lock) {
            if (closed) return
            latestSignalSequence = sequence
            if (coordinatorTurnQueued) {
                false
            } else {
                coordinatorTurnQueued = true
                true
            }
        }
        if (!shouldDispatch) return
        dispatchCoordinatorTurn()
    }

    fun invalidateLatestFromSession() {
        val staleRecords: List<DeliveryRecord>
        synchronized(lock) {
            if (closed) return
            latestSignalSequence = null
            pendingSnapshotCopies.forEach(PendingSnapshotCopy::markStaleLocked)
            pendingSnapshotCopies.clear()
            staleRecords = retirePreCallbackRecordsLocked()
        }
        staleRecords.forEach { record ->
            record.releaseLease()
            applyDeliveryDrop(DeliveryDropKind.StaleSession, record.subscription)
        }
    }

    private fun dispatchCoordinatorTurn() {
        val dispatchFailure = coordinationDispatcher.dispatchFailure {
            runCoordinatorTurnSafely()
        }
        if (dispatchFailure != null) {
            val retryPending = markCoordinatorTurnFailed()
            onFrameDeliveryFailure(ScreenCaptureProblemKind.FrameDeliveryFailed, "Frame delivery coordinator dispatch failed.", dispatchFailure)
            if (retryPending) dispatchPendingCoordinatorTurn()
        }
    }

    fun closeFromSession() {
        val staleRecords: List<DeliveryRecord>
        val stats: SubscriptionStats
        synchronized(lock) {
            closed = true
            latestSignalSequence = null
            pendingSnapshotCopies.forEach(PendingSnapshotCopy::markStaleLocked)
            pendingSnapshotCopies.clear()
            activeDeliveryRecords.forEach(DeliveryRecord::cancelWatchdogsLocked)
            subscriptions.values.forEach(CoreFrameSubscription::cancelLocked)
            subscriptions.clear()
            staleRecords = retirePreCallbackRecordsLocked()
            stats = subscriptionStatsLocked()
        }
        staleRecords.forEach { record ->
            record.releaseLease()
            applyDeliveryDrop(DeliveryDropKind.StaleSession, record.subscription)
        }
        onSubscriptionStatsChanged(stats.activeFrameSubscriptions, stats.slowConsumers, stats.version)
        watchdogExecutor.shutdown()
        coordinationDispatcher.close()
        deliveryTaskDispatcher.close()
        defaultCallbackDispatcher.close()
    }

    fun cancelSubscription(subscriptionId: Long) {
        val staleRecords: List<DeliveryRecord>
        val stats: SubscriptionStats
        synchronized(lock) {
            val subscription = subscriptions.remove(subscriptionId) ?: return
            subscription.cancelLocked()
            staleRecords = retirePreCallbackRecordsLocked(subscription)
            stats = subscriptionStatsLocked()
        }
        staleRecords.forEach { record ->
            record.releaseLease()
            applyDeliveryDrop(DeliveryDropKind.StaleSession, record.subscription)
        }
        onSubscriptionStatsChanged(stats.activeFrameSubscriptions, stats.slowConsumers, stats.version)
    }

    private fun runCoordinatorTurnSafely() {
        try {
            runCoordinatorTurn()
        } catch (throwable: Throwable) {
            val retryPending = markCoordinatorTurnFailed()
            onFrameDeliveryFailure(ScreenCaptureProblemKind.FrameDeliveryFailed, "Frame delivery coordinator task failed.", throwable)
            if (retryPending) dispatchPendingCoordinatorTurn()
        }
    }

    private fun markCoordinatorTurnFailed(): Boolean =
        synchronized(lock) {
            coordinatorTurnQueued = false
            !closed && latestSignalSequence != null
        }

    private fun dispatchPendingCoordinatorTurn() {
        val shouldDispatch = synchronized(lock) {
            if (closed || latestSignalSequence == null || coordinatorTurnQueued) {
                false
            } else {
                coordinatorTurnQueued = true
                true
            }
        }
        if (shouldDispatch) dispatchCoordinatorTurn()
    }

    private fun runCoordinatorTurn() {
        while (true) {
            val sequence = synchronized(lock) {
                if (closed) {
                    latestSignalSequence = null
                    coordinatorTurnQueued = false
                    return
                }
                val pendingSequence = latestSignalSequence ?: run {
                    coordinatorTurnQueued = false
                    return
                }
                latestSignalSequence = null
                pendingSequence
            }
            val frame = latestFrameBySequence(sequence) ?: continue
            processLatestFrame(frame)
        }
    }

    private fun processLatestFrame(frame: LatestEncodedFrame) {
        val pendingCopy = registerPendingSnapshotCopy(frame) ?: return
        if (!pendingCopy.tryStartCopy()) {
            discardPendingSnapshotCopy(pendingCopy)
            return
        }
        val sharedLease = try {
            snapshotPool.tryAcquireCopied(frame.bytes, pendingCopy.referenceCount)
        } catch (throwable: Throwable) {
            discardPendingSnapshotCopy(pendingCopy)
            onFrameDeliveryFailure(ScreenCaptureProblemKind.AllocationFailed, "Public delivery snapshot copy failed.", throwable)
            null
        }
        if (sharedLease == null) {
            applySnapshotUnavailableDecisions(pendingCopy)
            return
        }
        var leaseTransferred = false
        try {
            val records = materializeDeliveryRecords(pendingCopy, sharedLease)
            leaseTransferred = true
            for (record in records) {
                scheduleDelivery(record, FrameDeliveryMetadata(frame.format, frame.sequence, frame.timestampElapsedRealtimeNanos))
            }
        } finally {
            if (!leaseTransferred) {
                sharedLease.releaseRemainingReferences()
            }
        }
    }

    private fun registerPendingSnapshotCopy(frame: LatestEncodedFrame): PendingSnapshotCopy? =
        synchronized(callbackEntryGate) {
            if (isSessionTerminal()) return@synchronized null
            if (latestFrameBySequence(frame.sequence) == null) return@synchronized null
            synchronized(lock) {
                if (closed) return@synchronized null
                val targetSubscriptions = subscriptions.values.filter(CoreFrameSubscription::isRegisteredLocked)
                if (targetSubscriptions.isEmpty()) return@synchronized null
                val pendingCopy = PendingSnapshotCopy(sequence = frame.sequence, targetSubscriptions = targetSubscriptions)
                pendingSnapshotCopies += pendingCopy
                pendingCopy
            }
        }

    private fun discardPendingSnapshotCopy(pendingCopy: PendingSnapshotCopy) {
        synchronized(lock) {
            pendingCopy.markStaleLocked()
            pendingSnapshotCopies -= pendingCopy
        }
    }

    private fun applySnapshotUnavailableDecisions(pendingCopy: PendingSnapshotCopy) {
        val drops = ArrayList<DeliveryDrop>()
        synchronized(callbackEntryGate) {
            val latestFrameStillCurrent = latestFrameBySequence(pendingCopy.sequence) != null
            synchronized(lock) {
                pendingSnapshotCopies -= pendingCopy
                if (closed || isSessionTerminal() || !latestFrameStillCurrent || !pendingCopy.isActiveLocked()) {
                    pendingCopy.markStaleLocked()
                } else {
                    for (subscription in pendingCopy.targetSubscriptions) {
                        val dropKind = subscription.snapshotUnavailableDropKindLocked()
                        if (dropKind != null) {
                            drops += DeliveryDrop(dropKind, subscription)
                        }
                    }
                }
            }
        }
        drops.forEach { drop -> applyDeliveryDrop(drop.kind, drop.subscription) }
    }

    private fun materializeDeliveryRecords(pendingCopy: PendingSnapshotCopy, sharedLease: SharedSnapshotLease): List<DeliveryRecord> {
        val drops = ArrayList<DeliveryDrop>()
        var records: List<DeliveryRecord> = emptyList()
        var allocationFailure: Throwable? = null
        synchronized(callbackEntryGate) {
            val latestFrameStillCurrent = latestFrameBySequence(pendingCopy.sequence) != null
            synchronized(lock) {
                pendingSnapshotCopies -= pendingCopy
                if (closed || isSessionTerminal() || !latestFrameStillCurrent || !pendingCopy.isActiveLocked()) {
                    pendingCopy.markStaleLocked()
                    sharedLease.releaseUnissuedReferences(pendingCopy.referenceCount)
                } else {
                    val createdRecords = ArrayList<DeliveryRecord>(pendingCopy.referenceCount)
                    var pendingLease: SnapshotLease? = null
                    var scheduledSubscription: CoreFrameSubscription? = null
                    var scheduledDeliveryId = 0L
                    try {
                        for (subscription in pendingCopy.targetSubscriptions) {
                            val deliveryId = ++nextDeliveryId
                            when (subscription.scheduleLocked(deliveryId)) {
                                ScheduleResult.Scheduled -> {
                                    scheduledSubscription = subscription
                                    scheduledDeliveryId = deliveryId
                                    pendingLease = sharedLease.newRef()
                                    createdRecords += DeliveryRecord(subscription = subscription, deliveryId = deliveryId, lease = pendingLease)
                                    scheduledSubscription = null
                                    scheduledDeliveryId = 0L
                                    pendingLease = null
                                }

                                ScheduleResult.Busy -> drops += DeliveryDrop(DeliveryDropKind.SubscriptionBusy, subscription)
                                ScheduleResult.Stale -> Unit
                            }
                        }
                        activeDeliveryRecords += createdRecords
                        records = createdRecords
                        sharedLease.releaseUnissuedReferences(pendingCopy.referenceCount - createdRecords.size)
                    } catch (throwable: Throwable) {
                        val pendingReferenceIssued = pendingLease != null
                        scheduledSubscription?.clearInFlightLocked(scheduledDeliveryId)
                        for (record in createdRecords) {
                            activeDeliveryRecords -= record
                            record.subscription.clearInFlightLocked(record.deliveryId)
                            record.releaseLease()
                        }
                        pendingLease?.release()
                        sharedLease.releaseUnissuedReferences(pendingCopy.referenceCount - createdRecords.size - if (pendingReferenceIssued) 1 else 0)
                        allocationFailure = throwable
                    }
                }
            }
        }
        allocationFailure?.let { throwable ->
            onFrameDeliveryFailure(ScreenCaptureProblemKind.AllocationFailed, "Public delivery record allocation failed.", throwable)
        }
        drops.forEach { drop -> applyDeliveryDrop(drop.kind, drop.subscription) }
        return records
    }

    private fun scheduleDelivery(record: DeliveryRecord, metadata: FrameDeliveryMetadata) {
        val deliveryFailure = deliveryTaskDispatcher.dispatchFailure {
            try {
                deliverScheduledFrame(record, metadata)
            } catch (throwable: Throwable) {
                handleDeliveryTaskFailure(record, throwable)
            }
        }
        if (deliveryFailure != null) {
            val drop = retirePreCallbackDelivery(record, dispatchFailureWhenActive = true)
            if (drop != null) {
                record.releaseLease()
                applyDeliveryDrop(drop.kind, drop.subscription)
                if (drop.kind == DeliveryDropKind.DispatchFailed) {
                    onFrameDeliveryFailure(ScreenCaptureProblemKind.FrameDeliveryFailed, "Frame delivery coordinator dispatch failed.", deliveryFailure)
                }
            }
            return
        }
        schedulePreBodyWatchdog(record)
    }

    private fun deliverScheduledFrame(record: DeliveryRecord, metadata: FrameDeliveryMetadata) {
        if (isSessionTerminal() || !record.subscription.isRegisteredSnapshot()) {
            retireStalePreCallbackDelivery(record)
            return
        }
        if (!isPreCallbackDeliveryActive(record)) return
        if (callbackDispatcher.isCallerOwned) {
            dispatchCallbackThroughEngineBridge(record, metadata)
        } else {
            dispatchCallbackToSelectedDispatcher(record, metadata)
        }
    }

    private fun isPreCallbackDeliveryActive(record: DeliveryRecord): Boolean =
        synchronized(lock) {
            record.isNotStartedLocked() && record.subscription.isScheduledLocked(record.deliveryId)
        }

    private fun dispatchCallbackThroughEngineBridge(record: DeliveryRecord, metadata: FrameDeliveryMetadata) {
        val bridgeFailure = defaultCallbackDispatcher.dispatchFailure { dispatchCancelledBeforeStart ->
            try {
                if (dispatchCancelledBeforeStart) {
                    handlePreCallbackDispatchFailure(record)
                } else {
                    dispatchCallbackToSelectedDispatcher(record, metadata)
                }
            } catch (throwable: Throwable) {
                handlePreCallbackTaskFailure(record, throwable)
            }
        }
        if (bridgeFailure != null) {
            handlePreCallbackDispatchFailure(record, bridgeFailure)
        }
    }

    private fun dispatchCallbackToSelectedDispatcher(record: DeliveryRecord, metadata: FrameDeliveryMetadata) {
        val dispatchFailure = callbackDispatcher.dispatchFailure { dispatchCancelledBeforeStart ->
            try {
                if (dispatchCancelledBeforeStart) {
                    handlePreCallbackDispatchFailure(record)
                    return@dispatchFailure
                }
                val runningWatchdog = scheduleRunningCallbackWatchdogForAdmission(record)
                val borrowedFrame = try {
                    enterCallbackBody(record, metadata, runningWatchdog)
                } catch (throwable: Throwable) {
                    runningWatchdog?.cancel(false)
                    val drop = retirePreCallbackDelivery(record, dispatchFailureWhenActive = true)
                    if (drop != null) {
                        record.releaseLease()
                        applyDeliveryDrop(drop.kind, drop.subscription)
                    }
                    onFrameDeliveryFailure(ScreenCaptureProblemKind.AllocationFailed, "Frame callback snapshot borrow failed.", throwable)
                    return@dispatchFailure
                }
                if (borrowedFrame == null) {
                    runningWatchdog?.cancel(false)
                    retireStalePreCallbackDelivery(record)
                    return@dispatchFailure
                }
                runCallbackBody(record, borrowedFrame)
            } catch (throwable: Throwable) {
                handleCallbackTaskFailure(record, throwable)
            }
        }
        if (dispatchFailure != null) {
            handlePreCallbackDispatchFailure(record, dispatchFailure)
        }
    }

    private fun enterCallbackBody(record: DeliveryRecord, metadata: FrameDeliveryMetadata, runningWatchdog: ScheduledFuture<*>?): BorrowedEncodedImageFrame? =
        synchronized(callbackEntryGate) {
            if (isSessionTerminal()) return@synchronized null
            synchronized(lock) {
                if (record.isNotStartedLocked() && record.subscription.isScheduledLocked(record.deliveryId)) {
                    val borrowedFrame =
                        BorrowedEncodedImageFrame(metadata.format, record.lease.bytes, metadata.sequence, metadata.timestampElapsedRealtimeNanos)
                    if (record.subscription.startRunningLocked(record.deliveryId)) {
                        record.cancelPreBodyWatchdogLocked()
                        record.startRunningLocked(runningWatchdog)
                        borrowedFrame
                    } else {
                        borrowedFrame.invalidate()
                        null
                    }
                } else {
                    null
                }
            }
        }

    private fun runCallbackBody(record: DeliveryRecord, borrowedFrame: BorrowedEncodedImageFrame) {
        var callbackFailure: Throwable? = null
        try {
            record.subscription.callback(borrowedFrame)
        } catch (throwable: Throwable) {
            callbackFailure = throwable
        } finally {
            borrowedFrame.invalidate()
            completeRunningDelivery(record, success = callbackFailure == null)
        }
        if (callbackFailure != null) {
            applyDeliveryDrop(DeliveryDropKind.CallbackThrew, record.subscription)
            onFrameDeliveryFailure(ScreenCaptureProblemKind.FrameCallbackThrew, "Frame callback threw.", callbackFailure)
        }
    }

    private fun completeRunningDelivery(record: DeliveryRecord, success: Boolean) {
        val stats: SubscriptionStats
        synchronized(lock) {
            record.cancelWatchdogsLocked()
            record.finishRunningLocked()
            activeDeliveryRecords -= record
            record.subscription.clearInFlightLocked(record.deliveryId)
            if (success) {
                record.subscription.deliverySucceededLocked()
            }
            stats = subscriptionStatsLocked()
        }
        record.releaseLease()
        onSubscriptionStatsChanged(stats.activeFrameSubscriptions, stats.slowConsumers, stats.version)
    }

    private fun handleDeliveryTaskFailure(record: DeliveryRecord, failure: Throwable) {
        val drop = retirePreCallbackDelivery(record, dispatchFailureWhenActive = true)
        if (drop != null) {
            record.releaseLease()
            applyDeliveryDrop(drop.kind, drop.subscription)
        }
        onFrameDeliveryFailure(ScreenCaptureProblemKind.FrameDeliveryFailed, "Frame delivery task failed.", failure)
    }

    private fun handlePreCallbackTaskFailure(record: DeliveryRecord, failure: Throwable) {
        handlePreCallbackDispatchFailure(record, failure)
        onFrameDeliveryFailure(ScreenCaptureProblemKind.FrameDeliveryFailed, "Frame callback bridge task failed.", failure)
    }

    private fun handleCallbackTaskFailure(record: DeliveryRecord, failure: Throwable) {
        if (!retireRunningDeliveryAfterFailure(record)) {
            handlePreCallbackDispatchFailure(record, failure)
        }
        onFrameDeliveryFailure(ScreenCaptureProblemKind.FrameDeliveryFailed, "Frame callback task failed.", failure)
    }

    private fun retireRunningDeliveryAfterFailure(record: DeliveryRecord): Boolean {
        val stats: SubscriptionStats
        synchronized(lock) {
            if (!record.isRunningLocked()) return false
            record.cancelWatchdogsLocked()
            record.finishRunningLocked()
            activeDeliveryRecords -= record
            record.subscription.clearInFlightLocked(record.deliveryId)
            stats = subscriptionStatsLocked()
        }
        record.releaseLease()
        onSubscriptionStatsChanged(stats.activeFrameSubscriptions, stats.slowConsumers, stats.version)
        return true
    }

    private fun handlePreCallbackDispatchFailure(record: DeliveryRecord, failure: Throwable? = null) {
        val drop = retirePreCallbackDelivery(record, dispatchFailureWhenActive = true)
        if (drop != null) {
            record.releaseLease()
            applyDeliveryDrop(drop.kind, drop.subscription)
            if (failure != null && drop.kind == DeliveryDropKind.DispatchFailed) {
                onFrameDeliveryFailure(ScreenCaptureProblemKind.FrameDeliveryFailed, "Frame callback dispatch failed.", failure)
            }
        }
    }

    private fun retireStalePreCallbackDelivery(record: DeliveryRecord) {
        val drop = retirePreCallbackDelivery(record, dispatchFailureWhenActive = false)
        if (drop != null) {
            record.releaseLease()
            applyDeliveryDrop(drop.kind, drop.subscription)
        }
    }

    private fun retirePreCallbackDelivery(record: DeliveryRecord, dispatchFailureWhenActive: Boolean): DeliveryDrop? =
        synchronized(lock) {
            if (!record.retireBeforeCallbackLocked()) return@synchronized null
            record.cancelWatchdogsLocked()
            activeDeliveryRecords -= record
            record.subscription.clearInFlightLocked(record.deliveryId)
            val kind = if (dispatchFailureWhenActive && !isSessionTerminal() && record.subscription.isRegisteredLocked()) {
                DeliveryDropKind.DispatchFailed
            } else {
                DeliveryDropKind.StaleSession
            }
            DeliveryDrop(kind, record.subscription)
        }

    private fun retirePreCallbackRecordsLocked(subscription: CoreFrameSubscription? = null): List<DeliveryRecord> {
        val records = ArrayList<DeliveryRecord>()
        val iterator = activeDeliveryRecords.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next()
            if ((subscription == null || record.subscription === subscription) && record.retireBeforeCallbackLocked()) {
                record.cancelWatchdogsLocked()
                iterator.remove()
                record.subscription.clearInFlightLocked(record.deliveryId)
                records += record
            }
        }
        return records
    }

    private fun schedulePreBodyWatchdog(record: DeliveryRecord) {
        if (!watchdogSchedulingEnabled) return
        try {
            val future = watchdogExecutor.schedule(
                {
                    val drop = retirePreCallbackDelivery(record, dispatchFailureWhenActive = true)
                    if (drop != null) {
                        record.releaseLease()
                        applyDeliveryDrop(drop.kind, drop.subscription)
                    }
                },
                PRE_BODY_DELIVERY_START_WATCHDOG_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            synchronized(lock) {
                if (!record.attachPreBodyWatchdogLocked(future)) {
                    future.cancel(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            handlePreCallbackDispatchFailure(record)
        }
    }

    private fun scheduleRunningCallbackWatchdogForAdmission(record: DeliveryRecord): ScheduledFuture<*>? =
        scheduleRunningCallbackWatchdog(record, RUNNING_CALLBACK_STUCK_FIRST_DIAGNOSTIC_MILLIS)

    private fun scheduleRunningCallbackWatchdog(record: DeliveryRecord, delayMillis: Long): ScheduledFuture<*>? {
        if (!watchdogSchedulingEnabled) return null
        try {
            val future = watchdogExecutor.schedule(
                {
                    val runningElapsedMillis = record.runningElapsedMillis()
                    if (runningElapsedMillis != null && runningElapsedMillis >= delayMillis) {
                        onSlowConsumerPressure("Frame callback is still running.")
                        attachRepeatRunningCallbackWatchdog(record)
                    } else if (runningElapsedMillis != null) {
                        attachRunningCallbackWatchdog(record, delayMillis - runningElapsedMillis)
                    }
                },
                delayMillis,
                TimeUnit.MILLISECONDS,
            )
            return future
        } catch (_: RejectedExecutionException) {
            // Terminal cleanup may shut the watchdog executor down while a callback is still running.
            return null
        }
    }

    private fun attachRepeatRunningCallbackWatchdog(record: DeliveryRecord) {
        attachRunningCallbackWatchdog(record, RUNNING_CALLBACK_STUCK_REPEAT_DIAGNOSTIC_MILLIS)
    }

    private fun attachRunningCallbackWatchdog(record: DeliveryRecord, delayMillis: Long) {
        val future = scheduleRunningCallbackWatchdog(record, delayMillis) ?: return
        synchronized(lock) {
            if (!record.replaceRunningWatchdogLocked(future)) {
                future.cancel(false)
            }
        }
    }

    private fun DeliveryRecord.runningElapsedMillis(): Long? = synchronized(lock) { runningElapsedMillisLocked() }

    private fun applyDeliveryDrop(kind: DeliveryDropKind, subscription: CoreFrameSubscription?) {
        val emitSlowConsumerPressure: Boolean
        val stats: SubscriptionStats
        synchronized(lock) {
            subscription?.deliveryFailedLocked(kind)
            emitSlowConsumerPressure = subscription?.isSlowLocked(config.slowConsumerThreshold) == true
            stats = subscriptionStatsLocked()
        }
        onDeliveryDrop(kind)
        if (emitSlowConsumerPressure) {
            onSlowConsumerPressure("Frame subscription exceeded the slow-consumer threshold.")
        }
        onSubscriptionStatsChanged(stats.activeFrameSubscriptions, stats.slowConsumers, stats.version)
    }

    private fun subscriptionStatsLocked(): SubscriptionStats =
        SubscriptionStats(
            version = ++nextSubscriptionStatsVersion,
            activeFrameSubscriptions = subscriptions.values.count(CoreFrameSubscription::isRegisteredLocked),
            slowConsumers = subscriptions.values.count { subscription -> subscription.isSlowLocked(config.slowConsumerThreshold) },
        )
}

internal enum class DeliveryDropKind {
    SubscriptionBusy,
    DispatchFailed,
    SnapshotSlotsExhausted,
    CallbackThrew,
    StaleSession,
}

internal class LatestEncodedFrame(
    val format: EncodedImageFormat,
    val bytes: ByteArray,
    val sequence: Long,
    val timestampElapsedRealtimeNanos: Long,
)

private class FrameDeliveryMetadata(
    val format: EncodedImageFormat,
    val sequence: Long,
    val timestampElapsedRealtimeNanos: Long,
)

private class CoreFrameSubscription(
    val id: Long,
    val callback: (EncodedImageFrame) -> Unit,
    private val owner: ScreenCaptureFrameDeliveryCoordinator,
) : FrameSubscription {
    @Volatile
    private var state: SubscriptionState = SubscriptionState.Active
    private var consecutiveDeliveryProblems = 0L

    override fun cancel() {
        owner.cancelSubscription(id)
    }

    fun isRegisteredSnapshot(): Boolean = state != SubscriptionState.Cancelled

    fun isRegisteredLocked(): Boolean = state != SubscriptionState.Cancelled

    fun scheduleLocked(deliveryId: Long): ScheduleResult =
        when (state) {
            SubscriptionState.Active -> {
                state = SubscriptionState.Scheduled(deliveryId)
                ScheduleResult.Scheduled
            }

            is SubscriptionState.Scheduled,
            is SubscriptionState.Running -> ScheduleResult.Busy

            SubscriptionState.Cancelled -> ScheduleResult.Stale
        }

    fun snapshotUnavailableDropKindLocked(): DeliveryDropKind? =
        when (state) {
            SubscriptionState.Active -> DeliveryDropKind.SnapshotSlotsExhausted
            is SubscriptionState.Scheduled,
            is SubscriptionState.Running -> DeliveryDropKind.SubscriptionBusy

            SubscriptionState.Cancelled -> null
        }

    fun isScheduledLocked(deliveryId: Long): Boolean =
        when (val currentState = state) {
            is SubscriptionState.Scheduled if currentState.deliveryId == deliveryId -> true
            else -> false
        }

    fun startRunningLocked(deliveryId: Long): Boolean =
        when (val currentState = state) {
            is SubscriptionState.Scheduled if currentState.deliveryId == deliveryId -> {
                state = SubscriptionState.Running(deliveryId)
                true
            }

            else -> false
        }

    fun isRunningLocked(deliveryId: Long): Boolean =
        when (val currentState = state) {
            is SubscriptionState.Running if currentState.deliveryId == deliveryId -> true
            else -> false
        }

    fun clearInFlightLocked(deliveryId: Long) {
        when (val currentState = state) {
            is SubscriptionState.Scheduled if currentState.deliveryId == deliveryId -> state = SubscriptionState.Active
            is SubscriptionState.Running if currentState.deliveryId == deliveryId -> state = SubscriptionState.Active
            else -> Unit
        }
    }

    fun cancelLocked() {
        state = SubscriptionState.Cancelled
    }

    fun deliverySucceededLocked() {
        consecutiveDeliveryProblems = 0L
    }

    fun deliveryFailedLocked(kind: DeliveryDropKind) {
        when (kind) {
            DeliveryDropKind.SubscriptionBusy,
            DeliveryDropKind.DispatchFailed,
            DeliveryDropKind.CallbackThrew -> consecutiveDeliveryProblems += 1L

            DeliveryDropKind.SnapshotSlotsExhausted,
            DeliveryDropKind.StaleSession -> Unit
        }
    }

    fun isSlowLocked(threshold: Int): Boolean = consecutiveDeliveryProblems >= threshold
}

private sealed interface SubscriptionState {
    data object Active : SubscriptionState
    class Scheduled(val deliveryId: Long) : SubscriptionState
    class Running(val deliveryId: Long) : SubscriptionState
    data object Cancelled : SubscriptionState
}

private enum class ScheduleResult {
    Scheduled,
    Busy,
    Stale,
}

private class PendingSnapshotCopy(val sequence: Long, val targetSubscriptions: List<CoreFrameSubscription>) {
    private val state = AtomicReference(PendingSnapshotCopyState.Pending)

    val referenceCount: Int
        get() = targetSubscriptions.size

    fun tryStartCopy(): Boolean =
        state.compareAndSet(PendingSnapshotCopyState.Pending, PendingSnapshotCopyState.Copying)

    fun markStaleLocked() {
        state.set(PendingSnapshotCopyState.Stale)
    }

    fun isActiveLocked(): Boolean = state.get() != PendingSnapshotCopyState.Stale
}

private enum class PendingSnapshotCopyState {
    Pending,
    Copying,
    Stale,
}

private class DeliveryDrop(val kind: DeliveryDropKind, val subscription: CoreFrameSubscription?)

private class DeliveryRecord(val subscription: CoreFrameSubscription, val deliveryId: Long, val lease: SnapshotLease) {
    private var state = DeliveryState.NotStarted
    private var preBodyWatchdog: ScheduledFuture<*>? = null
    private var runningWatchdog: ScheduledFuture<*>? = null
    private var runningSinceNanos = 0L

    fun isNotStartedLocked(): Boolean = state == DeliveryState.NotStarted

    fun startRunningLocked(runningWatchdog: ScheduledFuture<*>?) {
        state = DeliveryState.Running
        this.runningWatchdog = runningWatchdog
        runningSinceNanos = System.nanoTime()
    }

    fun retireBeforeCallbackLocked(): Boolean =
        when (state) {
            DeliveryState.NotStarted -> {
                state = DeliveryState.Retired
                true
            }

            DeliveryState.Running,
            DeliveryState.Retired -> false
        }

    fun isRunningLocked(): Boolean = state == DeliveryState.Running

    fun runningElapsedMillisLocked(): Long? =
        if (state == DeliveryState.Running) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - runningSinceNanos) else null

    fun finishRunningLocked() {
        state = DeliveryState.Retired
        runningSinceNanos = 0L
    }

    fun attachPreBodyWatchdogLocked(future: ScheduledFuture<*>): Boolean {
        if (state != DeliveryState.NotStarted) return false
        preBodyWatchdog = future
        return true
    }

    fun replaceRunningWatchdogLocked(future: ScheduledFuture<*>): Boolean {
        if (state != DeliveryState.Running) return false
        runningWatchdog = future
        return true
    }

    fun cancelPreBodyWatchdogLocked() {
        preBodyWatchdog?.cancel(false)
        preBodyWatchdog = null
    }

    fun cancelWatchdogsLocked() {
        cancelPreBodyWatchdogLocked()
        runningWatchdog?.cancel(false)
        runningWatchdog = null
    }

    fun releaseLease() {
        lease.release()
    }
}

private enum class DeliveryState {
    NotStarted,
    Running,
    Retired,
}

private class SnapshotLeasePool(private val capacity: Int) {
    private val leased = AtomicInteger()

    fun tryAcquireCopied(sourceBytes: ByteArray, referenceCount: Int): SharedSnapshotLease? {
        require(referenceCount > 0) { "referenceCount must be positive, was $referenceCount" }
        while (true) {
            val current = leased.get()
            if (current >= capacity) return null
            if (leased.compareAndSet(current, current + 1)) {
                return try {
                    SharedSnapshotLease(bytes = sourceBytes.copyOf(), referenceCount = referenceCount, releaseSlot = ::release)
                } catch (throwable: Throwable) {
                    release()
                    throw throwable
                }
            }
        }
    }

    private fun release() {
        leased.decrementAndGet()
    }
}

private class SharedSnapshotLease(val bytes: ByteArray, referenceCount: Int, private val releaseSlot: () -> Unit) {
    private val remainingReferences = AtomicInteger(referenceCount)

    fun newRef(): SnapshotLease = SnapshotLease(bytes = bytes, release = ::releaseReference)

    fun releaseUnissuedReferences(referenceCount: Int) {
        repeat(referenceCount) {
            releaseReference()
        }
    }

    fun releaseRemainingReferences() {
        val remaining = remainingReferences.getAndSet(0)
        check(remaining >= 0) { "Snapshot lease released too many times." }
        if (remaining > 0) {
            releaseSlot.invoke()
        }
    }

    private fun releaseReference() {
        val remaining = remainingReferences.decrementAndGet()
        check(remaining >= 0) { "Snapshot lease released too many times." }
        if (remaining == 0) {
            releaseSlot.invoke()
        }
    }
}

private class SnapshotLease(bytes: ByteArray, release: () -> Unit) {
    private val released = AtomicBoolean(false)
    private var retainedBytes: ByteArray? = bytes
    private var releaseCallback: (() -> Unit)? = release

    val bytes: ByteArray
        get() = checkNotNull(retainedBytes) { "Snapshot lease was already released." }

    fun release() {
        if (released.compareAndSet(false, true)) {
            retainedBytes = null
            val callback = releaseCallback
            releaseCallback = null
            callback?.invoke()
        }
    }
}

private class BorrowedEncodedImageFrame(
    override val format: EncodedImageFormat,
    bytes: ByteArray,
    override val sequence: Long,
    override val timestampElapsedRealtimeNanos: Long,
) : EncodedImageFrame {
    private val valid = AtomicBoolean(true)

    @Volatile
    private var retainedBytes: ByteArray? = bytes

    override val byteCount: Int
        get() = checkedBytes().size

    override fun copyTo(destination: ByteArray, destinationOffset: Int): Int {
        val currentBytes = checkedBytes()
        require(destinationOffset >= 0) { "destinationOffset must be non-negative, was $destinationOffset" }
        require(destination.size - destinationOffset >= currentBytes.size) {
            "destination does not have enough space for ${currentBytes.size} bytes at offset $destinationOffset"
        }
        currentBytes.copyInto(destination, destinationOffset)
        return currentBytes.size
    }

    override fun copyBytes(): ByteArray {
        return checkedBytes().copyOf()
    }

    fun invalidate() {
        if (valid.compareAndSet(true, false)) {
            retainedBytes = null
        }
    }

    private fun checkedBytes(): ByteArray {
        check(valid.get()) { "EncodedImageFrame is valid only during the callback body." }
        return checkNotNull(retainedBytes) { "EncodedImageFrame is valid only during the callback body." }
    }
}

private class SubscriptionStats(val version: Long, val activeFrameSubscriptions: Int, val slowConsumers: Int)

private const val MAX_ACTIVE_FRAME_SUBSCRIPTIONS: Int = 16
private const val ENGINE_OWNED_DELIVERY_QUEUE_CAPACITY: Int = MAX_ACTIVE_FRAME_SUBSCRIPTIONS
private const val ENGINE_OWNED_COORDINATOR_THREADS: Int = 1
private const val ENGINE_OWNED_CALLBACK_THREADS: Int = 2
private const val PRE_BODY_DELIVERY_START_WATCHDOG_MILLIS: Long = 3_000L
private const val RUNNING_CALLBACK_STUCK_FIRST_DIAGNOSTIC_MILLIS: Long = 5_000L
private const val RUNNING_CALLBACK_STUCK_REPEAT_DIAGNOSTIC_MILLIS: Long = 30_000L
