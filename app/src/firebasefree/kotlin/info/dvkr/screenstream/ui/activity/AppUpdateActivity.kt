package info.dvkr.screenstream.ui.activity

import androidx.annotation.LayoutRes
import info.dvkr.screenstream.common.settings.AppSettings
import org.koin.android.ext.android.inject

abstract class AppUpdateActivity(@LayoutRes contentLayoutId: Int) : BaseActivity(contentLayoutId) {

    protected val appSettings: AppSettings by inject()

}