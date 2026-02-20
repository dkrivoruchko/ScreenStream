package info.dvkr.screenstream

import android.content.Context
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingAnalyticsEvent

public class AppStreamingAnalytics(@Suppress("UNUSED_PARAMETER") context: Context) : StreamingAnalytics {
    override fun logEvent(event: StreamingAnalyticsEvent): Unit = Unit
}
