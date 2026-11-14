package info.dvkr.screenstream.app.logging

import android.content.Context
import android.content.pm.ApplicationInfo
import co.touchlab.kermit.LogcatWriter
import co.touchlab.kermit.Logger as KermitLogger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import io.screenstream.foundation.logging.Logger
import io.screenstream.foundation.logging.Logger.Level
import kotlin.coroutines.cancellation.CancellationException
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Singleton

@Singleton
internal class KermitLogging(context: Context) {
    val logger: KermitLogger

    init {
        val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val writers = buildList {
            add(LogcatWriter())
            if (!isDebuggable) addAll(releaseLogWriters())
        }
        logger = KermitLogger(
            config = loggerConfigInit(*writers.toTypedArray(), minSeverity = Severity.Verbose),
            tag = "SSApp",
        )
    }
}

@Factory(binds = [Logger::class])
internal class AppLogger(@InjectedParam owner: Any, shared: KermitLogging) : Logger {
    private val logger = shared.logger
    private val source = "${owner.javaClass.simpleName}#${System.identityHashCode(owner)}"

    override fun log(level: Level, tag: String, msg: String, cause: Throwable?) {
        val severity = when (level) {
            Level.Verbose -> Severity.Verbose
            Level.Debug -> Severity.Debug
            Level.Info -> Severity.Info
            Level.Warn -> Severity.Warn
            Level.Error -> Severity.Error
        }
        try {
            logger.logBlock(severity, logger.tag, cause) {
                "$source.$tag@${Thread.currentThread().name}: $msg"
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (_: Exception) {
            // Diagnostic failures must not change the operation being logged.
        }
    }
}
