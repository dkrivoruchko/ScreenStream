package io.screenstream.mjpeg.http

import android.content.Context
import android.os.SystemClock
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.http.HttpDelivery.Info
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.HttpDelivery.ServerFailure
import io.screenstream.mjpeg.http.HttpDelivery.ServerInfo
import io.screenstream.mjpeg.http.HttpDelivery.ServerState
import io.screenstream.mjpeg.http.media.JpegResponse
import io.screenstream.mjpeg.http.media.JpegStore
import io.screenstream.mjpeg.http.web.WebRoutes
import io.screenstream.mjpeg.http.web.WebRoutes.WebSocketAdmission
import io.screenstream.mjpeg.http.web.WebState
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.streaming.logE
import io.screenstream.streaming.module.StreamingModule.CaptureAttemptId
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Factory
import java.io.IOException
import java.net.BindException
import java.net.Inet6Address
import java.net.SocketException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Owns admission and physical lifetimes under one monitor; JPEG writers never acquire it per frame. */
@Suppress("unused")
@Factory(binds = [HttpDelivery::class])
internal class KtorHttpDelivery(context: Context) : HttpDelivery, WebRoutes.RequestHandler {
    private val controlMonitor = Any()
    private val runtimeJob = SupervisorJob()
    private val runtimeScope = CoroutineScope(runtimeJob + Dispatchers.IO + CoroutineExceptionHandler { _, cause ->
        this@KtorHttpDelivery.logE("Http.work", "HTTP worker failed", cause)
        synchronized(controlMonitor) {
            fatalCause = fatalCause ?: cause
            publishControlInfo()
        }
        requestStop()
    })
    override val info: StateFlow<Info>
        field = MutableStateFlow(Info())
    override val webState: StateFlow<WebState?>
        field = MutableStateFlow(null)
    private val access = HttpAccess()
    private val jpegStore = JpegStore()
    private val webRoutes = WebRoutes(context, this)
    private val plannedServers = LinkedHashMap<Long, PlannedServer>()
    private val socketOwners = HashMap<SocketKey, SocketOwner>()
    private val requests = HttpRequests()
    private var started = false
    private var closing = false
    private var fatalCause: Throwable? = null
    private var cleanupCause: Throwable? = null
    private var pageSettings = WebPageSettings()
    private val sampler = runtimeScope.launch(start = CoroutineStart.LAZY) {
        while (true) {
            delay(STATISTICS_INTERVAL)
            synchronized(controlMonitor) {
                if (closing) return@launch
                sampleStatistics()
            }
        }
    }
    private val cleanup = CoroutineScope(Dispatchers.IO).async(start = CoroutineStart.LAZY) {
        try {
            // CIO calls belong to engine children; WebSocket handlers join their detached default sessions.
            runtimeJob.join()
            synchronized(controlMonitor) {
                socketOwners.clear()
                sampleStatistics()
            }
            synchronized(controlMonitor) { cleanupCause }?.let { throw it }
        } catch (cause: Throwable) {
            if (cause !== synchronized(controlMonitor) { cleanupCause }) {
                this@KtorHttpDelivery.logE("Http.cleanup", "HTTP cleanup failed", cause)
            }
            throw cause
        }
    }

    override val appliedAccessToken: SecretValue?
        get() = synchronized(controlMonitor) { access.accessToken }

    override suspend fun start(
        accessSettings: AccessSettings,
        postStopImage: PostStopImage,
        webSettings: WebPageSettings,
        logoBytes: ByteArray,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val owners = synchronized(controlMonitor) {
            if (started || closing) return false
            jpegStore.installLogo(logoBytes)
            updateConfigurationLocked(accessSettings, postStopImage, webSettings)
            started = true
            publishWebState()
            addSocketOwners()
        }
        launchSocketOwners(owners)
        sampler.start()
        return synchronized(controlMonitor) { !closing }
    }

