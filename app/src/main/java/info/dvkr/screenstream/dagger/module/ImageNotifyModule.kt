package info.dvkr.screenstream.dagger.module

import android.content.Context
import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.data.image.ImageNotify
import info.dvkr.screenstream.data.image.ImageNotifyImpl
import javax.inject.Singleton

@Singleton
@Module(includes = arrayOf(AppModule::class))
class ImageNotifyModule {

    @Provides
    @Singleton
    internal fun provideImageNotify(context: Context): ImageNotify = ImageNotifyImpl(context)
}