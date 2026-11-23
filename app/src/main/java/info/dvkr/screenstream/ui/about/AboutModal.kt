package info.dvkr.screenstream.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ads.AdMob
import info.dvkr.screenstream.ui.AdaptiveAppModal
import info.dvkr.screenstream.ui.ModalPane
import kotlinx.coroutines.isActive
import org.koin.compose.koinInject

@Composable
internal fun AboutModal(onDismiss: () -> Unit, pane: ModalPane?) {
    val scroll = retain { ScrollState(0) }
    AdaptiveAppModal(title = stringResource(R.string.app_about_title), onDismiss = onDismiss, pane = pane) {
        AboutContent(
            scroll,
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
        )
    }
}

@Composable
private fun AboutContent(
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    adMob: AdMob = koinInject()
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    fun tryOpenUrl(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun openUrl(url: String, fallbackUrl: String? = null) {
        if (tryOpenUrl(url) || (fallbackUrl != null && tryOpenUrl(fallbackUrl))) return
        Toast.makeText(context, R.string.app_about_open_link_error, Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier.verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = null,
            modifier = Modifier
                .padding(top = 8.dp)
                .size(104.dp)
                .clip(MaterialTheme.shapes.medium)
        )

        Text(
            text = stringResource(id = R.string.app_name),
            modifier = Modifier.padding(top = 16.dp),
            maxLines = 1,
            style = MaterialTheme.typography.headlineSmall
        )

        val versionName = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
        Text(
            text = stringResource(id = R.string.app_about_version, versionName),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(id = R.string.app_about_developer),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
            AboutActionButton(
                iconRes = R.drawable.app_icon_star_rate,
                labelRes = R.string.app_about_rate_app,
                onClick = {
                    openUrl(
                        "market://details?id=${context.packageName}",
                        fallbackUrl = "https://play.google.com/store/apps/details?id=${context.packageName}",
                    )
                },
            )
            AboutActionButton(
                iconRes = R.drawable.app_icon_github,
                labelRes = R.string.app_about_source_code,
                onClick = { openUrl("https://github.com/dkrivoruchko/ScreenStream") },
            )
            AboutActionButton(
                iconRes = R.drawable.app_icon_receipt_long,
                labelRes = R.string.app_about_terms_and_conditions,
                onClick = { openUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/TermsConditions.md") },
            )
            AboutActionButton(
                iconRes = R.drawable.app_icon_privacy_tip,
                labelRes = R.string.app_about_privacy_policy,
                onClick = { openUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/PrivacyPolicy.md") },
            )
            if (adMob.isPrivacyOptionsRequired && activity != null) {
                AboutActionButton(
                    iconRes = R.drawable.app_icon_settings,
                    labelRes = R.string.app_about_privacy_options,
                    onClick = dropUnlessResumed {
                        adMob.showPrivacyOptionsForm(activity) {
                            if (scope.isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                Toast.makeText(context, R.string.app_about_privacy_options_open_error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
            AboutActionButton(
                iconRes = R.drawable.app_icon_license,
                labelRes = R.string.app_about_license,
                onClick = { openUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/LICENSE") },
            )
        }
    }
}

@Composable
private fun AboutActionButton(
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth(),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text(text = stringResource(labelRes), maxLines = 1)
    }
}
