package info.dvkr.screenstream.app

import android.content.Context
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingAnalyticsEvent
import org.koin.core.annotation.Singleton

@Singleton(binds = [StreamingAnalytics::class], createdAtStart = true)
public class AppStreamingAnalytics(@Suppress("UNUSED_PARAMETER") context: Context) : StreamingAnalytics {
    override fun logEvent(event: StreamingAnalyticsEvent): Unit = Unit
}
