package io.screenstream.mjpeg.http

import android.content.Context
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.robolectric.RuntimeEnvironment
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds

internal fun httpTestContext(): Context = RuntimeEnvironment.getApplication()

internal fun httpTestPage(title: String = "Viewing contract"): WebPageSettings = WebPageSettings(title = title)

internal fun httpTestProtection(pin: String = "123456"): AccessSettings =
    AccessSettings(pinEnabled = true, pin = SecretValue(pin))

internal fun httpTestAddress(id: Long = 1L, host: String = "127.0.0.1", name: String = "lo"): NetworkAddress =
    NetworkAddress(id, InetAddress.getByName(host), name, InterfaceType.Other)

/** Genuine listener identity without socket activation; each fixture still settles its engine owner. */
internal class UnstartedHttpListener(host: String = "127.0.0.1", port: Int = 8080) {
    private val parent = Job()
    val listener: HttpListener = HttpListener(parent, host, port) { }

    suspend fun close() {
        withContext(NonCancellable) {
            try {
                withTimeout(15.seconds) { listener.stopAndAwaitCleanup() }
            } finally {
                parent.cancel()
                withTimeout(15.seconds) { parent.join() }
            }
        }
    }
}

internal suspend fun HttpDelivery.awaitHttpInfo(predicate: (HttpDelivery.Info) -> Boolean): HttpDelivery.Info =
    withTimeout(15.seconds) { info.first(predicate) }

internal suspend fun HttpDelivery.finishHttpTest() {
    withContext(NonCancellable) { withTimeout(15.seconds) { stop() } }
}
