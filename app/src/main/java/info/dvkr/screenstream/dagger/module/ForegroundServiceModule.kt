package info.dvkr.screenstream.dagger.module

import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.data.presenter.foreground.ForegroundPresenter
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import info.dvkr.screenstream.domain.settings.Settings
import rx.Scheduler

@Module(includes = arrayOf(
        SettingsModule::class,
        EventBusModule::class,
        GlobalStatusModule::class)
)
class ForegroundServiceModule {

    @Provides
    internal fun provideForegroundPresenter(settings: Settings,
                                            eventScheduler: Scheduler,
                                            eventBus: EventBus,
                                            globalStatus: GlobalStatus): ForegroundPresenter {
        return ForegroundPresenter(settings, eventScheduler, eventBus, globalStatus)
    }
}