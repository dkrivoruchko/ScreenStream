package info.dvkr.screenstream.dagger.component


import dagger.Component
import info.dvkr.screenstream.ScreenStreamApp
import info.dvkr.screenstream.dagger.module.*
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