package info.dvkr.screenstream.mjpeg.ui.settings.image

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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal object Grayscale : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.IMAGE_GRAYSCALE.name
    override val position: Int = 2
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_grayscale).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_grayscale_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val imageGrayscale = remember { derivedStateOf { mjpegSettingsState.value.imageGrayscale } }

        GrayscaleUI(horizontalPadding, imageGrayscale.value) {
            if (imageGrayscale.value != it) {
                coroutineScope.launch { mjpegSettings.updateData { copy(imageGrayscale = it) } }
            }
        }
    }
}

@Composable
private fun GrayscaleUI(
    horizontalPadding: Dp,
    imageGrayscale: Boolean,
    onValueChange: (Boolean) -> Unit
) {


    Row(
        modifier = Modifier
            .toggleable(value = imageGrayscale, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_FilterBAndW, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_grayscale),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_grayscale_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = imageGrayscale, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_FilterBAndW: ImageVector = materialIcon(name = "Outlined.FilterBAndW") {
    materialPath {
        moveTo(19.0f, 3.0f)
        lineTo(5.0f, 3.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(14.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        horizontalLineToRelative(14.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        lineTo(21.0f, 5.0f)
        curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
        close()
        moveTo(19.0f, 19.0f)
        lineToRelative(-7.0f, -8.0f)
        verticalLineToRelative(8.0f)
        lineTo(5.0f, 19.0f)
        lineToRelative(7.0f, -8.0f)
        lineTo(12.0f, 5.0f)
        horizontalLineToRelative(7.0f)
        verticalLineToRelative(14.0f)
        close()
    }
}