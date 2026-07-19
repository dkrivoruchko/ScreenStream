package io.screenstream.engine.internal.controller

import io.screenstream.engine.internal.AndroidLaneQuitAction
import io.screenstream.engine.internal.GlLaneShutdownAction
import io.screenstream.engine.internal.JpegEndpointShutdownAction
import io.screenstream.engine.internal.MetricsEndpointShutdownAction
import io.screenstream.engine.internal.StorageRetirementAction
import io.screenstream.engine.internal.settlement.FatalThrowablePolicy

internal class SessionTerminalCleanupCommand internal constructor(
    internal val metrics: MetricsEndpointShutdownAction?,
    internal val android: AndroidLaneQuitAction?,
    internal val gl: GlLaneShutdownAction?,
    internal val jpeg: JpegEndpointShutdownAction?,
    internal val storage: StorageRetirementAction?,
)

internal class SessionTerminalCleanupAttemptFacts internal constructor(
    internal val metricsFailure: Throwable?,
    internal val androidFailure: Throwable?,
    internal val glFailure: Throwable?,
    internal val jpegFailure: Throwable?,
    internal val storageFailure: Throwable?,
) {
    internal val hasFailure: Boolean
        get() = metricsFailure != null || androidFailure != null || glFailure != null ||
                jpegFailure != null || storageFailure != null
}

internal object SessionTerminalCleanup {
    internal fun attempt(command: SessionTerminalCleanupCommand): SessionTerminalCleanupAttemptFacts {
        var metricsFailure: Throwable? = null
        var androidFailure: Throwable? = null
        var glFailure: Throwable? = null
        var jpegFailure: Throwable? = null
        var storageFailure: Throwable? = null

        try {
            command.metrics?.runUnlocked()
        } catch (raw: Throwable) {
            metricsFailure = raw
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
        }
        try {
            command.android?.runUnlocked()
        } catch (raw: Throwable) {
            androidFailure = raw
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
        }
        try {
            command.gl?.runUnlocked()
        } catch (raw: Throwable) {
            glFailure = raw
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
        }
        try {
            command.jpeg?.runUnlocked()
        } catch (raw: Throwable) {
            jpegFailure = raw
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
        }
        try {
            command.storage?.runUnlocked()
        } catch (raw: Throwable) {
            storageFailure = raw
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
        }
        return SessionTerminalCleanupAttemptFacts(
            metricsFailure,
            androidFailure,
            glFailure,
            jpegFailure,
            storageFailure,
        )
    }
}
