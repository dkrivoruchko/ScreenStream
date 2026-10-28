package info.dvkr.screenstream.common.analytics

import android.util.Log

/** One fixed-metadata attempt. Source timestamps preserve the module's existing measurement points. */
public class StreamingSessionAnalyticsTracker(
    private val analytics: StreamingAnalytics,
    private val streamMode: StreamMode,
    private val entryPoint: EntryPoint,
) : StreamingSessionAnalytics {
    private enum class Phase { Provisional, Admitted, Started, Finished }

    private sealed interface StartupOutcome {
        data class Failed(val group: StartFailGroup) : StartupOutcome
        data object Aborted : StartupOutcome
    }

    private val gate: Any = Any()
    private var phase: Phase = Phase.Provisional
    private var consentSettled: Boolean = false
    private var pendingOutcome: StartupOutcome? = null
    private var usedCachedPermission: Boolean = false
    private var permissionEducationShown: Boolean = false
    private var startedAtElapsedMs: Long = 0L
    private var usefulSegmentStartElapsedMs: Long? = null
    private var usefulStreamingMs: Long = 0L
    private var maxActiveConsumers: Int = 0
    private var hadActiveConsumer: Boolean = false
    private val pendingEvents: ArrayDeque<StreamingAnalyticsEvent> = ArrayDeque()
    private var draining: Boolean = false

    /** Only the process consent router admits the observed cached or submitted request. */
    public fun consentAdmitted(usedCachedPermission: Boolean, permissionEducationShown: Boolean): Unit = update {
        if (phase != Phase.Provisional || consentSettled) return@update
        this.usedCachedPermission = usedCachedPermission
        this.permissionEducationShown = permissionEducationShown
        phase = Phase.Admitted
        pendingEvents.addLast(
            StreamingAnalyticsEvent.StreamStartAttempt(
                streamMode = streamMode,
                entryPoint = entryPoint,
                usedCachedPermission = usedCachedPermission,
                permissionEducationShown = permissionEducationShown,
            )
        )
    }

    override fun started(activeConsumers: Int, atElapsedRealtimeMs: Long): Unit = update {
        if (phase != Phase.Admitted || pendingOutcome != null) return@update
        phase = Phase.Started
        startedAtElapsedMs = atElapsedRealtimeMs
        pendingEvents.addLast(
            StreamingAnalyticsEvent.StreamStarted(
                streamMode = streamMode,
                entryPoint = entryPoint,
                usedCachedPermission = usedCachedPermission,
                permissionEducationShown = permissionEducationShown,
            )
        )
        updateActiveConsumers(activeConsumers, atElapsedRealtimeMs)
    }

    override fun startFailed(startFailGroup: StartFailGroup): Unit = finishStartup(StartupOutcome.Failed(startFailGroup))

    override fun startAborted(): Unit = finishStartup(StartupOutcome.Aborted)

    private fun finishStartup(outcome: StartupOutcome): Unit = update {
        if (phase == Phase.Started || phase == Phase.Finished || pendingOutcome != null) return@update
        pendingOutcome = outcome
        if (consentSettled) applyStartupOutcome()
    }

    override fun consumersChanged(activeConsumers: Int, atElapsedRealtimeMs: Long): Unit = update {
        if (phase == Phase.Started) updateActiveConsumers(activeConsumers, atElapsedRealtimeMs)
    }

    override fun ended(stopReasonRaw: String?, activeConsumers: Int, atElapsedRealtimeMs: Long): Unit = update {
        if (phase != Phase.Started) return@update
        updateActiveConsumers(activeConsumers, atElapsedRealtimeMs)
        closeUsefulSegment(atElapsedRealtimeMs)
        phase = Phase.Finished
        val durationMs = (atElapsedRealtimeMs - startedAtElapsedMs).coerceAtLeast(0L)
        pendingEvents.addLast(
            StreamingAnalyticsEvent.StreamEnded(
                streamMode = streamMode,
                entryPoint = entryPoint,
                stopReasonGroup = StreamingAnalyticsMappers.mapStopReasonGroup(stopReasonRaw),
                durationBucket = StreamingAnalyticsMappers.mapDurationBucket(durationMs),
                maxActiveConsumersBucket = StreamingAnalyticsMappers.mapMaxActiveConsumersBucket(maxActiveConsumers),
                hadActiveConsumer = hadActiveConsumer,
                successfulSession = usefulStreamingMs >= MIN_SUCCESSFUL_SESSION_USEFUL_MS,
            )
        )
    }

    /** The app router calls this only from its queued Main settlement barrier. */
    override fun consentCallFinished(): Unit = update {
        if (consentSettled) return@update
        consentSettled = true
        when (phase) {
            Phase.Provisional -> {
                phase = Phase.Finished
                pendingOutcome = null
            }
            Phase.Admitted -> if (pendingOutcome != null) applyStartupOutcome()
            Phase.Started, Phase.Finished -> Unit
        }
    }

    private fun applyStartupOutcome() {
        if (phase != Phase.Admitted) return
        val outcome = pendingOutcome ?: return
        phase = Phase.Finished
        pendingOutcome = null
        pendingEvents.addLast(
            when (outcome) {
                is StartupOutcome.Failed -> StreamingAnalyticsEvent.StreamStartFailed(
                    streamMode = streamMode,
                    entryPoint = entryPoint,
                    usedCachedPermission = usedCachedPermission,
                    permissionEducationShown = permissionEducationShown,
                    startFailGroup = outcome.group,
                )
                StartupOutcome.Aborted -> StreamingAnalyticsEvent.StreamStartAborted(
                    streamMode = streamMode,
                    entryPoint = entryPoint,
                    usedCachedPermission = usedCachedPermission,
                    permissionEducationShown = permissionEducationShown,
                )
            }
        )
    }

    private fun updateActiveConsumers(activeConsumers: Int, now: Long) {
        val count = activeConsumers.coerceAtLeast(0)
        if (count > 0) {
            if (!hadActiveConsumer) {
                hadActiveConsumer = true
                pendingEvents.addLast(StreamingAnalyticsEvent.FirstConsumerConnected(streamMode, entryPoint))
            }
            if (usefulSegmentStartElapsedMs == null) usefulSegmentStartElapsedMs = now
        } else {
            closeUsefulSegment(now)
        }
        maxActiveConsumers = maxOf(maxActiveConsumers, count)
    }

    private fun closeUsefulSegment(now: Long) {
        val start = usefulSegmentStartElapsedMs ?: return
        usefulStreamingMs += (now - start).coerceAtLeast(0L)
        usefulSegmentStartElapsedMs = null
    }

    private inline fun update(change: () -> Unit) {
        val drain = synchronized(gate) {
            change()
            if (pendingEvents.isNotEmpty() && !draining) {
                draining = true
                true
            } else {
                false
            }
        }
        if (drain) drainEvents()
    }

    /** One drainer preserves source order without invoking the sink under the tracker lock. */
    private fun drainEvents() {
        while (true) {
            val event = synchronized(gate) {
                pendingEvents.removeFirstOrNull().also { if (it == null) draining = false }
            } ?: return
            try {
                analytics.logEvent(event)
            } catch (failure: Throwable) {
                // A sink's own cancellation is not cancellation of functional capture work.
                Log.e("StreamingAnalytics", "Streaming analytics sink failed", failure)
            }
        }
    }

    private companion object {
        const val MIN_SUCCESSFUL_SESSION_USEFUL_MS: Long = 60_000L
    }
}
