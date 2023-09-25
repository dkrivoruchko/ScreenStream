package info.dvkr.screenstream.ui.activity

import androidx.annotation.LayoutRes
import info.dvkr.screenstream.activity.BaseActivity
import info.dvkr.screenstream.settings.AppSettings
import org.koin.android.ext.android.inject

public abstract class AppUpdateActivity(@LayoutRes contentLayoutId: Int) : BaseActivity(contentLayoutId) {

    protected val appSettings: AppSettings by inject(mode = LazyThreadSafetyMode.NONE)
}