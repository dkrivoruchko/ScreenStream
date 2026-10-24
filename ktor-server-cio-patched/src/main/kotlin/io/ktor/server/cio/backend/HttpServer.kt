/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

// Upstream: Ktor 3.6.0, commonMain/io/ktor/server/cio/backend/HttpServer.kt.
// Binary SHA-256: 319e4a03717e39ab9a5be8cb5a7b7d5327627ad3d55ff43270935372d7c13d16.
// Sources jar SHA-256: 911b590d894ac93d8d5151d9b0b1d597150aa3c24435b0b7bdf32244a23eabbb.
// Pristine file SHA-256: 50b65e6dc6c93c5a715d5c4f75019552c4bda3fcc39f7c621beacc20f6076722.
// KTOR-9992: keep failed accepts cancellable and avoid a busy retry loop.

package io.ktor.server.cio.backend

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.engine.internal.*
import io.ktor.util.logging.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.io.IOException
import kotlin.time.Duration.Companion.seconds

private val LOGGER = KtorSimpleLogger("io.ktor.server.cio.HttpServer")

/**
 * Start an http server with [settings] invoking [handler] for every request
 *
 * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.cio.backend.httpServer)
 */
@OptIn(InternalAPI::class)
public fun CoroutineScope.httpServer(
    settings: HttpServerSettings,
    handler: HttpRequestHandler
): HttpServer {
    val socket = CompletableDeferred<ServerSocket>()

    val serverLatch: CompletableJob = Job()

    val serverJob = launch(
        context = CoroutineName("server-root-$settings"),
        start = CoroutineStart.UNDISPATCHED
    ) {
        serverLatch.join()
    }

    val selector = SelectorManager(coroutineContext)
    val timeout = settings.connectionIdleTimeoutSeconds.seconds
    val rootConnectionJob = SupervisorJob(serverJob)

    val acceptJob = launch(serverJob + CoroutineName("accept-$settings")) {
        val serverSocket = aSocket(selector).tcp().bind(settings.host, settings.port) {
            reuseAddress = settings.reuseAddress
        }

        serverSocket.use { server ->
            socket.complete(server)

            val exceptionHandler = coroutineContext[CoroutineExceptionHandler]
                ?: DefaultUncaughtExceptionHandler(LOGGER)

            val connectionScope = CoroutineScope(
                coroutineContext +
                    rootConnectionJob +
                    exceptionHandler +
                    CoroutineName("request")
            )

            try {
                while (true) {
                    ensureActive()
                    val client: Socket = try {
                        server.accept()
                    } catch (closed: ClosedChannelException) {
                        throw closed
                    } catch (cause: IOException) {
                        LOGGER.trace("Failed to accept connection", cause)
                        delay(100)
                        continue
                    }

                    val connection = ServerIncomingConnection(
                        client.openReadChannel(),
                        client.openWriteChannel(),
                        client.remoteAddress.toNetworkAddress(),
                        client.localAddress.toNetworkAddress()
                    )

                    val clientJob = connectionScope.startServerConnectionPipeline(
                        connection,
                        timeout,
                        handler
                    )

                    clientJob.invokeOnCompletion {
                        client.close()
                    }
                }
            } catch (closed: ClosedChannelException) {
                LOGGER.trace("Server socket closed", closed)
                coroutineContext.cancel()
            } finally {
                server.close()
                rootConnectionJob.complete()
                rootConnectionJob.join()
                server.awaitClosed()
            }
        }
    }

    acceptJob.invokeOnCompletion { cause ->
        cause?.let { socket.completeExceptionally(it) }
        serverLatch.complete()
    }

    serverJob.invokeOnCompletion {
        selector.close()
    }

    return HttpServer(serverJob, acceptJob, socket)
}
