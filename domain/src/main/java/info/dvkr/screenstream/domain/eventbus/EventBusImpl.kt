package info.dvkr.screenstream.domain.eventbus

import kotlinx.coroutines.experimental.channels.ArrayBroadcastChannel
import kotlinx.coroutines.experimental.channels.ReceiveChannel

class EventBusImpl : EventBus {
    private val broadcastChannel = ArrayBroadcastChannel<EventBus.GlobalEvent>(16)

    override suspend fun send(event: EventBus.GlobalEvent) {
        if (broadcastChannel.isFull)
            IllegalStateException("EventBusImpl.send: broadcastChannel.isFull")

        if (broadcastChannel.isClosedForSend.not()) broadcastChannel.send(event)
        else IllegalStateException("EventBusImpl.send: broadcastChannel.isClosedForSend")
    }

    override fun openSubscription(): ReceiveChannel<EventBus.GlobalEvent> =
        broadcastChannel.openSubscription()
}