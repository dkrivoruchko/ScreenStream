package info.dvkr.screenstream.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import io.screenstream.analytics.StreamingAnalytics
import io.screenstream.analytics.StreamingAnalyticsEvent
import io.screenstream.analytics.StreamingAnalyticsMappers
import io.screenstream.analytics.StreamingAnalyticsSchema
import io.screenstream.foundation.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.core.annotation.Singleton
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf

@Singleton(binds = [StreamingAnalytics::class], createdAtStart = true)
public class AppStreamingAnalytics(context: Context) : StreamingAnalytics, KoinComponent {
    private val log: Logger by inject { parametersOf(this) }

    private val firebaseAnalytics: FirebaseAnalytics = FirebaseAnalytics.getInstance(context)
    private val events: Channel<StreamingAnalyticsEvent> = Channel(Channel.UNLIMITED)
    private val exportScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        exportScope.launch {
            for (event in events) {
                try {
                    exportEvent(event)
                } catch (failure: Throwable) {
                    currentCoroutineContext().ensureActive()
                    reportFailure("Streaming analytics export failed", failure)
                }
            }
        }
    }

    override fun logEvent(event: StreamingAnalyticsEvent) {
        try {
            if (events.trySend(event).isFailure) reportFailure("Streaming analytics export queue is closed")
        } catch (failure: Throwable) {
            reportFailure("Streaming analytics event was not accepted", failure)
        }
    }

    private fun reportFailure(message: String, failure: Throwable? = null) {
        try {
            val detail = if (failure == null) message else "$message (${failure.javaClass.simpleName})"
            log.e("analytics", detail)
        } catch (_: Throwable) {
            // Analytics diagnostics must never affect capture or event draining.
        }
    }

    private fun exportEvent(event: StreamingAnalyticsEvent) {
        when (event) {
            is StreamingAnalyticsEvent.StreamStartAttempt -> {
                firebaseAnalytics.logEvent(
                    StreamingAnalyticsSchema.EventName.STREAM_START_ATTEMPT,
                    Bundle().apply {
                        putString(StreamingAnalyticsSchema.ParamName.STREAM_MODE, event.streamMode.analyticsValue)
                        putString(StreamingAnalyticsSchema.ParamName.ENTRY_POINT, event.entryPoint.analyticsValue)
                        putLong(
                            StreamingAnalyticsSchema.ParamName.USED_CACHED_PERMISSION,
                            StreamingAnalyticsMappers.toFlag(event.usedCachedPermission).toLong()
                        )
                        putLong(
                            StreamingAnalyticsSchema.ParamName.PERMISSION_EDUCATION_SHOWN,
                            StreamingAnalyticsMappers.toFlag(event.permissionEducationShown).toLong()
                        )
                    }
                )
            }

            is StreamingAnalyticsEvent.StreamStarted -> {
                firebaseAnalytics.logEvent(
                    StreamingAnalyticsSchema.EventName.STREAM_STARTED,
                    Bundle().apply {
                        putString(StreamingAnalyticsSchema.ParamName.STREAM_MODE, event.streamMode.analyticsValue)
                        putString(StreamingAnalyticsSchema.ParamName.ENTRY_POINT, event.entryPoint.analyticsValue)
                        putLong(
                            StreamingAnalyticsSchema.ParamName.USED_CACHED_PERMISSION,
                            StreamingAnalyticsMappers.toFlag(event.usedCachedPermission).toLong()
                        )
                        putLong(
                            StreamingAnalyticsSchema.ParamName.PERMISSION_EDUCATION_SHOWN,
                            StreamingAnalyticsMappers.toFlag(event.permissionEducationShown).toLong()
                        )
                    }
                )
            }

            is StreamingAnalyticsEvent.StreamStartFailed -> {
                firebaseAnalytics.logEvent(
                    StreamingAnalyticsSchema.EventName.STREAM_START_FAILED,
                    Bundle().apply {
                        putString(StreamingAnalyticsSchema.ParamName.STREAM_MODE, event.streamMode.analyticsValue)
                        putString(StreamingAnalyticsSchema.ParamName.ENTRY_POINT, event.entryPoint.analyticsValue)
                        putLong(
                            StreamingAnalyticsSchema.ParamName.USED_CACHED_PERMISSION,
                            StreamingAnalyticsMappers.toFlag(event.usedCachedPermission).toLong()
                        )
                        putLong(
                            StreamingAnalyticsSchema.ParamName.PERMISSION_EDUCATION_SHOWN,
                            StreamingAnalyticsMappers.toFlag(event.permissionEducationShown).toLong()
                        )
                        putString(StreamingAnalyticsSchema.ParamName.START_FAIL_GROUP, event.startFailGroup.analyticsValue)
                    }
                )
            }

            is StreamingAnalyticsEvent.StreamStartAborted -> {
                firebaseAnalytics.logEvent(
                    StreamingAnalyticsSchema.EventName.STREAM_START_ABORTED,
                    Bundle().apply {
                        putString(StreamingAnalyticsSchema.ParamName.STREAM_MODE, event.streamMode.analyticsValue)
                        putString(StreamingAnalyticsSchema.ParamName.ENTRY_POINT, event.entryPoint.analyticsValue)
                        putLong(
                            StreamingAnalyticsSchema.ParamName.USED_CACHED_PERMISSION,
                            StreamingAnalyticsMappers.toFlag(event.usedCachedPermission).toLong()
                        )
                        putLong(
                            StreamingAnalyticsSchema.ParamName.PERMISSION_EDUCATION_SHOWN,
                            StreamingAnalyticsMappers.toFlag(event.permissionEducationShown).toLong()
                        )
                    }
                )
            }

            is StreamingAnalyticsEvent.FirstConsumerConnected -> {
                firebaseAnalytics.logEvent(
                    StreamingAnalyticsSchema.EventName.FIRST_CONSUMER_CONNECTED,
                    Bundle().apply {
                        putString(StreamingAnalyticsSchema.ParamName.STREAM_MODE, event.streamMode.analyticsValue)
                        putString(StreamingAnalyticsSchema.ParamName.ENTRY_POINT, event.entryPoint.analyticsValue)
                    }
                )
            }

            is StreamingAnalyticsEvent.StreamEnded -> {
                firebaseAnalytics.logEvent(
                    StreamingAnalyticsSchema.EventName.STREAM_ENDED,
                    Bundle().apply {
                        putString(StreamingAnalyticsSchema.ParamName.STREAM_MODE, event.streamMode.analyticsValue)
                        putString(StreamingAnalyticsSchema.ParamName.ENTRY_POINT, event.entryPoint.analyticsValue)
                        putString(StreamingAnalyticsSchema.ParamName.STOP_REASON_GROUP, event.stopReasonGroup.analyticsValue)
                        putString(StreamingAnalyticsSchema.ParamName.DURATION_BUCKET, event.durationBucket.analyticsValue)
                        putString(
                            StreamingAnalyticsSchema.ParamName.MAX_ACTIVE_CONSUMERS_BUCKET,
                            event.maxActiveConsumersBucket.analyticsValue
                        )
                        putLong(
                            StreamingAnalyticsSchema.ParamName.HAD_ACTIVE_CONSUMER,
                            StreamingAnalyticsMappers.toFlag(event.hadActiveConsumer).toLong()
                        )
                        putLong(
                            StreamingAnalyticsSchema.ParamName.SUCCESSFUL_SESSION,
                            StreamingAnalyticsMappers.toFlag(event.successfulSession).toLong()
                        )
                    }
                )
            }
        }
    }
}
