package info.dvkr.screenstream.ads

import android.app.Activity
import androidx.activity.ComponentActivity
import org.koin.core.annotation.Singleton

@Singleton
public class AdMob {
    public var isPrivacyOptionsRequired: Boolean = false
        private set

    internal fun beginAppLaunch() {}

    internal fun attach(activity: ComponentActivity) {}

    public fun showPrivacyOptionsForm(activity: Activity, onFailure: () -> Unit) {}
}
