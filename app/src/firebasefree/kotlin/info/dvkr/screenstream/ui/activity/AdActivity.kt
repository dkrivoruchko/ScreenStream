package info.dvkr.screenstream.ui.activity

import androidx.annotation.LayoutRes

abstract class AdActivity(@LayoutRes contentLayoutId: Int) : AppUpdateActivity(contentLayoutId) {
    internal val isConsentEnabled = false
    internal fun showConsentForm() {}
}