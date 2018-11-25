package info.dvkr.screenstream.domain.eventbus

import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel


class EventBusImpl : EventBus {
    private val broadcastChannel = BroadcastChannel<EventBus.GlobalEvent>(16)

    override suspend fun send(event: EventBus.GlobalEvent) {
        if (broadcastChannel.isFull)
            IllegalStateException("EventBusImpl.send: broadcastChannel.isFull")

        if (broadcastChannel.isClosedForSend.not()) broadcastChannel.send(event)
        else IllegalStateException("EventBusImpl.send: broadcastChannel.isClosedForSend")
    }

    override fun openSubscription(): ReceiveChannel<EventBus.GlobalEvent> =
        broadcastChannel.openSubscription()
}