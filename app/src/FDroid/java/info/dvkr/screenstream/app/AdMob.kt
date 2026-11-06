package info.dvkr.screenstream.app

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.koin.core.annotation.Singleton

@Singleton
public class AdMob {
    public var isPrivacyOptionsRequired: Boolean = false
        private set

    public fun showPrivacyOptionsForm(activity: Activity) {}
}

@Composable
public fun AnchoredAdaptiveBanner(modifier: Modifier = Modifier) {
}

@Composable
public fun InlineAdaptiveBanner(modifier: Modifier = Modifier) {
}
