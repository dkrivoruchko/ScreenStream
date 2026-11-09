package info.dvkr.screenstream.app

import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingAnalyticsEvent
import org.koin.core.annotation.Singleton

@Singleton(binds = [StreamingAnalytics::class])
public class AppStreamingAnalytics : StreamingAnalytics {
    override fun logEvent(event: StreamingAnalyticsEvent): Unit = Unit
}
