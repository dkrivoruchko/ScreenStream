package info.dvkr.screenstream.dagger.module

import android.os.HandlerThread
import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.eventbus.EventBusImpl
import rx.Scheduler
import rx.android.schedulers.AndroidSchedulers
import javax.inject.Singleton

@Singleton
@Module
class EventBusModule {

    private val eventThread = HandlerThread("SSEventThread")
    private val eventScheduler: Scheduler

    init {
        eventThread.start()
        eventScheduler = AndroidSchedulers.from(eventThread.looper)
    }

    @Provides
    @Singleton
    internal fun provideEventScheduler(): Scheduler = eventScheduler

    @Provides
    @Singleton
    internal fun provideEventBus(eventScheduler: Scheduler): EventBus = EventBusImpl(eventScheduler)
}