package info.dvkr.screenstream

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.koin.core.annotation.Single

@Single
public class AdMob {
    public val isPrivacyOptionsRequired: Boolean = false

    public fun showPrivacyOptionsForm(activity: Activity) {}
}

@Composable
public fun AdaptiveBanner(modifier: Modifier = Modifier, collapsible: Boolean = false) {
}