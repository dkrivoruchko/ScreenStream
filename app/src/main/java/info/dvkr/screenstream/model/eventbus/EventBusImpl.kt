package info.dvkr.screenstream.model.eventbus

import com.jakewharton.rxrelay.PublishRelay
import info.dvkr.screenstream.model.EventBus
import rx.Observable

class EventBusImpl : EventBus {
    private val events = PublishRelay.create<EventBus.GlobalEvent>()

    override fun getEvent(): Observable<EventBus.GlobalEvent> = events.asObservable()

    override fun sendEvent(event: EventBus.GlobalEvent) = events.call(event)
}