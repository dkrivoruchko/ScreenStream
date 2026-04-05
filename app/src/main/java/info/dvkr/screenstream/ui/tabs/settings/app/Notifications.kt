package info.dvkr.screenstream.ui.tabs.settings.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.tabs.settings.app.common.SettingActionRow

@Composable
internal fun NotificationsRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingActionRow(
        iconRes = R.drawable.notifications_24px,
        title = stringResource(id = R.string.app_pref_notification),
        summary = stringResource(id = R.string.app_pref_notification_summary),
        onClick = onClick,
        modifier = modifier
    )
}
