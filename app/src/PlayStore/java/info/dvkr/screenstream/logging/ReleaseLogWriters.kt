package info.dvkr.screenstream.logging

import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.crashlytics.CrashlyticsLogWriter

@OptIn(ExperimentalKermitApi::class)
internal fun releaseLogWriters(): List<LogWriter> = listOf(
    CrashlyticsLogWriter(minSeverity = Severity.Info, minCrashSeverity = Severity.Info),
)
