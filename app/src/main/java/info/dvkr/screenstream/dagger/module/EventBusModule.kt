package info.dvkr.screenstream.dagger.module

import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.model.EventBus
import info.dvkr.screenstream.model.eventbus.EventBusImpl
import javax.inject.Singleton

@Singleton
@Module
class EventBusModule {

    @Provides
    @Singleton
    internal fun provideEventBus(): EventBus = EventBusImpl()
}