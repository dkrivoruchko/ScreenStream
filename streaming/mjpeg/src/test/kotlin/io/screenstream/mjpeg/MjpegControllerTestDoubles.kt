package io.screenstream.mjpeg

import android.app.Notification
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Looper
import androidx.core.app.NotificationCompat
import info.dvkr.screenstream.common.analytics.EntryPoint
import info.dvkr.screenstream.common.analytics.SessionKey
import info.dvkr.screenstream.common.analytics.StartFailGroup
import info.dvkr.screenstream.common.analytics.StreamMode
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingAnalyticsEvent
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalytics
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsFactory
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsTracker
import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.IpAddressKey
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.networkaddress.NetworkAddressInventory
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.StreamBehaviorSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

internal class RecordingManagerHandle(
    private val delegate: StreamingModule.Controller.ManagerHandle? = null,
) : StreamingModule.Controller.ManagerHandle {
    override val instanceId = delegate?.instanceId ?: StreamingModule.InstanceId(StreamingModule.Id("MJPEG"))
    override val foregroundSlot: StreamingModuleNotifications.ForegroundSlot = delegate?.foregroundSlot ?: MjpegStreamingModule().foregroundSlot

    @Volatile
    var current = true
    val status = MutableStateFlow<StreamingModule.Status?>(null)
    val reports = CopyOnWriteArrayList<Pair<StreamingModule.Status, Long>>()
    val failed = CompletableDeferred<Unit>()
    override fun isCurrent(): Boolean = current && (delegate?.isCurrent() ?: true)
    override fun reportRunning(status: StreamingModule.Status, heartbeatAtUptimeMillis: Long) {
        reports += status to heartbeatAtUptimeMillis
        this.status.value = status
        delegate?.reportRunning(status, heartbeatAtUptimeMillis)
    }

    override fun reportFailed(messageResource: Int?) {
        current = false
        failed.complete(Unit)
        delegate?.reportFailed(messageResource)
    }
}

internal class ControlledAddressMonitor(holdClose: Boolean = false) : NetworkAddressMonitor {
    override val state = MutableStateFlow<NetworkAddressMonitor.State>(NetworkAddressMonitor.State.NotStarted)
    val started = MutableStateFlow(false)
    val closeEntered = CompletableDeferred<Unit>()
    val closeSettled = CompletableDeferred<Unit>()
    val allowCloseToReturn = CompletableDeferred<Unit>().also { if (!holdClose) it.complete(Unit) }
    var closeFailure: Throwable? = null
    override fun start() {
        if (!closeEntered.isCompleted) started.value = true
    }

    override suspend fun close() {
        closeEntered.complete(Unit)
        try {
            allowCloseToReturn.await()
            closeFailure?.let { throw it }
            state.value = NetworkAddressMonitor.State.Closed
        } finally {
            closeSettled.complete(Unit)
        }
    }
}

internal class ControlledCaptureAccess(private val onFinish: () -> Unit = {}) : ScreenCaptureAccess {
    val consentRequests = MutableStateFlow<List<StreamingModule.CaptureAttemptId>>(emptyList())
    val projectionCreationFinishes = MutableStateFlow<List<StreamingModule.CaptureAttemptId>>(emptyList())
    val consentGrant = CompletableDeferred<ScreenCaptureAccess.Grant?>()
    var finishProjectionFailure: Throwable? = null
    override suspend fun requestConsent(
        attempt: StreamingModule.CaptureAttemptId,
        attemptJob: Job,
        prepareForeground: () -> Boolean,
    ): ScreenCaptureAccess.ConsentResult {
        synchronized(this) { consentRequests.value += attempt }
        val grant = consentGrant.await() ?: return ScreenCaptureAccess.ConsentResult.NotGranted(ScreenCaptureAccess.NoGrantReason.Declined)
        return if (withContext(Dispatchers.Main.immediate) { prepareForeground() }) ScreenCaptureAccess.ConsentResult.Granted(grant)
        else ScreenCaptureAccess.ConsentResult.NotGranted(ScreenCaptureAccess.NoGrantReason.ForegroundRejected)
    }

    override fun createProjection(grant: ScreenCaptureAccess.Grant, retryAfterRecentForegroundStart: Boolean): MediaProjection {
        check(!retryAfterRecentForegroundStart)
        return Shadow.newInstanceOf(MediaProjection::class.java)
    }

    override fun finishProjectionCreation(attempt: StreamingModule.CaptureAttemptId) {
        synchronized(this) { projectionCreationFinishes.value += attempt }
        onFinish()
        finishProjectionFailure?.let { throw it }
    }

    override fun saveConsentForReuse(grant: ScreenCaptureAccess.Grant) = Unit
}

