package info.dvkr.screenstream.model.eventbus

import info.dvkr.screenstream.model.EventBus
import rx.Observable
import rx.subjects.PublishSubject

class EventBusImpl : EventBus {
    private val events = PublishSubject.create<EventBus.GlobalEvent>()

    init {
        events.onBackpressureBuffer(32)
    }

    override fun getEvent(): Observable<EventBus.GlobalEvent> = events.asObservable()

    override fun sendEvent(event: EventBus.GlobalEvent) = events.onNext(event)
}