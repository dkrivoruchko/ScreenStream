package info.dvkr.screenstream.domain.httpserver

import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.reactivex.netty.threads.PreferCurrentEventLoopGroup
import io.reactivex.netty.threads.RxEventLoopProvider

/**
 * An implementation of [RxEventLoopProvider] that returns the same [EventLoopGroup] instance for both
 * client and server.
 */
class HttpServerNioLoopProvider internal constructor(threadCount: Int) : RxEventLoopProvider() {
    private val eventLoop: EventLoopGroup
    private val clientEventLoop: EventLoopGroup
    private val parentEventLoop: EventLoopGroup

    init {
        eventLoop = NioEventLoopGroup(threadCount, HttpServerThreadFactory("SSNioLoop", true, Thread.MAX_PRIORITY))
        clientEventLoop = PreferCurrentEventLoopGroup(eventLoop)
        parentEventLoop = eventLoop
    }

    override fun globalClientEventLoop(): EventLoopGroup = clientEventLoop

    override fun globalServerEventLoop(): EventLoopGroup = eventLoop

    override fun globalServerParentEventLoop(): EventLoopGroup = parentEventLoop

    override fun globalClientEventLoop(nativeTransport: Boolean): EventLoopGroup = globalClientEventLoop()

    override fun globalServerEventLoop(nativeTransport: Boolean): EventLoopGroup = globalServerEventLoop()

    override fun globalServerParentEventLoop(nativeTransport: Boolean): EventLoopGroup = globalServerParentEventLoop()
}