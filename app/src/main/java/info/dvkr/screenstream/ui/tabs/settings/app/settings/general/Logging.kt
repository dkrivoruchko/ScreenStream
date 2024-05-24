package info.dvkr.screenstream.ui.tabs.settings.app.settings.general

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val context = LocalContext.current
        val isLoggingOn = remember { mutableStateOf(AppLogger.isLoggingOn) }

        LoggingUI(horizontalPadding, isLoggingOn.value) {
            AppLogger.disableLogging(context)
        }
    }
}

@Composable
private fun LoggingUI(
    horizontalPadding: Dp,
    isLoggingOn: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = isLoggingOn, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Article, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

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

        Switch(checked = isLoggingOn, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_Article: ImageVector = materialIcon(name = "AutoMirrored.Outlined.Article", autoMirror = true) {
    materialPath {
        moveTo(19.0f, 5.0f)
        verticalLineToRelative(14.0f)
        horizontalLineTo(5.0f)
        verticalLineTo(5.0f)
        horizontalLineTo(19.0f)
        moveTo(19.0f, 3.0f)
        horizontalLineTo(5.0f)
        curveTo(3.9f, 3.0f, 3.0f, 3.9f, 3.0f, 5.0f)
        verticalLineToRelative(14.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        horizontalLineToRelative(14.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        verticalLineTo(5.0f)
        curveTo(21.0f, 3.9f, 20.1f, 3.0f, 19.0f, 3.0f)
        lineTo(19.0f, 3.0f)
        close()
    }
    materialPath {
        moveTo(14.0f, 17.0f)
        horizontalLineTo(7.0f)
        verticalLineToRelative(-2.0f)
        horizontalLineToRelative(7.0f)
        verticalLineTo(17.0f)
        close()
        moveTo(17.0f, 13.0f)
        horizontalLineTo(7.0f)
        verticalLineToRelative(-2.0f)
        horizontalLineToRelative(10.0f)
        verticalLineTo(13.0f)
        close()
        moveTo(17.0f, 9.0f)
        horizontalLineTo(7.0f)
        verticalLineTo(7.0f)
        horizontalLineToRelative(10.0f)
        verticalLineTo(9.0f)
        close()
    }
}