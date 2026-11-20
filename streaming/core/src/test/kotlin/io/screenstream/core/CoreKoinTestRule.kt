package io.screenstream.core

import io.screenstream.foundation.logging.Logger
import org.junit.rules.ExternalResource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module

private val silentLoggerModule: Module = module {
    factory<Logger> { SilentLogger }
}

private object SilentLogger : Logger {
    override fun log(level: Logger.Level, tag: String, msg: String, cause: Throwable?) = Unit
}

internal class CoreKoinTestRule : ExternalResource() {
    override fun before() {
        startKoin { modules(silentLoggerModule) }
    }

    override fun after() {
        stopKoin()
    }
}
