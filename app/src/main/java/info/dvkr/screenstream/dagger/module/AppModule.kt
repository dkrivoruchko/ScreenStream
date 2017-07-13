package info.dvkr.screenstream.dagger.module

import android.app.Application
import android.content.Context
import android.os.HandlerThread
import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.model.EventBus
import info.dvkr.screenstream.model.ImageNotify
import info.dvkr.screenstream.model.eventbus.EventBusImpl
import info.dvkr.screenstream.model.image.ImageNotifyImpl
import rx.Scheduler
import rx.android.schedulers.AndroidSchedulers
import javax.inject.Singleton

@Singleton
@Module
class AppModule(private val application: Application) {

    private val eventThread = HandlerThread("SSEventThread")
    private val eventScheduler: Scheduler

    init {
        eventThread.start()
        eventScheduler = AndroidSchedulers.from(eventThread.looper)
    }

    @Provides
    @Singleton
    internal fun providesApplication(): Application = application

    @Provides
    @Singleton
    internal fun provideContext(): Context = application.applicationContext

    @Provides
    @Singleton
    internal fun provideEventScheduler(): Scheduler = eventScheduler

    // TODO Is this correct place?
    @Provides
    @Singleton
    internal fun provideEventBus(): EventBus = EventBusImpl()

    // TODO Is this correct place?
//    @Provides
//    @Singleton
//    internal fun provideAppEvent(): AppStatus = AppStatusImpl()

    // TODO Is this correct place?
    @Provides
    @Singleton
    internal fun provideImageNotify(context: Context): ImageNotify = ImageNotifyImpl(context)
}