    override suspend fun configureServers(addresses: List<NetworkAddress>, port: Int) {
        currentCoroutineContext().ensureActive()
        val (owners, listeners, jobsToCancel) = synchronized(controlMonitor) {
            if (closing) return
            require(port in 1..65535)
            require(addresses.map { it.id }.toSet().size == addresses.size) { "Duplicate address identities" }
            require(addresses.map { SocketKey.from(it, port) }.toSet().size == addresses.size) { "Duplicate scoped sockets" }
            val retiringEntries = plannedServers.toMutableMap()
            val plannedEntries = addresses.map { address ->
                val previous = retiringEntries[address.id]
                if (previous != null && previous.port == port) {
                    retiringEntries.remove(address.id)
                    check(previous.key == SocketKey.from(address, port)) { "Address identity changed its scoped IP" }
                    previous.address = address
                    previous
                } else {
                    PlannedServer(address, port)
                }
            }
            plannedServers.clear()
            plannedEntries.forEach { plannedServers[it.address.id] = it }
            val listeners = retiringEntries.values.mapNotNull { it.listener }
            val jobsToCancel = buildList {
                for (entry in retiringEntries.values) {
                    val listener = entry.listener
                    if (listener != null) addAll(requests.cutoff(listener, entry.id, SystemClock.elapsedRealtime()))
                }
            }
            socketOwners.values.forEach { it.changes.trySend(Unit) }
            val owners = addSocketOwners()
            publishControlInfo()
            Triple(owners, listeners, jobsToCancel)
        }
        // Accepted owners start independently of caller cancellation, before fallible cutoff actions.
        launchSocketOwners(owners)
        try {
            listeners.forEach(HttpListener::requestStop)
            jobsToCancel.forEach(Job::cancel)
        } catch (cause: Throwable) {
            logE("Http.cleanup", "HTTP cutoff failed", cause)
            synchronized(controlMonitor) {
                cleanupCause = cleanupCause ?: cause
                fatalCause = fatalCause ?: cause
                publishControlInfo()
            }
            requestStop()
            throw cause
        }
    }

    override fun retryServer(id: Uuid) {
        val retryRequests = synchronized(controlMonitor) {
            if (!started || closing) return
            val entry = plannedServers.values.firstOrNull { it.id == id && it.state is ServerState.Failed } ?: return
            entry.bindAttempts = 0
            entry.state = ServerState.Pending
            publishControlInfo()
            checkNotNull(socketOwners[entry.key]).changes
        }
        retryRequests.trySend(Unit)
    }

    override fun updateConfiguration(accessSettings: AccessSettings, postStopImage: PostStopImage, webSettings: WebPageSettings): Boolean {
        val webJobsToCancel = synchronized(controlMonitor) {
            if (!started || closing) return false
            updateConfigurationLocked(accessSettings, postStopImage, webSettings)
        }
        webJobsToCancel.forEach(Job::cancel)
        return true
    }

    override fun registerCapture(id: CaptureAttemptId): Boolean = synchronized(controlMonitor) {
        if (!started || closing || !jpegStore.registerCapture(id)) return false
        publishWebState()
        true
    }

    override fun updateCaptureStatus(id: CaptureAttemptId, state: Status) {
        synchronized(controlMonitor) {
            if (!jpegStore.updateCaptureStatus(id, state)) return
            publishWebState()
        }
    }

    override fun offerJpeg(id: CaptureAttemptId, byteCount: Int, copyTo: (ByteArray) -> Unit) {
        if (jpegStore.offerJpeg(id, byteCount, copyTo)) synchronized(controlMonitor) {
            publishWebState()
        }
    }

    override fun requestStop() {
        val (listeners, jobs) = synchronized(controlMonitor) {
            if (closing) return
            closing = true
            val jobs = requests.closeAll()
            jpegStore.dispose()
            val listeners = plannedServers.values.mapNotNull { it.listener }
            plannedServers.clear()
            publishWebState()
            publishControlInfo()
            listeners to jobs
        }
        fun requestCancellation(action: () -> Unit) {
            try {
                action()
            } catch (cause: Throwable) {
                logE("Http.cleanup", "HTTP cancellation failed", cause)
                synchronized(controlMonitor) {
                    cleanupCause = cleanupCause ?: cause
                    fatalCause = fatalCause ?: cause
                    publishControlInfo()
                }
            }
        }
        try {
            listeners.forEach { listener -> requestCancellation { listener.requestStop() } }
            jobs.forEach { job -> requestCancellation { job.cancel() } }
            requestCancellation { runtimeJob.cancel() }
        } finally {
            cleanup.start()
        }
    }

