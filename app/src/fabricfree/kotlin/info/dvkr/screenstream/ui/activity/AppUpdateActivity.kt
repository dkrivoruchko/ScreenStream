package info.dvkr.screenstream.ui.activity

import info.dvkr.screenstream.data.settings.Settings
import org.koin.android.ext.android.inject

abstract class AppUpdateActivity : BaseActivity() {

    protected val settings: Settings by inject()


}