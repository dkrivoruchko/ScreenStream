package info.dvkr.screenstream.ui.activity

import androidx.annotation.LayoutRes
import info.dvkr.screenstream.data.settings.Settings
import org.koin.android.ext.android.inject

abstract class AppUpdateActivity(@LayoutRes contentLayoutId: Int) : BaseActivity(contentLayoutId) {

    protected val settings: Settings by inject()

}