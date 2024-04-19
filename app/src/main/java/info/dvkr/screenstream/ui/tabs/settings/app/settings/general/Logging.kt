package info.dvkr.screenstream.ui.tabs.settings.app.settings.general

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.logger.AppLogger
import kotlinx.coroutines.CoroutineScope

internal object Logging : ModuleSettings.Item {
    override val id: String = "LOGGING"
    override val position: Int = 4
    override val available: Boolean = AppLogger.isLoggingOn

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.app_pref_logging).contains(text, ignoreCase = true) ||
                getString(R.string.app_pref_logging_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ListUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) =
        LoggingUI(horizontalPadding)
}

@Composable
private fun LoggingUI(horizontalPadding: Dp) {
    val context = LocalContext.current
    val isLoggingOn = remember { mutableStateOf(AppLogger.isLoggingOn) }

    Row(
        modifier = Modifier
            .toggleable(
                value = isLoggingOn.value,
                onValueChange = { AppLogger.disableLogging(context) }
            )
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Article,
            contentDescription = stringResource(id = R.string.app_pref_logging),
            modifier = Modifier.padding(end = 16.dp)
        )

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.app_pref_logging),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.app_pref_logging_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(
            checked = isLoggingOn.value,
            onCheckedChange = null,
            modifier = Modifier.scale(0.7F),
        )
    }
}