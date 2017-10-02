package info.dvkr.screenstream.model.eventbus

import com.crashlytics.android.Crashlytics
import com.jakewharton.rxrelay.PublishRelay
import info.dvkr.screenstream.model.EventBus
import rx.Observable
import rx.functions.Action0

class EventBusImpl : EventBus {
    private val TAG = "EventBusImpl"
    private val events = PublishRelay.create<EventBus.GlobalEvent>()

    override fun getEvent(): Observable<EventBus.GlobalEvent> = events.asObservable()
            .onBackpressureBuffer(16, Action0 { Crashlytics.log(1, TAG, "onBackpressureBuffer: DROP") })

    override fun sendEvent(event: EventBus.GlobalEvent) = events.call(event)
}