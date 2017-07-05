package info.dvkr.screenstream.dagger.component


import dagger.Component
import info.dvkr.screenstream.ScreenStreamApp
import info.dvkr.screenstream.dagger.module.AppModule
import info.dvkr.screenstream.dagger.module.SettingsModule
import javax.inject.Singleton

@Singleton
@Component(modules = arrayOf(AppModule::class, SettingsModule::class))
interface AppComponent {
    fun plusActivityComponent(): NonConfigurationComponent

    fun inject(screenStreamApp: ScreenStreamApp)
}