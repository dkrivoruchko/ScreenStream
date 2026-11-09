package info.dvkr.screenstream.ui.about

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.R
import info.dvkr.screenstream.app.AdMob
import info.dvkr.screenstream.common.findActivity
import info.dvkr.screenstream.common.getVersionName
import info.dvkr.screenstream.common.openStringUrl
import org.koin.compose.koinInject

@Composable
internal fun AboutScreen(
    modifier: Modifier = Modifier,
    adMob: AdMob = koinInject()
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
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

        val context = LocalContext.current
        Text(
            text = stringResource(id = R.string.app_tab_about_app_version, context.getVersionName()),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(id = R.string.app_tab_about_developer_name),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
            AboutActionButton(
                iconRes = R.drawable.star_rate_24px,
                labelRes = R.string.app_tab_about_rate_app,
                onClick = {
                    context.openStringUrl("market://details?id=${context.packageName}") {
                        context.openStringUrl("https://play.google.com/store/apps/details?id=${context.packageName}")
                    }
                },
            )
            AboutActionButton(
                iconRes = R.drawable.github,
                labelRes = R.string.app_tab_about_app_sources,
                onClick = { context.openStringUrl("https://github.com/dkrivoruchko/ScreenStream") },
            )
            AboutActionButton(
                iconRes = R.drawable.receipt_long_24px,
                labelRes = R.string.app_tab_about_terms_conditions,
                onClick = { context.openStringUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/TermsConditions.md") },
            )
            AboutActionButton(
                iconRes = R.drawable.privacy_tip_24px,
                labelRes = R.string.app_tab_about_privacy_policy,
                onClick = { context.openStringUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/PrivacyPolicy.md") },
            )
            if (adMob.isPrivacyOptionsRequired) {
                AboutActionButton(
                    iconRes = R.drawable.receipt_long_24px,
                    labelRes = R.string.app_tab_about_privacy_options,
                    onClick = { adMob.showPrivacyOptionsForm(context.findActivity()) },
                )
            }
            AboutActionButton(
                iconRes = R.drawable.license_24px,
                labelRes = R.string.app_tab_about_license,
                onClick = { context.openStringUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/LICENSE") },
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
