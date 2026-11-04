package io.screenstream.core

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

@Module
@Configuration
@ComponentScan(
    "io.screenstream.core.capture",
    "io.screenstream.core.manager",
    "io.screenstream.core.settings",
)
public class StreamingKoinModule
