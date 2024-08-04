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
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
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
import androidx.compose.ui.graphics.vector.ImageVector
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
        val loggerClicksCounter = remember { mutableIntStateOf(0) }
        Text(
            text = stringResource(id = R.string.app_tab_about_app_version, context.getVersionName()),
            modifier = Modifier
                .padding(8.dp)
                .clickable {
                    if (AppLogger.isLoggingOn) return@clickable
                    loggerClicksCounter.intValue += 1
                    if (loggerClicksCounter.intValue >= 5) AppLogger.enableLogging(context)
                }
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
                Icon(imageVector = Icon_Stars, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(text = stringResource(id = R.string.app_tab_about_rate_app), maxLines = 1)
            }

            TextButton(
                onClick = { context.openStringUrl("https://github.com/dkrivoruchko/ScreenStream") },
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                Icon(imageVector = Icon_GitHub, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(text = stringResource(id = R.string.app_tab_about_app_sources), maxLines = 1)
            }

            TextButton(
                onClick = { context.openStringUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/TermsConditions.md") },
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icon_ReceiptLong,
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
                Icon(imageVector = Icon_Policy, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(text = stringResource(id = R.string.app_tab_about_privacy_policy), maxLines = 1)
            }

            if (adMob.isPrivacyOptionsRequired) {
                TextButton(
                    onClick = { adMob.showPrivacyOptionsForm(context.findActivity()) },
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()

                ) {
                    Icon(imageVector = Icon_Receipt, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(text = stringResource(id = R.string.app_tab_about_privacy_options), maxLines = 1)
                }
            }

            TextButton(
                onClick = { context.openStringUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/LICENSE") },
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                Icon(imageVector = Icon_License, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(text = stringResource(id = R.string.app_tab_about_license), maxLines = 1)
            }
        }
    }
}

private val Icon_Stars: ImageVector = materialIcon(name = "Filled.Stars") {
    materialPath {
        moveTo(11.99f, 2.0f)
        curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
        reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
        curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
        reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
        close()
        moveTo(16.23f, 18.0f)
        lineTo(12.0f, 15.45f)
        lineTo(7.77f, 18.0f)
        lineToRelative(1.12f, -4.81f)
        lineToRelative(-3.73f, -3.23f)
        lineToRelative(4.92f, -0.42f)
        lineTo(12.0f, 5.0f)
        lineToRelative(1.92f, 4.53f)
        lineToRelative(4.92f, 0.42f)
        lineToRelative(-3.73f, 3.23f)
        lineTo(16.23f, 18.0f)
        close()
    }
}

private val Icon_GitHub: ImageVector = materialIcon(name = "GitHub") {
    materialPath {
        verticalLineToRelative(0.0F)
        moveTo(12.0F, 2.0F)
        arcTo(10.0F, 10.0F, 0.0F, false, false, 2.0F, 12.0F)
        curveTo(2.0F, 16.42F, 4.87F, 20.17F, 8.84F, 21.5F)
        curveTo(9.34F, 21.58F, 9.5F, 21.27F, 9.5F, 21.0F)
        curveTo(9.5F, 20.77F, 9.5F, 20.14F, 9.5F, 19.31F)
        curveTo(6.73F, 19.91F, 6.14F, 17.97F, 6.14F, 17.97F)
        curveTo(5.68F, 16.81F, 5.03F, 16.5F, 5.03F, 16.5F)
        curveTo(4.12F, 15.88F, 5.1F, 15.9F, 5.1F, 15.9F)
        curveTo(6.1F, 15.97F, 6.63F, 16.93F, 6.63F, 16.93F)
        curveTo(7.5F, 18.45F, 8.97F, 18.0F, 9.54F, 17.76F)
        curveTo(9.63F, 17.11F, 9.89F, 16.67F, 10.17F, 16.42F)
        curveTo(7.95F, 16.17F, 5.62F, 15.31F, 5.62F, 11.5F)
        curveTo(5.62F, 10.39F, 6.0F, 9.5F, 6.65F, 8.79F)
        curveTo(6.55F, 8.54F, 6.2F, 7.5F, 6.75F, 6.15F)
        curveTo(6.75F, 6.15F, 7.59F, 5.88F, 9.5F, 7.17F)
        curveTo(10.29F, 6.95F, 11.15F, 6.84F, 12.0F, 6.84F)
        curveTo(12.85F, 6.84F, 13.71F, 6.95F, 14.5F, 7.17F)
        curveTo(16.41F, 5.88F, 17.25F, 6.15F, 17.25F, 6.15F)
        curveTo(17.8F, 7.5F, 17.45F, 8.54F, 17.35F, 8.79F)
        curveTo(18.0F, 9.5F, 18.38F, 10.39F, 18.38F, 11.5F)
        curveTo(18.38F, 15.32F, 16.04F, 16.16F, 13.81F, 16.41F)
        curveTo(14.17F, 16.72F, 14.5F, 17.33F, 14.5F, 18.26F)
        curveTo(14.5F, 19.6F, 14.5F, 20.68F, 14.5F, 21.0F)
        curveTo(14.5F, 21.27F, 14.66F, 21.59F, 15.17F, 21.5F)
        curveTo(19.14F, 20.16F, 22.0F, 16.42F, 22.0F, 12.0F)
        arcTo(10.0F, 10.0F, 0.0F, false, false, 12.0F, 2.0F)
        close()
    }
}

private val Icon_ReceiptLong: ImageVector = materialIcon(name = "AutoMirrored.Filled.ReceiptLong", autoMirror = true) {
    materialPath {
        moveTo(19.5f, 3.5f)
        lineTo(18.0f, 2.0f)
        lineToRelative(-1.5f, 1.5f)
        lineTo(15.0f, 2.0f)
        lineToRelative(-1.5f, 1.5f)
        lineTo(12.0f, 2.0f)
        lineToRelative(-1.5f, 1.5f)
        lineTo(9.0f, 2.0f)
        lineTo(7.5f, 3.5f)
        lineTo(6.0f, 2.0f)
        verticalLineToRelative(14.0f)
        horizontalLineTo(3.0f)
        verticalLineToRelative(3.0f)
        curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
        horizontalLineToRelative(12.0f)
        curveToRelative(1.66f, 0.0f, 3.0f, -1.34f, 3.0f, -3.0f)
        verticalLineTo(2.0f)
        lineTo(19.5f, 3.5f)
        close()
        moveTo(19.0f, 19.0f)
        curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
        reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
        verticalLineToRelative(-3.0f)
        horizontalLineTo(8.0f)
        verticalLineTo(5.0f)
        horizontalLineToRelative(11.0f)
        verticalLineTo(19.0f)
        close()
    }
    materialPath {
        moveTo(9.0f, 7.0f)
        horizontalLineToRelative(6.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(-6.0f)
        close()
    }
    materialPath {
        moveTo(16.0f, 7.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(-2.0f)
        close()
    }
    materialPath {
        moveTo(9.0f, 10.0f)
        horizontalLineToRelative(6.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(-6.0f)
        close()
    }
    materialPath {
        moveTo(16.0f, 10.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(-2.0f)
        close()
    }
}

private val Icon_Policy: ImageVector = materialIcon(name = "Filled.Policy") {
    materialPath {
        moveTo(21.0f, 5.0f)
        lineToRelative(-9.0f, -4.0f)
        lineTo(3.0f, 5.0f)
        verticalLineToRelative(6.0f)
        curveToRelative(0.0f, 5.55f, 3.84f, 10.74f, 9.0f, 12.0f)
        curveToRelative(2.3f, -0.56f, 4.33f, -1.9f, 5.88f, -3.71f)
        lineToRelative(-3.12f, -3.12f)
        curveToRelative(-1.94f, 1.29f, -4.58f, 1.07f, -6.29f, -0.64f)
        curveToRelative(-1.95f, -1.95f, -1.95f, -5.12f, 0.0f, -7.07f)
        curveToRelative(1.95f, -1.95f, 5.12f, -1.95f, 7.07f, 0.0f)
        curveToRelative(1.71f, 1.71f, 1.92f, 4.35f, 0.64f, 6.29f)
        lineToRelative(2.9f, 2.9f)
        curveTo(20.29f, 15.69f, 21.0f, 13.38f, 21.0f, 11.0f)
        verticalLineTo(5.0f)
        close()
    }
    materialPath {
        moveTo(12.0f, 12.0f)
        moveToRelative(-3.0f, 0.0f)
        arcToRelative(3.0f, 3.0f, 0.0f, true, true, 6.0f, 0.0f)
        arcToRelative(3.0f, 3.0f, 0.0f, true, true, -6.0f, 0.0f)
    }
}

private val Icon_Receipt: ImageVector = materialIcon(name = "Filled.Receipt") {
    materialPath {
        moveTo(18.0f, 17.0f)
        lineTo(6.0f, 17.0f)
        verticalLineToRelative(-2.0f)
        horizontalLineToRelative(12.0f)
        verticalLineToRelative(2.0f)
        close()
        moveTo(18.0f, 13.0f)
        lineTo(6.0f, 13.0f)
        verticalLineToRelative(-2.0f)
        horizontalLineToRelative(12.0f)
        verticalLineToRelative(2.0f)
        close()
        moveTo(18.0f, 9.0f)
        lineTo(6.0f, 9.0f)
        lineTo(6.0f, 7.0f)
        horizontalLineToRelative(12.0f)
        verticalLineToRelative(2.0f)
        close()
        moveTo(3.0f, 22.0f)
        lineToRelative(1.5f, -1.5f)
        lineTo(6.0f, 22.0f)
        lineToRelative(1.5f, -1.5f)
        lineTo(9.0f, 22.0f)
        lineToRelative(1.5f, -1.5f)
        lineTo(12.0f, 22.0f)
        lineToRelative(1.5f, -1.5f)
        lineTo(15.0f, 22.0f)
        lineToRelative(1.5f, -1.5f)
        lineTo(18.0f, 22.0f)
        lineToRelative(1.5f, -1.5f)
        lineTo(21.0f, 22.0f)
        lineTo(21.0f, 2.0f)
        lineToRelative(-1.5f, 1.5f)
        lineTo(18.0f, 2.0f)
        lineToRelative(-1.5f, 1.5f)
        lineTo(15.0f, 2.0f)
        lineToRelative(-1.5f, 1.5f)
        lineTo(12.0f, 2.0f)
        lineToRelative(-1.5f, 1.5f)
        lineTo(9.0f, 2.0f)
        lineTo(7.5f, 3.5f)
        lineTo(6.0f, 2.0f)
        lineTo(4.5f, 3.5f)
        lineTo(3.0f, 2.0f)
        verticalLineToRelative(20.0f)
        close()
    }
}

private val Icon_License: ImageVector = materialIcon(name = "License") {
    materialPath {
        verticalLineToRelative(0.0F)
        moveTo(9.0F, 10.0F)
        arcTo(3.04F, 3.04F, 0.0F, false, true, 12.0F, 7.0F)
        arcTo(3.04F, 3.04F, 0.0F, false, true, 15.0F, 10.0F)
        arcTo(3.04F, 3.04F, 0.0F, false, true, 12.0F, 13.0F)
        arcTo(3.04F, 3.04F, 0.0F, false, true, 9.0F, 10.0F)
        moveTo(12.0F, 19.0F)
        lineTo(16.0F, 20.0F)
        verticalLineTo(16.92F)
        arcTo(7.54F, 7.54F, 0.0F, false, true, 12.0F, 18.0F)
        arcTo(7.54F, 7.54F, 0.0F, false, true, 8.0F, 16.92F)
        verticalLineTo(20.0F)
        moveTo(12.0F, 4.0F)
        arcTo(5.78F, 5.78F, 0.0F, false, false, 7.76F, 5.74F)
        arcTo(5.78F, 5.78F, 0.0F, false, false, 6.0F, 10.0F)
        arcTo(5.78F, 5.78F, 0.0F, false, false, 7.76F, 14.23F)
        arcTo(5.78F, 5.78F, 0.0F, false, false, 12.0F, 16.0F)
        arcTo(5.78F, 5.78F, 0.0F, false, false, 16.24F, 14.23F)
        arcTo(5.78F, 5.78F, 0.0F, false, false, 18.0F, 10.0F)
        arcTo(5.78F, 5.78F, 0.0F, false, false, 16.24F, 5.74F)
        arcTo(5.78F, 5.78F, 0.0F, false, false, 12.0F, 4.0F)
        moveTo(20.0F, 10.0F)
        arcTo(8.04F, 8.04F, 0.0F, false, true, 19.43F, 12.8F)
        arcTo(7.84F, 7.84F, 0.0F, false, true, 18.0F, 15.28F)
        verticalLineTo(23.0F)
        lineTo(12.0F, 21.0F)
        lineTo(6.0F, 23.0F)
        verticalLineTo(15.28F)
        arcTo(7.9F, 7.9F, 0.0F, false, true, 4.0F, 10.0F)
        arcTo(7.68F, 7.68F, 0.0F, false, true, 6.33F, 4.36F)
        arcTo(7.73F, 7.73F, 0.0F, false, true, 12.0F, 2.0F)
        arcTo(7.73F, 7.73F, 0.0F, false, true, 17.67F, 4.36F)
        arcTo(7.68F, 7.68F, 0.0F, false, true, 20.0F, 10.0F)
        close()
    }
}