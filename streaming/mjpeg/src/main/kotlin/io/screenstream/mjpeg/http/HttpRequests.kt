package io.screenstream.mjpeg.http

import io.screenstream.mjpeg.http.HttpDelivery.ClientInfo
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.media.JpegResponse
import kotlinx.coroutines.Job
import kotlin.uuid.Uuid

/**
 * One runtime's requests, endpoint history and completed JPEG totals. Every operation requires
 * the delivery's control monitor; delivery supplies completion and current-listener authority.
 */
internal class HttpRequests {
    private val media = LinkedHashMap<JpegResponse, MediaRequest>()
    private val webSocketRequests = LinkedHashMap<Job, HttpListener>()
    private val endpoints = LinkedHashMap<Endpoint.Key, Endpoint>()
    private var lastEndpointId = 0L
    private var retiredJpegCount = 0L
    private var retiredJpegBytes = 0L

    /** Register after access validation and initial reader pinning, before installing completion. */
    fun registerMedia(
        writer: JpegResponse,
        listener: HttpListener,
        serverId: Uuid,
        ip: String,
        port: Int,
        job: Job,
        now: Long,
    ) {
        check(writer !in media) { "Media writer already registered" }
        val key = Endpoint.Key(serverId, ip, port)
        if (endpoints[key]?.hasExpired(now) == true) endpoints.remove(key)
        val endpoint = endpoints.getOrPut(key) { Endpoint(++lastEndpointId, key, writer.format) }
        endpoint.format = writer.format
        endpoint.activeUntilMillis = null
        media[writer] = MediaRequest(listener, job, endpoint)
    }

    fun registerWebSocket(listener: HttpListener, job: Job) {
        check(job !in webSocketRequests) { "WebSocket request already registered" }
        webSocketRequests[job] = listener
    }

    fun removeWebSocket(job: Job) {
        webSocketRequests.remove(job)
    }

    /** Completion transfers final progress exactly once; old listeners cannot start Active grace. */
    fun retireMedia(writer: JpegResponse, now: Long, currentListeners: Set<HttpListener>) {
        val request = media.remove(writer) ?: return
        writer.close()
        val progress = writer.progress
        retiredJpegCount += progress.completedJpegs
        retiredJpegBytes += progress.completedJpegBytes
        val endpoint = request.endpoint
        if (endpoints[endpoint.key] !== endpoint) return
        endpoint.retiredJpegBytes += progress.completedJpegBytes
        val startsGrace = request.listener in currentListeners && media.values.none {
            it.endpoint === endpoint && it.listener in currentListeners
        }
        if (startsGrace) {
            endpoint.activeUntilMillis = now + RECENT_MEDIA_MILLIS
        }
    }

    /** Close next-frame admission; already acquired payload leases still belong to their writers. */
    fun closeMediaReaders() {
        media.keys.forEach(JpegResponse::close)
    }

    /** Credential rotation detaches endpoint history but leaves physical requests for final totals. */
    fun clearEndpointsAndGetWebSocketJobs(): List<Job> {
        endpoints.clear()
        return webSocketRequests.keys.toList()
    }

    /** Cutoff skips Active grace; late completion cannot extend the retention deadline. */
    fun cutoff(listener: HttpListener, serverId: Uuid, now: Long): List<Job> {
        endpoints.values.filter { it.key.serverId == serverId }.forEach { endpoint ->
            endpoint.activeUntilMillis = minOf(endpoint.activeUntilMillis ?: now, now)
        }
        val jobs = media.filterValues { it.listener === listener }.map { (writer, request) ->
            writer.close()
            request.job
        }
        return jobs + webSocketRequests.filterValues { it === listener }.keys
    }

    /** Discard endpoint history; pending physical completions still contribute global totals. */
    fun closeAll(): List<Job> {
        closeMediaReaders()
        endpoints.clear()
        return media.values.map { it.job } + webSocketRequests.keys
    }

    /** [currentListeners] must be captured under the same gate; it is not retained. */
    fun sample(now: Long, captureGeneration: Any?, currentListeners: Set<HttpListener>): Sample {
        var completedJpegs = retiredJpegCount
        var completedJpegBytes = retiredJpegBytes
        endpoints.values.removeAll { it.hasExpired(now) }
        val rowBytes = HashMap<Endpoint, Long>()
        val slowEndpoints = HashSet<Endpoint>()
        val liveEndpoints = HashSet<Endpoint>()
        for ((writer, request) in media) {
            val progress = writer.progress
            completedJpegs += progress.completedJpegs
            completedJpegBytes += progress.completedJpegBytes
            val endpoint = request.endpoint
            if (endpoints[endpoint.key] !== endpoint) continue
            rowBytes[endpoint] = (rowBytes[endpoint] ?: 0L) + progress.completedJpegBytes
            if (request.listener in currentListeners) {
                liveEndpoints.add(endpoint)
                if (captureGeneration != null && progress.isSlow && progress.observedCaptureGeneration === captureGeneration) {
                    slowEndpoints.add(endpoint)
                }
            }
        }
        val clients = endpoints.values.map { endpoint ->
            ClientInfo(
                id = endpoint.id,
                ip = endpoint.key.ip,
                remotePort = endpoint.key.port,
                serverId = endpoint.key.serverId,
                format = endpoint.format,
                isSlow = endpoint in slowEndpoints,
                completedJpegBytes = endpoint.retiredJpegBytes + (rowBytes[endpoint] ?: 0L),
                state = if (endpoint in liveEndpoints || endpoint.isInGrace(now)) ClientInfo.State.Active else ClientInfo.State.Disconnected,
            )
        }
        return Sample(clients, completedJpegs, completedJpegBytes)
    }

    data class Sample(val clients: List<ClientInfo>, val completedJpegs: Long, val completedJpegBytes: Long)

    private class MediaRequest(val listener: HttpListener, val job: Job, val endpoint: Endpoint)

    /** Numeric/string history only; never retains jobs, writers, readers or JPEG storage. */
    private class Endpoint(val id: Long, val key: Key, var format: MediaFormat) {
        var retiredJpegBytes = 0L
        var activeUntilMillis: Long? = null

        fun hasExpired(now: Long): Boolean =
            activeUntilMillis?.let { now >= it + DISCONNECTED_HOLD_MILLIS } == true

        fun isInGrace(now: Long): Boolean = activeUntilMillis?.let { now < it } == true

        data class Key(val serverId: Uuid, val ip: String, val port: Int)
    }

    private companion object {
        const val DISCONNECTED_HOLD_MILLIS = 5_000L
        const val RECENT_MEDIA_MILLIS = 2_000L
    }
}
