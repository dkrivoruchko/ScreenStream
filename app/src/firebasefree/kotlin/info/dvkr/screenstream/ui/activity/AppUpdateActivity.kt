package info.dvkr.screenstream.ui.activity

import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.lifecycle.lifecycleScope
import info.dvkr.screenstream.common.settings.AppSettings
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

abstract class AppUpdateActivity(@LayoutRes contentLayoutId: Int) : BaseActivity(contentLayoutId) {

    protected val appSettings: AppSettings by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch { appSettings.setStreamMode(AppSettings.Values.STREAM_MODE_MJPEG) }
    }
}