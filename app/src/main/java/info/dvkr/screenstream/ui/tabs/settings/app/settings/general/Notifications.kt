package info.dvkr.screenstream.ui.tabs.settings.app.settings.general

import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val notificationHelper = koinInject<NotificationHelper>()
        val context = LocalContext.current

        NotificationsUI(horizontalPadding) { context.startActivity(notificationHelper.getNotificationSettingsIntent()) }
    }
}

@Composable
private fun NotificationsUI(
    horizontalPadding: Dp,
    onDetailShow: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onDetailShow)
            .padding(horizontal = horizontalPadding + 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Notifications, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

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

private val Icon_Notifications: ImageVector = materialIcon(name = "Outlined.Notifications") {
    materialPath {
        moveTo(12.0f, 22.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        horizontalLineToRelative(-4.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        close()
        moveTo(18.0f, 16.0f)
        verticalLineToRelative(-5.0f)
        curveToRelative(0.0f, -3.07f, -1.63f, -5.64f, -4.5f, -6.32f)
        lineTo(13.5f, 4.0f)
        curveToRelative(0.0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
        reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
        verticalLineToRelative(0.68f)
        curveTo(7.64f, 5.36f, 6.0f, 7.92f, 6.0f, 11.0f)
        verticalLineToRelative(5.0f)
        lineToRelative(-2.0f, 2.0f)
        verticalLineToRelative(1.0f)
        horizontalLineToRelative(16.0f)
        verticalLineToRelative(-1.0f)
        lineToRelative(-2.0f, -2.0f)
        close()
        moveTo(16.0f, 17.0f)
        lineTo(8.0f, 17.0f)
        verticalLineToRelative(-6.0f)
        curveToRelative(0.0f, -2.48f, 1.51f, -4.5f, 4.0f, -4.5f)
        reflectiveCurveToRelative(4.0f, 2.02f, 4.0f, 4.5f)
        verticalLineToRelative(6.0f)
        close()
    }
}