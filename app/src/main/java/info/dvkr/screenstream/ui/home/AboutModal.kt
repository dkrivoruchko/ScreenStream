package info.dvkr.screenstream.ui.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ads.AdMob
import info.dvkr.screenstream.ui.AdaptiveAppModal
import kotlinx.coroutines.isActive
import org.koin.compose.koinInject

@Composable
internal fun AboutModal(onDismissRequest: () -> Unit, dialogBounds: DpRect?) {
    val scroll = retain { ScrollState(0) }
    AdaptiveAppModal(title = stringResource(R.string.app_about_title), onDismissRequest = onDismissRequest, dialogBounds = dialogBounds) {
        val adMob = koinInject<AdMob>()
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
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll),
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

            val versionName = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "unknown"
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
                val privacyOptionsAction = if (adMob.isPrivacyOptionsRequired && activity != null) {
                    Triple(
                        R.drawable.app_icon_settings,
                        R.string.app_about_privacy_options,
                        dropUnlessResumed {
                            adMob.showPrivacyOptionsForm(activity) {
                                if (scope.isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                    Toast.makeText(
                                        context,
                                        R.string.app_about_privacy_options_open_error,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    )
                } else {
                    null
                }
                val sourceUrl = "https://github.com/dkrivoruchko/ScreenStream"
                val actions = listOfNotNull(
                    Triple(R.drawable.app_icon_star_rate, R.string.app_about_rate_app) {
                        openUrl(
                            "market://details?id=${context.packageName}",
                            fallbackUrl = "https://play.google.com/store/apps/details?id=${context.packageName}",
                        )
                    },
                    Triple(R.drawable.app_icon_github, R.string.app_about_source_code) {
                        openUrl(sourceUrl)
                    },
                    Triple(R.drawable.app_icon_receipt_long, R.string.app_about_terms_and_conditions) {
                        openUrl("$sourceUrl/blob/master/TermsConditions.md")
                    },
                    Triple(R.drawable.app_icon_privacy_tip, R.string.app_about_privacy_policy) {
                        openUrl("$sourceUrl/blob/master/PrivacyPolicy.md")
                    },
                    privacyOptionsAction,
                    Triple(R.drawable.app_icon_license, R.string.app_about_license) {
                        openUrl("$sourceUrl/blob/master/LICENSE")
                    },
                )
                for ((iconRes, labelRes, onClick) in actions) {
                    key(labelRes) {
                        TextButton(
                            onClick = onClick,
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .fillMaxWidth(),
                        ) {
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(text = stringResource(labelRes), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
