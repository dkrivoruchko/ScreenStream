package info.dvkr.screenstream

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

@Module
@Configuration
@ComponentScan("info.dvkr.screenstream.app", "info.dvkr.screenstream.notification")
public class AppKoinModule
