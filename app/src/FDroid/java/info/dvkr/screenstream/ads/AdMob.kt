package info.dvkr.screenstream.ads

import android.app.Activity
import androidx.activity.ComponentActivity
import org.koin.core.annotation.Singleton

internal fun attachAdMob(activity: ComponentActivity) {}

@Singleton
public class AdMob {
    public var isPrivacyOptionsRequired: Boolean = false
        private set

    public fun showPrivacyOptionsForm(activity: Activity, onFailure: () -> Unit) {}
}
