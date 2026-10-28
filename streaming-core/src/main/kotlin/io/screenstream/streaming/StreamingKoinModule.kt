package io.screenstream.streaming

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

@Module
@Configuration
@ComponentScan(
    "io.screenstream.streaming.capture",
    "io.screenstream.streaming.manager",
    "io.screenstream.streaming.settings",
)
public class StreamingKoinModule
