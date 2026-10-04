package io.screenstream.mjpeg.http

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.HttpRequestLifecycle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * One CIO engine per physical bind attempt, isolating failures between addresses. The socket owner
 * retains it through cleanup before creating another engine for the same scoped IP and port.
 */
internal class HttpListener(
    parentJob: Job,
    val host: String,
    val port: Int,
    configureApplication: Application.(HttpListener) -> Unit,
) {
    private val engineJob = SupervisorJob(parentJob)

    private val ktorServer = try {
        embeddedServer(
            factory = CIO,
            rootConfig = serverConfig {
                parentCoroutineContext = engineJob + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
                module {
                    install(HttpRequestLifecycle) { cancelCallOnClose = true }
                    configureApplication(this@HttpListener)
                }
            },
            configure = {
                connector {
                    this.host = this@HttpListener.host
                    this.port = this@HttpListener.port
                }
                reuseAddress = false
            },
        )
    } catch (cause: Throwable) {
        engineJob.cancel()
        throw cause
    }

    suspend fun startListening() {
        ktorServer.startSuspend(false)
    }

    suspend fun awaitTermination() {
        // CIO reuses its existing job here; EmbeddedServer's wait path blocks on the JVM.
        ktorServer.engine.startSuspend(true)
    }

    /** The caller must revoke this listener's admission before cancelling its transport. */
    fun requestStop() {
        engineJob.cancel()
    }

    /**
     * Requires an active cleanup context even after owner cancellation. Joins engine work before
     * Ktor shutdown; the caller retains this listener until both finish.
     */
    suspend fun stopAndAwaitCleanup() {
        requestStop()
        try {
            engineJob.join()
        } finally {
            ktorServer.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }
}
