package io.screenstream.core

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

/**
 * Koin module for Core's process singletons: screen capture access, the module manager, and streaming
 * settings. [Configuration] includes it in `@KoinApplication` apps. The app supplies the Android `Context`
 * and bindings for every registered `StreamingModule`, `StreamingModuleNotifications`, and
 * `ConsentRequestObserver`.
 */
@Module
@Configuration
@ComponentScan(
    "io.screenstream.core.capture",
    "io.screenstream.core.manager",
    "io.screenstream.core.settings",
)
public class StreamingKoinModule
