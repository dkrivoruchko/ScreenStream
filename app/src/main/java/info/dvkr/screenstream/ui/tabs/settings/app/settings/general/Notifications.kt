package info.dvkr.screenstream.ui.tabs.settings.app.settings.general

import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.common.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import org.koin.compose.koinInject

internal object Notifications : ModuleSettings.Item {
    override val id: String = "NOTIFICATIONS"
    override val position: Int = 3
    override val available: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.app_pref_notification).contains(text, ignoreCase = true) ||
                getString(R.string.app_pref_notification_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ListUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) =
        NotificationsUI(horizontalPadding)
}

@Composable
private fun NotificationsUI(
    horizontalPadding: Dp,
    notificationHelper: NotificationHelper = koinInject()
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .clickable(role = Role.Button) { context.startActivity(notificationHelper.getNotificationSettingsIntent()) }
            .padding(horizontal = horizontalPadding + 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Notifications,
            contentDescription = stringResource(id = R.string.app_pref_notification),
            modifier = Modifier.padding(end = 16.dp)
        )
        Column(modifier = Modifier.weight(1.0F)) {
            Text(
                text = stringResource(id = R.string.app_pref_notification),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.app_pref_notification_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}