    override suspend fun stop() {
        requestStop()
        cleanup.await()
    }

    override fun isListenerActive(listener: HttpListener): Boolean = synchronized(controlMonitor) { isListenerActiveLocked(listener) }

    override fun exchangeAccessToken(listener: HttpListener, accessToken: String?): SecretValue? = synchronized(controlMonitor) {
        if (isListenerActiveLocked(listener)) access.exchangeAccessToken(accessToken) else null
    }

    override fun verifyPin(listener: HttpListener, pin: String?, peerIp: String): HttpAccess.PinResult? = synchronized(controlMonitor) {
        if (isListenerActiveLocked(listener)) access.verifyPin(pin, peerIp, SystemClock.elapsedRealtime()) else null
    }

    override suspend fun respondMedia(
        call: ApplicationCall,
        listener: HttpListener,
        format: MediaFormat,
        accessToken: String?,
        accessCookie: String?,
    ) {
        val job = checkNotNull(call.coroutineContext[Job])
        val admission = synchronized(controlMonitor) {
            admitMediaResponseLocked(call, listener, job, format, accessToken, accessCookie)
        }
        when (admission) {
            is MediaAdmission.Rejected -> {
                if (admission.status == HttpStatusCode.ServiceUnavailable) {
                    call.response.headers.append(HttpHeaders.RetryAfter, "1")
                }
                call.respond(admission.status)
            }

            is MediaAdmission.Accepted -> {
                val writer = admission.writer
                job.invokeOnCompletion {
                    synchronized(controlMonitor) {
                        requests.retireMedia(writer, SystemClock.elapsedRealtime(), currentListeners())
                    }
                }
                writer.respond(call)
            }
        }
    }

    private fun admitMediaResponseLocked(
        call: ApplicationCall,
        listener: HttpListener,
        job: Job,
        format: MediaFormat,
        accessToken: String?,
        accessCookie: String?,
    ): MediaAdmission {
        val now = SystemClock.elapsedRealtime()
        if (!access.allowsViewing(accessToken, accessCookie)) return MediaAdmission.Rejected(HttpStatusCode.Forbidden)
        val server = plannedServers.values.firstOrNull { it.listener === listener }
        if (closing || server == null || !job.isActive) {
            return MediaAdmission.Rejected(HttpStatusCode.ServiceUnavailable)
        }
        val reader = jpegStore.openReader() ?: return MediaAdmission.Rejected(HttpStatusCode.ServiceUnavailable)
        val writer = JpegResponse(reader, format)
        requests.registerMedia(
            writer = writer,
            listener = listener,
            serverId = server.id,
            ip = call.request.local.remoteAddress,
            port = call.request.local.remotePort,
            format = format,
            job = job,
            now = now,
        )
        return MediaAdmission.Accepted(writer)
    }

    override fun admitWebSocket(listener: HttpListener, accessCookie: String?, peerIp: String, requestJob: Job): WebSocketAdmission {
        val generation = synchronized(controlMonitor) {
            val now = SystemClock.elapsedRealtime()
            if (!isListenerActiveLocked(listener) || !requestJob.isActive || !access.allowsViewing(null, accessCookie)) {
                val retry = access.remainingBlockMillis(peerIp, now).takeIf { it > 0 }
                return WebSocketAdmission.Denied(retry)
            }
            requests.registerWeb(listener, requestJob)
            access.generation
        }
        requestJob.invokeOnCompletion {
            synchronized(controlMonitor) {
                requests.removeWeb(requestJob)
            }
        }
        return WebSocketAdmission.Accepted(generation)
    }

    override fun isWebSocketCurrent(listener: HttpListener, generation: Long): Boolean = synchronized(controlMonitor) {
        isListenerActiveLocked(listener) && generation == access.generation
    }

    /** Read fresh store facts; null marks preactivation and shutdown. */
    private fun publishWebState() {
        if (!started || closing) {
            webState.value = null
            return
        }
        val image = jpegStore.snapshot()
        webState.value = WebState(
            generation = access.generation,
            accessOpen = !access.pinEnabled,
            notice = WebState.noticeFor(image.capture),
            hasImage = image.hasImage,
            display = pageSettings,
        )
    }

