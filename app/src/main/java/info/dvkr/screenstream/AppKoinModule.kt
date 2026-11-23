package info.dvkr.screenstream

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

@Module
@Configuration
@ComponentScan(
    "info.dvkr.screenstream.ads",
    "info.dvkr.screenstream.analytics",
    "info.dvkr.screenstream.logging",
    "info.dvkr.screenstream.review",
    "info.dvkr.screenstream.settings",
    "info.dvkr.screenstream.notification",
)
public class AppKoinModule
