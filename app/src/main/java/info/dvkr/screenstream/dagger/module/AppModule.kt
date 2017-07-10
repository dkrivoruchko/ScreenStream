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
class AppModule(private val mApplication: Application) {

    private val mEventThread = HandlerThread("SSEventThread")
    private val mEventScheduler: Scheduler

    init {
        mEventThread.start()
        mEventScheduler = AndroidSchedulers.from(mEventThread.looper)
    }

    @Provides
    @Singleton
    internal fun providesApplication(): Application = mApplication

    @Provides
    @Singleton
    internal fun provideContext(): Context = mApplication.applicationContext

    @Provides
    @Singleton
    internal fun provideEventScheduler(): Scheduler = mEventScheduler

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