package info.dvkr.screenstream

import android.content.Context
import info.dvkr.screenstream.common.notification.NotificationHelper
import io.screenstream.streaming.manager.StreamingModuleErrorNotification
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module
@Configuration
public class LegacyKoinModule {

    @Singleton(binds = [StreamingModuleErrorNotification.Display::class])
    internal fun streamingModuleErrorDisplay(
        context: Context,
        notifications: NotificationHelper,
    ): LegacyStreamingModuleErrorDisplay = LegacyStreamingModuleErrorDisplay(context, notifications)
}
