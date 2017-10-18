package info.dvkr.screenstream.model.eventbus

import com.crashlytics.android.Crashlytics
import com.jakewharton.rxrelay.PublishRelay
import info.dvkr.screenstream.model.EventBus
import rx.Observable
import rx.Scheduler

class EventBusImpl(private val eventScheduler: Scheduler) : EventBus {
    private val events = PublishRelay.create<EventBus.GlobalEvent>()

    override fun getEvent(): Observable<EventBus.GlobalEvent> = events.asObservable()
            .subscribeOn(eventScheduler)
            .onBackpressureBuffer(16, { Crashlytics.logException(IllegalStateException("onBackpressureBuffer: DROP")) })

    override fun sendEvent(event: EventBus.GlobalEvent) = events.call(event)
}