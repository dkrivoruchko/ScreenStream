package info.dvkr.screenstream.ui.tabs.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.AdMob
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.findActivity
import info.dvkr.screenstream.common.getVersionName
import info.dvkr.screenstream.common.openStringUrl
import info.dvkr.screenstream.logger.AppLogger
import org.koin.compose.koinInject

@Composable
public fun AboutTabContent(
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
//        val loggerClicksCounter = remember { mutableIntStateOf(0) }
        Text(
            text = stringResource(id = R.string.app_tab_about_app_version, context.getVersionName()),
            modifier = Modifier
                .padding(8.dp)
//                .clickable { //TODO AppLogger doest send logs correctly
//                    if (AppLogger.isLoggingOn) return@clickable
//                    loggerClicksCounter.intValue += 1
//                    if (loggerClicksCounter.intValue >= 5) AppLogger.enableLogging(context)
//                }
                .padding(8.dp),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(id = R.string.app_tab_about_developer_name),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
            TextButton(
                onClick = {
                    context.openStringUrl("market://details?id=${context.packageName}") {
                        context.openStringUrl("https://play.google.com/store/apps/details?id=${context.packageName}")
                    }
                },
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(R.drawable.star_rate_24px),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(text = stringResource(id = R.string.app_tab_about_rate_app), maxLines = 1)
            }

            TextButton(
                onClick = { context.openStringUrl("https://github.com/dkrivoruchko/ScreenStream") },
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                Icon(painter = painterResource(R.drawable.github), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(text = stringResource(id = R.string.app_tab_about_app_sources), maxLines = 1)
            }

            TextButton(
                onClick = { context.openStringUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/TermsConditions.md") },
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(R.drawable.receipt_long_24px),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(text = stringResource(id = R.string.app_tab_about_terms_conditions), maxLines = 1)
            }

            TextButton(
                onClick = { context.openStringUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/PrivacyPolicy.md") },
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(R.drawable.privacy_tip_24px),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(text = stringResource(id = R.string.app_tab_about_privacy_policy), maxLines = 1)
            }

            if (adMob.isPrivacyOptionsRequired) {
                TextButton(
                    onClick = { adMob.showPrivacyOptionsForm(context.findActivity()) },
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()

                ) {
                    Icon(
                        painter = painterResource(R.drawable.receipt_long_24px),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(text = stringResource(id = R.string.app_tab_about_privacy_options), maxLines = 1)
                }
            }

            TextButton(
                onClick = { context.openStringUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/LICENSE") },
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                Icon(painter = painterResource(R.drawable.license_24px), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(text = stringResource(id = R.string.app_tab_about_license), maxLines = 1)
            }
        }
    }
}
