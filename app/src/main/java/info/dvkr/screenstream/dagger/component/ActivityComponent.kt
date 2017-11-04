package info.dvkr.screenstream.dagger.component

import dagger.Subcomponent
import info.dvkr.screenstream.ui.ClientsActivity
import info.dvkr.screenstream.ui.SettingsActivity
import info.dvkr.screenstream.ui.StartActivity

@Subcomponent
interface ActivityComponent {

    fun inject(activity: StartActivity)

    fun inject(activity: SettingsActivity)

    fun inject(activity: ClientsActivity)
}