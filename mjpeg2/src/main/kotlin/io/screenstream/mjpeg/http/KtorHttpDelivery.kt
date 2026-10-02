package io.screenstream.mjpeg.http

import android.os.SystemClock
import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.screenstream.mjpeg.http.HttpDelivery.FatalIssue
import io.screenstream.mjpeg.http.HttpDelivery.Info
import io.screenstream.mjpeg.http.HttpDelivery.ImageKind
import io.screenstream.mjpeg.http.HttpDelivery.ServerId
import io.screenstream.mjpeg.http.HttpDelivery.ViewerMethod
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor.Address
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Factory

/** Inert per-controller CIO delivery; start installs prepared access before opening listeners. */
@Factory(binds = [HttpDelivery::class])
internal class KtorHttpDelivery(context: Context) : HttpDelivery {
    private val gate = Any()
    private val ingress = Any()
    private val workJob = SupervisorJob()
    private val workScope = CoroutineScope(workJob + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
        synchronized(gate) {
            fatalIssue = FatalIssue.InternalFailure
            publishInfo()
        }
        requestStop()
    })
    private val mutableInfo = MutableStateFlow(Info())
    override val info: StateFlow<Info> = mutableInfo.asStateFlow()
    private val publicationWake = MutableStateFlow(0L)
    private val access = HttpAccess()
    private val pages = WebClientSessions()
    private val presentation = WebPresentation()
    private val webRevision = MutableStateFlow(0L)
    private val listeners: HttpListeners = HttpListeners(gate, workJob, { address, port ->
        AddressServer(workJob, checkNotNull(address.ip.hostAddress), port) { server -> web.install(this, server) }
    }, ::publishInfo, { fatalIssue = FatalIssue.CleanupFailure; publishInfo() })
    private val web: WebRoutes = WebRoutes(context, gate, access, pages, presentation, webRevision,
        { server -> !closing && listeners.isActive(server) }, { latest?.kind }, ::media)
    private val responses = LinkedHashSet<Response>()
    private var started = false
    private var closing = false
    private var publication: HttpDelivery.Publication? = null
    private var frameVersion = 0L
    private var latest: Frame? = null
    private var spare: ByteArray? = null
    private var completedJpegs = 0L
    private var completedJpegBytes = 0L
    private var slowWrites = 0L
    private var fatalIssue: FatalIssue? = null
    private val cleanup = CoroutineScope(Dispatchers.IO).async(start = CoroutineStart.LAZY) { cleanupOwnedResources() }

    override suspend fun start(access: AccessSettings, token: SecretValue?, isAllowed: (Address, Int) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val statistics = synchronized(gate) {
            if (started || closing) return false
            this.access.apply(access, token)
            started = true
            statisticsJob()
        }
        listeners.start(isAllowed)
        statistics.start()
        return synchronized(gate) { !closing }
    }

    override suspend fun configureServers(servers: List<HttpDelivery.DesiredServer>, port: Int) {
        currentCoroutineContext().ensureActive()
        listeners.configure(servers, port)
    }

    override suspend fun retryServer(id: ServerId) {
        currentCoroutineContext().ensureActive()
        listeners.retry(id)
    }

    override fun updateAccess(access: AccessSettings, token: SecretValue?): Boolean {
        val revoked = synchronized(gate) {
            if (!started || closing) return false
            if (!this.access.apply(access, token)) return true
            pages.revoke()
            signalWebState()
            publishInfo()
            responses.map { it.job } + web.ownedJobs()
        }
        revoked.forEach { it.cancel() }
        return true
    }

    override fun updatePresentation(capture: HttpDelivery.WebCaptureState, issue: HttpDelivery.WebCaptureIssue?, display: WebPageSettings) {
        synchronized(gate) {
            if (!closing && presentation.update(capture, issue, display)) signalWebState()
        }
    }

    override fun updatePublication(publication: HttpDelivery.Publication): Boolean {
        synchronized(gate) {
            if (closing || publication.revision <= (this.publication?.revision ?: 0L)) return false
            if ((this.publication?.revision ?: 0L) < publication.clearRevision) {
                val previous = latest
                latest = null
                previous?.let(::retire)
            }
            this.publication = publication
            signalPublication()
            publishInfo()
            return true
        }
    }

    override fun offerJpeg(publication: HttpDelivery.Publication, byteCount: Int, copyTo: (ByteArray, Int) -> Int): Boolean = synchronized(ingress) {
        require(byteCount > 0)
        val kind = publication.kind ?: return false
        val prefix = "Content-Type: image/jpeg\r\nContent-Length: $byteCount\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val size = Math.addExact(Math.addExact(prefix.size, byteCount), PART_END.size)
        var bytes = synchronized(gate) {
            if (!started || closing || this.publication !== publication) return false
            spare.also { spare = null }
        }
        try {
            if (bytes == null || bytes.size < size || bytes.size.toLong() > size.toLong() * 2) bytes = ByteArray(size)
            prefix.copyInto(bytes)
            check(copyTo(bytes, prefix.size) == byteCount) { "Incomplete JPEG copy" }
            PART_END.copyInto(bytes, prefix.size + byteCount)
            synchronized(gate) {
                if (closing || this.publication !== publication) {
                    recycle(bytes)
                    return false
                }
                val previous = latest
                latest = Frame(bytes, prefix.size, byteCount, size, ++frameVersion, kind)
                previous?.let(::retire)
                signalPublication()
                if (previous?.kind != kind) publishInfo()
            }
            true
        } catch (cause: Throwable) {
            synchronized(gate) { bytes?.let(::recycle); fatalIssue = FatalIssue.InternalFailure; publishInfo() }
            requestStop()
            throw cause
        }
    }

    override fun requestStop() {
        val owned = synchronized(gate) {
            if (closing) return
            closing = true
            val transports = listeners.closeAdmission()
            publication = null
            val previous = latest
            latest = null
            previous?.let(::retire)
            pages.revoke()
            signalPublication()
            publishInfo()
            transports to (responses.map { it.job } + web.ownedJobs())
        }
        owned.first.forEach(AddressServer::requestStop)
        owned.second.forEach { it.cancel() }
        cleanup.start()
    }

    override suspend fun stop() {
        requestStop()
        cleanup.await()
    }

    private suspend fun media(call: ApplicationCall, server: AddressServer, method: ViewerMethod) {
        val requestJob = checkNotNull(call.coroutineContext[Job])
        val peer = call.request.local.remoteHost
        val suppliedToken = call.request.queryParameters["t"]
        val suppliedCookie = WebRoutes.cookie(call)
        val suppliedPage = call.request.queryParameters["s"]
        val suppliedKind = call.request.queryParameters["kind"]
        val expectedKind = when (suppliedKind) {
            "stream" -> ImageKind.StreamFrame
            "placeholder" -> ImageKind.Placeholder
            else -> null
        }
        // Access admission and the initial source pin are one transaction.
        var rejection: HttpStatusCode? = null
        val ownedResponse = synchronized(gate) {
            val now = SystemClock.elapsedRealtime()
            pages.expire(now)
            val page = pages.findPage(suppliedPage, access.generation)
            val authorized = access.admits(suppliedToken, null) || page != null && access.admits(null, suppliedCookie)
            rejection = when {
                !authorized -> HttpStatusCode.Forbidden
                suppliedKind != null && expectedKind == null -> HttpStatusCode.BadRequest
                closing || !listeners.isActive(server) || !requestJob.isActive || latest == null -> HttpStatusCode.ServiceUnavailable
                expectedKind != null && latest?.kind != expectedKind -> HttpStatusCode.ServiceUnavailable
                else -> null
            }
            if (rejection != null) null
            else Response(server, requestJob, access.generation, peer, method, page, expectedKind).also {
                responses.add(it)
                pages.mediaAdmitted(page, now)
                pin(it)
            }
        }
        if (ownedResponse == null) {
            if (rejection == HttpStatusCode.ServiceUnavailable) call.response.headers.append(HttpHeaders.RetryAfter, "1")
            call.respond(checkNotNull(rejection))
            return
        }
        requestJob.invokeOnCompletion {
            synchronized(gate) {
                releasePin(ownedResponse)
                responses.remove(ownedResponse)
                pages.mediaFinished(ownedResponse.page, ownedResponse.viewer, SystemClock.elapsedRealtime())
                publishInfo()
            }
        }
        try {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = if (method == ViewerMethod.Jpeg) ContentType.Image.JPEG
                else ContentType.parse("multipart/x-mixed-replace; boundary=$BOUNDARY")
                override val contentLength: Long? = if (method == ViewerMethod.Jpeg) synchronized(gate) { ownedResponse.held?.payloadSize?.toLong() } else null
                override suspend fun writeTo(channel: ByteWriteChannel) { writeMedia(channel, ownedResponse) }
            })
        } finally {
            // If the response failed before writeTo, no source read is still in flight.
            synchronized(gate) { releasePin(ownedResponse) }
        }
    }

    private suspend fun writeMedia(channel: ByteWriteChannel, response: Response) {
        var sentVersion = -1L
        var repeatAt = 0L
        if (response.method == ViewerMethod.Mjpeg) channel.writeFully(PART_BEGIN)
        while (true) {
            currentCoroutineContext().ensureActive()
            val observed = publicationWake.value
            val frame = synchronized(gate) {
                if (closing || !listeners.isActive(response.server) || response.generation != access.generation) throw CancellationException("HTTP response revoked")
                // A selected part finishes with its own bytes/kind. A constrained response waits
                // for a matching publication; transient kind changes may be conflated by WS.
                response.held ?: pin(response)
            }
            if (frame == null || (frame.version == sentVersion && SystemClock.elapsedRealtime() < repeatAt)) {
                synchronized(gate) { releasePin(response) }
                if (frame == null) publicationWake.first { it != observed }
                else withTimeoutOrNull((repeatAt - SystemClock.elapsedRealtime()).coerceAtLeast(1).milliseconds) { publicationWake.first { it != observed } }
                continue
            }
            try {
                synchronized(gate) {
                    if (response.generation != access.generation || closing || !listeners.isActive(response.server)) throw CancellationException("HTTP response revoked")
                    if (response.viewer != null) response.writeStarted = SystemClock.elapsedRealtime()
                }
                try {
                    if (response.method == ViewerMethod.Mjpeg) writeRange(channel, frame.bytes, 0, frame.payloadOffset)
                    synchronized(gate) {
                        if (response.generation != access.generation || closing || !listeners.isActive(response.server)) throw CancellationException("HTTP response revoked")
                        val firstPayload = response.viewer == null
                        if (firstPayload) response.viewer = pages.beginPayload(response.page, response.ip, response.method, SystemClock.elapsedRealtime())
                        if (response.writeStarted == null) response.writeStarted = SystemClock.elapsedRealtime()
                        if (firstPayload) publishInfo()
                    }
                    writeRange(channel, frame.bytes, frame.payloadOffset, frame.payloadSize)
                    synchronized(gate) {
                        completedJpegs++
                        completedJpegBytes += frame.payloadSize
                        response.viewer?.let { it.completedBytes += frame.payloadSize }
                    }
                    if (response.method == ViewerMethod.Mjpeg) writeRange(channel, frame.bytes, frame.payloadOffset + frame.payloadSize, frame.size - frame.payloadOffset - frame.payloadSize)
                } finally {
                    // No source is read after this point, even if final flush remains blocked.
                    synchronized(gate) { releasePin(response) }
                }
                channel.flush()
            } finally {
                synchronized(gate) {
                    markSlowWrite(response, SystemClock.elapsedRealtime())
                    response.writeStarted = null
                    response.slow = false
                }
            }
            if (response.method == ViewerMethod.Jpeg) return
            sentVersion = frame.version
            repeatAt = SystemClock.elapsedRealtime() + JPEG_REPEAT_INTERVAL_MILLIS
        }
    }

    private suspend fun writeRange(channel: ByteWriteChannel, bytes: ByteArray, offset: Int, count: Int) {
        var position = offset
        val end = offset + count
        while (position < end) {
            val size = minOf(WRITE_CHUNK_BYTES, end - position)
            channel.writeFully(bytes, position, position + size)
            position += size
        }
    }

    private fun pin(response: Response): Frame? = latest?.takeIf {
        response.expectedKind == null || it.kind == response.expectedKind
    }?.also {
        it.readers++
        response.held = it
    }
    private fun releasePin(response: Response) {
        val frame = response.held ?: return
        response.held = null
        frame.readers--
        if (frame !== latest && frame.readers == 0) recycle(frame.bytes)
    }
    private fun retire(frame: Frame) { if (frame.readers == 0) recycle(frame.bytes) }
    private fun recycle(bytes: ByteArray) { if (!closing && spare == null) spare = bytes }
    private fun signalPublication() { publicationWake.value++ }

    private fun statisticsJob(): Job = workScope.launch(start = CoroutineStart.LAZY) {
        while (!synchronized(gate) { closing }) {
            delay(STATISTICS_INTERVAL_MILLIS.milliseconds)
            synchronized(gate) {
                val now = SystemClock.elapsedRealtime()
                responses.forEach { markSlowWrite(it, now) }
                if (responses.isNotEmpty() || mutableInfo.value.viewers.isNotEmpty()) publishInfo()
            }
        }
    }

    private fun markSlowWrite(response: Response, now: Long) {
        if (!response.slow && response.writeStarted?.let { now - it >= SLOW_WRITE_THRESHOLD_MILLIS } == true) {
            response.slow = true
            slowWrites++
        }
    }

    /** All snapshots are copied under the short gate; no network or JPEG copying occurs here. */
    private fun publishInfo() {
        val available = latest != null
        val kind = latest?.kind
        if (mutableInfo.value.imageKind != kind) signalWebState()
        mutableInfo.value = Info(
            addresses = if (closing) emptyList() else listeners.snapshot(),
            viewers = if (closing) emptyList() else pages.snapshot(SystemClock.elapsedRealtime(), responses.mapNotNull {
                it.viewer?.id?.takeIf { _ -> it.slow && it.generation == access.generation }
            }.toSet()),
            completedJpegs = completedJpegs,
            completedJpegBytes = completedJpegBytes,
            slowWrites = slowWrites,
            sampledAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            imageAvailable = available,
            imageKind = kind,
            fatalIssue = fatalIssue,
        )
    }

    private fun signalWebState() { webRevision.value++ }

    private suspend fun cleanupOwnedResources() {
        listeners.awaitCleanup()
        val calls = synchronized(gate) { responses.map { it.job } + web.ownedJobs() }
        calls.forEach { it.cancel() }
        calls.joinAll()
        // A synchronous copy cannot be interrupted; closed admission already prevents its publication.
        synchronized(ingress) {
            synchronized(gate) {
                spare = null
                latest = null
            }
        }
        workJob.cancelAndJoin()
        synchronized(gate) {
            publishInfo()
        }
        check(synchronized(gate) { fatalIssue != FatalIssue.CleanupFailure }) { "HTTP cleanup failed" }
    }

    private class Frame(val bytes: ByteArray, val payloadOffset: Int, val payloadSize: Int, val size: Int, val version: Long, val kind: ImageKind) {
        var readers = 0
    }
    private class Response(
        val server: AddressServer,
        val job: Job,
        val generation: Long,
        val ip: String,
        val method: ViewerMethod,
        val page: WebClientSessions.Page?,
        val expectedKind: ImageKind?,
    ) {
        var held: Frame? = null
        var viewer: WebClientSessions.Viewer? = null
        var writeStarted: Long? = null
        var slow = false
    }

    private companion object {
        const val STATISTICS_INTERVAL_MILLIS = 250L
        const val JPEG_REPEAT_INTERVAL_MILLIS = 1_000L
        const val SLOW_WRITE_THRESHOLD_MILLIS = 2_000L
        const val WRITE_CHUNK_BYTES = 32 * 1024
        const val BOUNDARY = "screenstream-jpeg"
        val PART_BEGIN = "--$BOUNDARY\r\n".toByteArray(Charsets.US_ASCII)
        val PART_END = "\r\n--$BOUNDARY\r\n".toByteArray(Charsets.US_ASCII)
    }
}
