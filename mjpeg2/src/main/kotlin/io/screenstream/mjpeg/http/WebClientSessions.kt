package io.screenstream.mjpeg.http

import java.security.SecureRandom

/** Page labels are not credentials. All state transitions use the delivery's existing gate. */
internal class WebClientSessions {
    private val random = SecureRandom()
    private val pages = LinkedHashMap<String, Page>()
    private val viewers = LinkedHashMap<Long, Viewer>()
    private var sequence = 0L

    fun connectPage(supplied: String?, generation: Long, now: Long): Page {
        expire(now)
        val page = findPage(supplied, generation) ?: Page(label(), generation, now).also { pages[it.id] = it }
        page.webSockets++
        page.touchedAt = now
        return page
    }

    fun findPage(id: String?, generation: Long): Page? = id?.let { pages[it] }?.takeIf { it.generation == generation }

    fun pageDisconnected(page: Page, now: Long) {
        page.webSockets--
        page.touchedAt = now
    }

    fun mediaAdmitted(page: Page?, now: Long) {
        if (page != null) {
            page.mediaResponses++
            page.touchedAt = now
        }
    }

    fun beginPayload(page: Page?, ip: String, method: HttpDelivery.ViewerMethod, now: Long): Viewer {
        expire(now)
        val current = page?.viewer?.takeIf { viewers[it.id] === it }
        val viewer = current ?: Viewer(++sequence, page, ip, method, now).also {
            viewers[it.id] = it
            if (page != null) page.viewer = it
        }
        viewer.method = method
        viewer.responses++
        viewer.touchedAt = now
        return viewer
    }

    fun mediaFinished(page: Page?, viewer: Viewer?, now: Long) {
        if (page != null) {
            page.mediaResponses--
            page.touchedAt = now
        }
        if (viewer != null) {
            viewer.responses--
            viewer.touchedAt = now
            if (page == null) viewers.remove(viewer.id)
        }
    }

    fun revoke() {
        pages.clear()
        viewers.clear()
    }

    fun snapshot(now: Long, slowViewerIds: Set<Long>): List<HttpDelivery.ViewerInfo> {
        expire(now)
        return viewers.values.map {
            val active = it.responses > 0 || now - it.touchedAt <= POLLING_ACTIVE_MILLIS
            HttpDelivery.ViewerInfo(it.id, it.ip, it.method, active && it.id in slowViewerIds, it.completedBytes,
                if (active) HttpDelivery.ViewerState.Active else HttpDelivery.ViewerState.Reconnecting)
        }
    }

    fun expire(now: Long) {
        viewers.values.removeAll { it.responses == 0 && now - it.touchedAt > VIEWER_GRACE_MILLIS }
        pages.values.removeAll { it.webSockets == 0 && it.mediaResponses == 0 && now - it.touchedAt > PAGE_GRACE_MILLIS }
    }

    private fun label(): String = ByteArray(LABEL_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    class Page(val id: String, val generation: Long, var touchedAt: Long) {
        var webSockets = 0
        var mediaResponses = 0
        var viewer: Viewer? = null
    }
    class Viewer(val id: Long, val page: Page?, val ip: String, var method: HttpDelivery.ViewerMethod, var touchedAt: Long) {
        var responses = 0
        var completedBytes = 0L
    }

    private companion object {
        const val LABEL_BYTES = 24
        const val POLLING_ACTIVE_MILLIS = 2_000L
        const val VIEWER_GRACE_MILLIS = 30_000L
        const val PAGE_GRACE_MILLIS = 30_000L
    }
}
