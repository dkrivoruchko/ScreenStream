package info.dvkr.screenstream.domain.eventbus

import kotlinx.coroutines.experimental.channels.ArrayBroadcastChannel
import kotlinx.coroutines.experimental.channels.SubscriptionReceiveChannel

class EventBusImpl : EventBus {
    private val broadcastChannel = ArrayBroadcastChannel<EventBus.GlobalEvent>(16)

    override suspend fun send(event: EventBus.GlobalEvent) {
        if (broadcastChannel.isClosedForSend.not()) broadcastChannel.send(event)
    }

    override fun openSubscription(): SubscriptionReceiveChannel<EventBus.GlobalEvent> =
            broadcastChannel.openSubscription()
}