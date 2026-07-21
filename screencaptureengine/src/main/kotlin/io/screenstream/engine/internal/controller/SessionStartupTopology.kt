package io.screenstream.engine.internal.controller

import android.content.Context
import android.media.projection.MediaProjection
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.JpegRuntimeOwner
import io.screenstream.engine.internal.android.AndroidCaptureFactSink
import io.screenstream.engine.internal.android.AndroidCaptureOwner
import io.screenstream.engine.internal.android.AndroidLaneStartupResult
import io.screenstream.engine.internal.android.CaptureMetricsIngressPort
import io.screenstream.engine.internal.android.CaptureMetricsOwner
import io.screenstream.engine.internal.gl.GlFiniteOperationIdentity
import io.screenstream.engine.internal.gl.GlPipelineOwner
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.SettlementSignal

internal class SessionMetricsConstructionCommand internal constructor(
    internal val applicationContext: Context,
    internal val source: CaptureMetricsSource?,
    internal val ingress: CaptureMetricsIngressPort,
    internal val clock: EngineClock,
    internal val signal: SettlementSignal,
    internal val attachmentIdentity: Long,
    internal val deadlineIdentity: Long,
    internal val wakeIdentity: Long,
    internal val timeoutCause: Throwable,
    internal val closeIdentity: Long,
)

internal class SessionAndroidConstructionCommand internal constructor(
    internal val projection: MediaProjection,
    internal val projectionOwnerEpoch: Long,
    internal val callbackIdentity: Long,
    internal val clock: EngineClock,
    internal val signal: SettlementSignal,
    internal val factSink: AndroidCaptureFactSink,
)

internal class SessionStartupPrestartCommand internal constructor(
    internal val metrics: CaptureMetricsOwner,
    internal val gl: GlPipelineOwner,
    internal val jpeg: JpegRuntimeOwner,
)

internal class SessionStartupPrestartFacts internal constructor(
    internal val ready: Boolean,
    internal val failure: Throwable?,
)

internal class SessionStartupLaunchCommand internal constructor(
    internal val metrics: CaptureMetricsOwner,
    internal val android: AndroidCaptureOwner,
    internal val gl: GlPipelineOwner,
    internal val construction: GlFiniteOperationIdentity,
    internal val partialCleanup: GlFiniteOperationIdentity,
)

internal class SessionStartupLaunchFacts internal constructor(
    internal val androidStartAccepted: Boolean,
    internal val androidStartup: AndroidLaneStartupResult,
    internal val glCommand: GlPipelineOwner.SessionConstructionCommand?,
)

internal object SessionStartupTopology {
    internal fun constructMetrics(command: SessionMetricsConstructionCommand): CaptureMetricsOwner = CaptureMetricsOwner(
        applicationContext = command.applicationContext,
        configuredSource = command.source,
        ingressPort = command.ingress,
        clock = command.clock,
        settlementSignal = command.signal,
        attachmentIdentity = command.attachmentIdentity,
        readinessDeadlineIdentity = command.deadlineIdentity,
        readinessWakeIdentity = command.wakeIdentity,
        readinessTimeoutCause = command.timeoutCause,
        closeOperationIdentity = command.closeIdentity,
    )

    internal fun constructAndroid(command: SessionAndroidConstructionCommand): AndroidCaptureOwner = AndroidCaptureOwner(
        projection = command.projection,
        projectionOwnerEpoch = command.projectionOwnerEpoch,
        callbackIdentity = command.callbackIdentity,
        clock = command.clock,
        settlementSignal = command.signal,
        factSink = command.factSink,
    )

    internal fun constructGl(clock: EngineClock, signal: SettlementSignal): GlPipelineOwner =
        GlPipelineOwner(clock, signal, "ScreenCaptureEngine-GL")

    internal fun constructJpeg(clock: EngineClock, signal: SettlementSignal): JpegRuntimeOwner =
        JpegRuntimeOwner(clock, signal)

    internal fun constructStorage(): EncodedStorageOwner = EncodedStorageOwner()

    internal fun prestart(command: SessionStartupPrestartCommand): SessionStartupPrestartFacts {
        val metrics = command.metrics.prestartEndpoint()
        val gl = command.gl.prestartLane()
        val jpeg = command.jpeg.prestartJpegEndpoint()
        val ready = metrics == PrivateExecutorStartupDisposition.Ready &&
                gl == PrivateExecutorStartupDisposition.Ready &&
                jpeg == PrivateExecutorStartupDisposition.Ready
        return SessionStartupPrestartFacts(
            ready = ready,
            failure = command.metrics.endpointStartupFailure ?: command.gl.laneStartupFailure ?:
            command.jpeg.jpegEndpointStartupFailure,
        )
    }

    internal fun launch(command: SessionStartupLaunchCommand): SessionStartupLaunchFacts {
        val startAccepted = command.android.startLane()
        val startup = command.android.laneStartupResult
        if (!startAccepted && startup is AndroidLaneStartupResult.Failed) {
            return SessionStartupLaunchFacts(startAccepted, startup, null)
        }
        command.metrics.attach()
        val glCommand = command.gl.prepareSessionConstruction(command.construction, command.partialCleanup)
        return SessionStartupLaunchFacts(startAccepted, startup, glCommand)
    }
}
