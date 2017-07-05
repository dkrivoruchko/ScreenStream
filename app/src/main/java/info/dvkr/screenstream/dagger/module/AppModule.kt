package info.dvkr.screenstream.dagger.module

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.model.AppEvent
import info.dvkr.screenstream.model.ImageNotify
import info.dvkr.screenstream.model.appevent.AppEventImpl
import info.dvkr.screenstream.model.image.ImageNotifyImpl
import javax.inject.Singleton

@Singleton
@Module
class AppModule(private val mApplication: Application) {

    @Provides
    @Singleton
    internal fun providesApplication(): Application = mApplication

    @Provides
    @Singleton
    internal fun provideContext(): Context = mApplication.applicationContext

    // TODO Is this correct place?
    @Provides
    @Singleton
    internal fun provideAppEvent(): AppEvent = AppEventImpl()

    // TODO Is this correct place?
    @Provides
    @Singleton
    internal fun provideImageNotify(context: Context): ImageNotify = ImageNotifyImpl(context)
}