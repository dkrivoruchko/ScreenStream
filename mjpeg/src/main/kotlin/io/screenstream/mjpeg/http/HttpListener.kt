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

    private val ktorServer = embeddedServer(
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

    suspend fun startListening() {
        ktorServer.startSuspend(false)
    }

    suspend fun awaitTermination() {
        ktorServer.engine.startSuspend(true)
    }

    fun requestStop() {
        engineJob.cancel()
    }

    suspend fun stopAndAwaitCleanup() {
        requestStop()
        try {
            engineJob.join()
        } finally {
            ktorServer.stopSuspend(0, 0)
        }
    }
}
