package info.dvkr.screenstream.dagger.component


import dagger.Component
import info.dvkr.screenstream.ScreenStreamApp
import info.dvkr.screenstream.dagger.module.AppModule
import info.dvkr.screenstream.dagger.module.ImageNotifyModule
import info.dvkr.screenstream.dagger.module.SettingsModule
import info.dvkr.screenstream.data.dagger.module.EventBusModule
import info.dvkr.screenstream.data.dagger.module.GlobalStatusModule
import javax.inject.Singleton

@Singleton
@Component(modules = arrayOf(
        AppModule::class,
        EventBusModule::class,
        GlobalStatusModule::class,
        ImageNotifyModule::class,
        SettingsModule::class))
interface AppComponent {
    fun plusActivityComponent(): NonConfigurationComponent

    fun inject(screenStreamApp: ScreenStreamApp)
}