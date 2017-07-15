package info.dvkr.screenstream.dagger.module

import android.os.HandlerThread
import dagger.Module
import dagger.Provides
import rx.Scheduler
import rx.android.schedulers.AndroidSchedulers
import javax.inject.Singleton

@Singleton
@Module(includes = arrayOf(AppModule::class))
class EventSchedulerModule {

    private val eventThread = HandlerThread("SSEventThread")
    private val eventScheduler: Scheduler

    init {
        eventThread.start()
        eventScheduler = AndroidSchedulers.from(eventThread.looper)
    }

    @Provides
    @Singleton
    internal fun provideEventScheduler(): Scheduler = eventScheduler
}