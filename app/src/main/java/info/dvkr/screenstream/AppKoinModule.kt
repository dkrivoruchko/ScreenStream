package info.dvkr.screenstream

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

@Module
@Configuration
@ComponentScan("info.dvkr.screenstream.app", "info.dvkr.screenstream.settings", "info.dvkr.screenstream.notification", "info.dvkr.screenstream.ui")
public class AppKoinModule
