package io.screenstream.mjpeg

import io.screenstream.foundation.logging.Logger
import org.junit.rules.ExternalResource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module

internal val silentMjpegLoggerModule: Module = module {
    factory<Logger> { SilentMjpegLogger }
}

private object SilentMjpegLogger : Logger {
    override fun log(level: Logger.Level, tag: String, msg: String, cause: Throwable?) = Unit
}

internal class MjpegKoinTestRule : ExternalResource() {
    override fun before() {
        startKoin { modules(silentMjpegLoggerModule) }
    }

    override fun after() {
        stopKoin()
    }
}

internal suspend fun <T> withMjpegTestKoin(block: suspend () -> T): T {
    startKoin { modules(silentMjpegLoggerModule) }
    return try {
        block()
    } finally {
        stopKoin()
    }
}
