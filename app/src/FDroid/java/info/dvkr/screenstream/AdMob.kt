package info.dvkr.screenstream

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

public class AdMob(context: Context) {
    public val isPrivacyOptionsRequired: Boolean = false

    public fun showPrivacyOptionsForm(activity: Activity) {}
}

@Composable
public fun AdaptiveBanner(modifier: Modifier = Modifier, collapsible: Boolean = false) {
}