package info.dvkr.screenstream.data.dagger.module

import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatusImpl
import javax.inject.Singleton

@Singleton
@Module
class GlobalStatusModule {

    @Provides
    @Singleton
    internal fun provideGlobalStatus(): GlobalStatus = GlobalStatusImpl()
}