package info.dvkr.screenstream.dagger.module

import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.data.presenter.PresenterFactory
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import info.dvkr.screenstream.domain.settings.Settings
import rx.Scheduler
import javax.inject.Singleton

@Singleton
@Module(includes = arrayOf(
        SettingsModule::class,
        EventBusModule::class,
        GlobalStatusModule::class)
)
class PresenterFactoryModule {

    @Provides
    @Singleton
    internal fun providePresenterFactory(settings: Settings,
                                         eventScheduler: Scheduler,
                                         eventBus: EventBus,
                                         globalStatus: GlobalStatus): PresenterFactory {
        return PresenterFactory(settings, eventScheduler, eventBus, globalStatus)
    }
}