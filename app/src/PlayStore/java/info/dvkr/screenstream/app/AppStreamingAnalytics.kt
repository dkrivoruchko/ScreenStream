package info.dvkr.screenstream.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingAnalyticsEvent
import info.dvkr.screenstream.common.analytics.StreamingAnalyticsMappers
import info.dvkr.screenstream.common.analytics.StreamingAnalyticsSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.core.annotation.Singleton

@Singleton(binds = [StreamingAnalytics::class], createdAtStart = true)
public class AppStreamingAnalytics(context: Context) : StreamingAnalytics {

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
                    Log.e("StreamingAnalytics", "Streaming analytics export failed", failure)
                }
            }
        }
    }

    override fun logEvent(event: StreamingAnalyticsEvent) {
        try {
            if (events.trySend(event).isFailure) Log.e("StreamingAnalytics", "Streaming analytics export queue is closed")
        } catch (failure: Throwable) {
            Log.e("StreamingAnalytics", "Streaming analytics event was not accepted", failure)
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
