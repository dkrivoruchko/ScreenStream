package info.dvkr.screenstream

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingAnalyticsEvent
import info.dvkr.screenstream.common.analytics.StreamingAnalyticsMappers
import info.dvkr.screenstream.common.analytics.StreamingAnalyticsSchema

public class AppStreamingAnalytics(context: Context) : StreamingAnalytics {

    private val firebaseAnalytics: FirebaseAnalytics = FirebaseAnalytics.getInstance(context)

    override fun logEvent(event: StreamingAnalyticsEvent) {
        when (event) {
            is StreamingAnalyticsEvent.StreamStartAttempt -> {
                firebaseAnalytics.logEvent(
                    StreamingAnalyticsSchema.EventName.STREAM_START_ATTEMPT,
                    Bundle().apply {
                        putString(StreamingAnalyticsSchema.ParamName.STREAM_MODE, event.streamMode.analyticsValue)
                        putString(StreamingAnalyticsSchema.ParamName.ENTRY_POINT, event.entryPoint.analyticsValue)
                    }
                )
            }

            is StreamingAnalyticsEvent.StreamStarted -> {
                firebaseAnalytics.logEvent(
                    StreamingAnalyticsSchema.EventName.STREAM_STARTED,
                    Bundle().apply {
                        putString(StreamingAnalyticsSchema.ParamName.STREAM_MODE, event.streamMode.analyticsValue)
                        putString(StreamingAnalyticsSchema.ParamName.ENTRY_POINT, event.entryPoint.analyticsValue)
                    }
                )
            }

            is StreamingAnalyticsEvent.StreamStartFailed -> {
                firebaseAnalytics.logEvent(
                    StreamingAnalyticsSchema.EventName.STREAM_START_FAILED,
                    Bundle().apply {
                        putString(StreamingAnalyticsSchema.ParamName.STREAM_MODE, event.streamMode.analyticsValue)
                        putString(StreamingAnalyticsSchema.ParamName.ENTRY_POINT, event.entryPoint.analyticsValue)
                        putString(StreamingAnalyticsSchema.ParamName.START_FAIL_GROUP, event.startFailGroup.analyticsValue)
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
