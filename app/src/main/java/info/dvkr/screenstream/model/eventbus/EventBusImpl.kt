package info.dvkr.screenstream.model.eventbus

import info.dvkr.screenstream.model.EventBus
import rx.Observable
import rx.subjects.PublishSubject

class EventBusImpl : EventBus {
    private val mEvents = PublishSubject.create<EventBus.GlobalEvent>()
// TODO Thread ?

    override fun getEvent(): Observable<EventBus.GlobalEvent> = mEvents.asObservable()

    override fun sendEvent(event: EventBus.GlobalEvent) = mEvents.onNext(event)
}