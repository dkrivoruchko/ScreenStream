package info.dvkr.screenstream.domain.eventbus

import com.jakewharton.rxrelay.PublishRelay
import rx.Observable
import rx.Scheduler

class EventBusImpl(private val eventScheduler: Scheduler) : EventBus {
    private val events = PublishRelay.create<EventBus.GlobalEvent>()

    override fun getEvent(): Observable<EventBus.GlobalEvent> = events.asObservable()
            .subscribeOn(eventScheduler)
            .onBackpressureBuffer()

    override fun sendEvent(event: EventBus.GlobalEvent) = events.call(event)
}