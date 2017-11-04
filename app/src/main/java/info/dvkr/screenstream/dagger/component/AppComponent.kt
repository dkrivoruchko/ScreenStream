package info.dvkr.screenstream.dagger.component


import dagger.Component
import info.dvkr.screenstream.ScreenStreamApp
import info.dvkr.screenstream.dagger.module.AppModule
import info.dvkr.screenstream.dagger.module.EventBusModule
import info.dvkr.screenstream.dagger.module.ForegroundServiceModule
import info.dvkr.screenstream.dagger.module.GlobalStatusModule
import info.dvkr.screenstream.dagger.module.ImageNotifyModule
import info.dvkr.screenstream.dagger.module.PresenterFactoryModule
import info.dvkr.screenstream.dagger.module.SettingsModule
import javax.inject.Singleton

@Singleton
@Component(modules = arrayOf(
        AppModule::class,
        EventBusModule::class,
        GlobalStatusModule::class,
        ImageNotifyModule::class,
        PresenterFactoryModule::class,
        ForegroundServiceModule::class,
        SettingsModule::class)
)
interface AppComponent {
    fun inject(screenStreamApp: ScreenStreamApp)

    fun activityComponent(): ActivityComponent

    fun serviceComponent(): ServiceComponent
}