    /** Close old readers before admitting new credentials; a writer's acquired JPEG may finish. */
    private fun updateConfigurationLocked(accessSettings: AccessSettings, postStopImage: PostStopImage, webSettings: WebPageSettings): List<Job> {
        if (access.requiresCredentialRotation(accessSettings)) requests.closeMediaReaders()
        val webJobsToCancel = if (access.updateSettings(accessSettings)) {
            val jobs = requests.clearEndpointsAndGetWebJobs()
            sampleStatistics()
            jobs
        } else emptyList()
        jpegStore.updateStoppedImagePolicy(postStopImage)
        pageSettings = webSettings
        publishWebState()
        return webJobsToCancel
    }

    private fun isServerCurrent(server: PlannedServer): Boolean =
        !closing && plannedServers[server.address.id] === server

    /** Admission requires the exact current transport, including while binding. */
    private fun isListenerActiveLocked(listener: HttpListener): Boolean =
        !closing && plannedServers.values.any { it.listener === listener }

    private fun currentListeners(): Set<HttpListener> =
        if (closing) emptySet() else plannedServers.values.mapNotNullTo(HashSet()) { it.listener }

    /** Insert under the gate; launch afterwards so dispatch cannot run transport work inside it. */
    private fun addSocketOwners(): List<SocketOwner> {
        if (!started || closing || !runtimeJob.isActive) return emptyList()
        return plannedServers.values.mapNotNull { server ->
            if (server.key in socketOwners) return@mapNotNull null
            SocketOwner(server.key).also { socketOwners[it.key] = it }
        }
    }

    private fun launchSocketOwners(owners: List<SocketOwner>) {
        owners.forEach { owner ->
            runtimeScope.launch { runSocketOwner(owner) }
        }
    }

    private suspend fun runSocketOwner(owner: SocketOwner) {
        var retryServer: PlannedServer? = null
        var retryAtMillis = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val server = synchronized(controlMonitor) {
                if (closing) return
                val current = plannedServers.values.firstOrNull { it.key == owner.key }
                if (current == null) {
                    if (socketOwners[owner.key] === owner) socketOwners.remove(owner.key)
                    return
                }
                current
            }
            if (server !== retryServer) {
                retryServer = null
                retryAtMillis = 0L
            }
            val retryDelay = (retryAtMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val attemptAddress = synchronized(controlMonitor) {
                if (!isServerCurrent(server) || server.bindAttempts >= MAX_BIND_ATTEMPTS || retryDelay > 0L) null else {
                    server.bindAttempts++
                    server.state = ServerState.Pending
                    publishControlInfo()
                    server.address
                }
            }
            if (attemptAddress == null) {
                if (retryDelay > 0L) {
                    withTimeoutOrNull(retryDelay.milliseconds) { owner.changes.receive() }
                } else {
                    owner.changes.receive()
                }
                continue
            }
            runBindAttempt(server, attemptAddress)
            synchronized(controlMonitor) {
                retryServer = server.takeIf { isServerCurrent(it) && it.bindAttempts < MAX_BIND_ATTEMPTS }
                retryAtMillis = if (retryServer != null) SystemClock.elapsedRealtime() + RETRY_DELAY_MILLIS else 0L
            }
        }
    }

