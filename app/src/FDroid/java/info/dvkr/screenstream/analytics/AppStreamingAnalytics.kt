package info.dvkr.screenstream.analytics

import io.screenstream.analytics.StreamingAnalytics
import io.screenstream.analytics.StreamingAnalyticsEvent
import org.koin.core.annotation.Singleton

@Singleton(binds = [StreamingAnalytics::class])
public class AppStreamingAnalytics : StreamingAnalytics {
    override fun logEvent(event: StreamingAnalyticsEvent): Unit = Unit
}