internal class ControlledHttpDelivery(
    holdStop: Boolean = false,
    private val onStart: () -> Unit = {},
    private val onJpeg: (Int, (ByteArray) -> Unit) -> Unit = { _, _ -> error("No engine frame is expected") },
) : HttpDelivery {
    data class Configuration(val access: AccessSettings, val postStop: StreamBehaviorSettings.PostStopImage, val web: WebPageSettings)
    data class Plan(val addresses: List<NetworkAddress>, val port: Int)
    data class CaptureUpdate(val id: StreamingModule.CaptureAttemptId, val status: MjpegCaptureSession.State.Status, val generation: Any?)

    override val info = MutableStateFlow(HttpDelivery.Info())
    override val appliedToken: SecretValue? = null
    val configuration = MutableStateFlow<Configuration?>(null)
    val startCalls = MutableStateFlow(0)
    val plan = MutableStateFlow<Plan?>(null)
    val planApplications = MutableStateFlow<List<Plan>>(emptyList())
    val registeredCaptureAttempts = MutableStateFlow<List<StreamingModule.CaptureAttemptId>>(emptyList())
    val captureStatus = MutableStateFlow<Pair<StreamingModule.CaptureAttemptId, MjpegCaptureSession.State.Status>?>(null)
    val captureStatusUpdates = CopyOnWriteArrayList<CaptureUpdate>()
    val serverRetryRequests = CopyOnWriteArrayList<Uuid>()
    val stopEntered = CompletableDeferred<Unit>()
    val stopSettled = CompletableDeferred<Unit>()
    val allowStopToReturn = CompletableDeferred<Unit>().also { if (!holdStop) it.complete(Unit) }

    @Volatile
    var closed = false
    var acceptConfiguration = true
    var acceptCapture = true
    var onCaptureRegistered: (StreamingModule.CaptureAttemptId) -> Unit = {}
    var stopFailure: Throwable? = null
    var initialLogoBytes: ByteArray? = null
    var onConfiguration: () -> Unit = {}
    var serverConfigurationFailure: Throwable? = null
    override suspend fun start(
        accessSettings: AccessSettings,
        postStopImage: StreamBehaviorSettings.PostStopImage,
        webSettings: WebPageSettings,
        logoBytes: ByteArray
    ): Boolean {
        synchronized(this) { startCalls.value += 1 }
        onStart()
        if (closed || configuration.value != null || !acceptConfiguration) return false
        initialLogoBytes = logoBytes
        configuration.value = Configuration(accessSettings, postStopImage, webSettings)
        return true
    }

    override suspend fun configureServers(addresses: List<NetworkAddress>, port: Int) {
        serverConfigurationFailure?.let { throw it }
        if (!closed) {
            val applied = Plan(addresses.toList(), port)
            plan.value = applied
            planApplications.value += applied
        }
    }

    override fun updateConfiguration(
        accessSettings: AccessSettings,
        postStopImage: StreamBehaviorSettings.PostStopImage,
        webSettings: WebPageSettings
    ): Boolean {
        onConfiguration()
        if (closed || configuration.value == null || !acceptConfiguration) return false
        configuration.value = Configuration(accessSettings, postStopImage, webSettings)
        return true
    }

    override fun registerCapture(id: StreamingModule.CaptureAttemptId, initialCaptureGeneration: Any): Boolean = synchronized(this) {
        if (closed || configuration.value == null || !acceptCapture) false else {
            registeredCaptureAttempts.value += id
            onCaptureRegistered(id)
            true
        }
    }

    override fun updateCaptureStatus(id: StreamingModule.CaptureAttemptId, state: MjpegCaptureSession.State.Status, captureGeneration: Any?) {
        captureStatus.value = id to state
        captureStatusUpdates += CaptureUpdate(id, state, captureGeneration)
    }

    override fun offerJpeg(expectedCaptureGeneration: Any, byteCount: Int, copyTo: (ByteArray) -> Unit) = onJpeg(byteCount, copyTo)
    override fun retryServer(id: Uuid) {
        serverRetryRequests += id
    }

    override fun requestStop() {
        closed = true
    }

    override suspend fun stop() {
        requestStop()
        stopEntered.complete(Unit)
        try {
            allowStopToReturn.await(); stopFailure?.let { throw it }
        } finally {
            stopSettled.complete(Unit)
        }
    }
}

internal class RecordingModuleNotifications : StreamingModuleNotifications {
    val actions = CopyOnWriteArrayList<StreamingModuleNotifications.ForegroundAction>()
    val errors = CopyOnWriteArrayList<StreamingModuleNotifications.ErrorContent>()
    override fun buildForeground(action: StreamingModuleNotifications.ForegroundAction): Notification {
        actions += action
        return NotificationCompat.Builder(RuntimeEnvironment.getApplication(), "fixture").setContentTitle("Capture").build()
    }

    override fun updateError(notificationId: Int, content: StreamingModuleNotifications.ErrorContent?) {
        content?.let { errors += it }
    }

    override fun areNotificationsEnabled(channel: StreamingModuleNotifications.Channel): Boolean = true
    override fun channelSettingsIntent(channel: StreamingModuleNotifications.Channel): Intent? = null
}

internal fun observedAddressState(addresses: List<NetworkAddress>): NetworkAddressMonitor.State.Observed =
    NetworkAddressMonitor.State.Observed(NetworkAddressInventory(addresses.groupBy { IpAddressKey(it.ip) }))

internal object DiscardingSessionAnalyticsFactory : StreamingSessionAnalyticsFactory {
    private val sink = object : StreamingAnalytics {
        override fun logEvent(event: StreamingAnalyticsEvent) = Unit
    }

    override fun beginConsent(key: SessionKey, streamMode: StreamMode, entryPoint: EntryPoint): StreamingSessionAnalytics =
        StreamingSessionAnalyticsTracker(sink, streamMode, entryPoint)

    override fun preflightFailed(streamMode: StreamMode, entryPoint: EntryPoint, startFailGroup: StartFailGroup) = Unit
}

/** Main dispatcher for Toast histories whose executor thread must own an Android Looper. */
internal fun createMjpegLooperDispatcher(threadName: String): ExecutorCoroutineDispatcher =
    java.util.concurrent.Executors.newSingleThreadExecutor { command ->
        Thread {
            Looper.prepare()
            try {
                command.run()
            } finally {
                Looper.myLooper()?.quit()
            }
        }.apply { name = threadName; isDaemon = true }
    }.asCoroutineDispatcher()
