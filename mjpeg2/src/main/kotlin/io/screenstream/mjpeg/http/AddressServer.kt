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

/** One physical HTTP listener; the caller owns admission, routes, and retirement. */
internal class AddressServer(
    parentJob: Job,
    val host: String,
    val port: Int,
    configureApplication: Application.(AddressServer) -> Unit,
) {
    private val engineJob = SupervisorJob(parentJob)

    private val server = try {
        embeddedServer(
            factory = CIO,
            rootConfig = serverConfig {
                parentCoroutineContext = engineJob + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
                module {
                    install(HttpRequestLifecycle) { cancelCallOnClose = true }
                    configureApplication(this@AddressServer)
                }
            },
            configure = {
                connector {
                    this.host = this@AddressServer.host
                    this.port = this@AddressServer.port
                }
                reuseAddress = false
            },
        )
    } catch (cause: Throwable) {
        engineJob.cancel()
        throw cause
    }

    suspend fun start() {
        server.startSuspend(false)
    }

    suspend fun awaitTermination() {
        // CIO reuses its existing job here; EmbeddedServer's wait path blocks on the JVM.
        server.engine.startSuspend(true)
    }

    /** The caller must revoke this listener's admission before cancelling its transport. */
    fun requestStop() {
        engineJob.cancel()
    }

    /**
     * Waits for owned coroutines, then attempts application disposal. The caller retains this
     * listener until completion and provides an active cleanup context, including in a cancelled worker.
     */
    suspend fun stopAndJoin() {
        requestStop()
        try {
            engineJob.join()
        } finally {
            server.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }
}
