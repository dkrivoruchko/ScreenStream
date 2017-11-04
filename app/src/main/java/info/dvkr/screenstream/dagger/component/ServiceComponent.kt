package info.dvkr.screenstream.dagger.component

import dagger.Subcomponent
import info.dvkr.screenstream.service.BootReceiver
import info.dvkr.screenstream.service.ForegroundService

@Subcomponent
interface ServiceComponent {

    fun inject(service: ForegroundService)

    fun inject(service: BootReceiver)
}