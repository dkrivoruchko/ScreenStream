package info.dvkr.screenstream.dagger.module

import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.model.GlobalStatus
import info.dvkr.screenstream.model.appevent.GlobalStatusImpl
import javax.inject.Singleton

@Singleton
@Module
class GlobalStatusModule {

    @Provides
    @Singleton
    internal fun provideGlobalStatus(): GlobalStatus = GlobalStatusImpl()
}