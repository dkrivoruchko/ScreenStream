package info.dvkr.screenstream.dagger.component

import dagger.Subcomponent
import info.dvkr.screenstream.dagger.PersistentScope
import info.dvkr.screenstream.service.BootReceiver
import info.dvkr.screenstream.service.ForegroundService
import info.dvkr.screenstream.ui.SettingsActivity
import info.dvkr.screenstream.ui.StartActivity

@PersistentScope
@Subcomponent
interface NonConfigurationComponent {

    fun inject(activity: StartActivity)

    fun inject(activity: SettingsActivity)

    fun inject(service: ForegroundService)

    fun inject(service: BootReceiver)
}