    private suspend fun runBindAttempt(entry: PlannedServer, address: NetworkAddress) {
        var listener: HttpListener? = null
        var failure: ServerFailure? = null
        try {
            listener = HttpListener(
                parentJob = runtimeJob,
                host = checkNotNull(address.ip.hostAddress),
                port = entry.port,
            ) { physicalListener ->
                webRoutes.installRoutes(this, physicalListener)
            }
            val installed = synchronized(controlMonitor) {
                if (!isServerCurrent(entry)) false else {
                    entry.listener = listener
                    true
                }
            }
            if (!installed) return
            listener.startListening()
            val listening = synchronized(controlMonitor) {
                if (!isServerCurrent(entry) || entry.listener !== listener) false else {
                    entry.state = ServerState.Listening
                    publishControlInfo()
                    true
                }
            }
            if (!listening) return
            listener.awaitTermination()
            failure = ServerFailure.IoFailure
        } catch (cause: Exception) {
            // A canceled physical engine can fail its await while this persistent owner is active.
            currentCoroutineContext().ensureActive()
            failure = when (cause) {
                is BindException -> ServerFailure.AddressInUse
                is SecurityException -> ServerFailure.PermissionDenied
                is SocketException -> ServerFailure.AddressUnavailable
                is IOException -> ServerFailure.IoFailure
                else -> ServerFailure.Unknown
            }
        } finally {
            val jobsToCancel = synchronized(controlMonitor) {
                val jobs = if (entry.listener === listener) {
                    entry.listener = null
                    listener?.let { requests.cutoff(it, entry.id, SystemClock.elapsedRealtime()) }.orEmpty()
                } else emptyList()
                if (isServerCurrent(entry)) {
                    failure?.let {
                        entry.state = ServerState.Failed(it, entry.bindAttempts)
                        publishControlInfo()
                    }
                }
                jobs
            }
            withContext(NonCancellable) {
                try {
                    try {
                        jobsToCancel.forEach(Job::cancel)
                    } finally {
                        listener?.stopAndAwaitCleanup()
                    }
                } catch (cause: Throwable) {
                    this@KtorHttpDelivery.logE("Http.listenerCleanup", "Listener cleanup failed", cause)
                    synchronized(controlMonitor) {
                        cleanupCause = cleanupCause ?: cause
                        fatalCause = fatalCause ?: cause
                        publishControlInfo()
                    }
                    // Do not return to this owner's successor selection after uncertain cleanup.
                    requestStop()
                }
            }
        }
    }

    /** Preserve the last statistics sample when listener state or failure changes. */
    private fun publishControlInfo() {
        info.value = info.value.copy(
            servers = if (closing) emptyList() else plannedServers.values.map { it.toServerInfo() },
            clients = if (closing) emptyList() else info.value.clients,
            fatalCause = fatalCause,
        )
    }

    private fun sampleStatistics() {
        val now = SystemClock.elapsedRealtime()
        val sample = requests.sample(now, jpegStore.snapshot().sourceIdentity, currentListeners())
        info.value = Info(
            servers = if (closing) emptyList() else plannedServers.values.map { it.toServerInfo() },
            clients = if (closing) emptyList() else sample.clients,
            completedJpegs = sample.completedJpegs,
            completedJpegBytes = sample.completedJpegBytes,
            sampledAtElapsedRealtimeMillis = now,
            fatalCause = fatalCause,
        )
    }

    /** One planned incarnation; metadata edits and physical bind retries preserve its UUID. */
    private class PlannedServer(var address: NetworkAddress, val port: Int) {
        val id = Uuid.random()
        val key = SocketKey.from(address, port)
        var bindAttempts = 0
        var state: ServerState = ServerState.Pending
        var listener: HttpListener? = null

        fun toServerInfo(): ServerInfo = ServerInfo(id, address, port, state)
    }

    /**
     * One runtime child serializes attempts for a scoped socket. It finishes physical cleanup
     * before reading the latest plan; replacement plans do not create waiter jobs.
     */
    private class SocketOwner(val key: SocketKey) {
        val changes = Channel<Unit>(capacity = Channel.CONFLATED)
    }

    private data class SocketKey(val bytes: List<Byte>, val scopeId: Int?, val scopeInterface: String?, val port: Int) {
        companion object {
            fun from(address: NetworkAddress, port: Int): SocketKey {
                val ip = address.ip
                return SocketKey(
                    bytes = ip.address.toList(),
                    scopeId = (ip as? Inet6Address)?.scopeId,
                    scopeInterface = (ip as? Inet6Address)?.scopedInterface?.name,
                    port = port,
                )
            }
        }
    }

    private sealed interface MediaAdmission {
        class Accepted(val writer: JpegResponse) : MediaAdmission
        class Rejected(val status: HttpStatusCode) : MediaAdmission
    }

    private companion object {
        val STATISTICS_INTERVAL = 1.seconds
        const val RETRY_DELAY_MILLIS = 250L
        const val MAX_BIND_ATTEMPTS = 3
    }
}
