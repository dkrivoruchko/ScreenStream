package info.dvkr.screenstream

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

public class AdMob(context: Context) {
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
