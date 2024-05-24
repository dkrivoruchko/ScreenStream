package info.dvkr.screenstream.mjpeg.ui.settings.security

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.common.ui.conditional
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal object BlockAddress : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.BLOCK_ADDRESS.name
    override val position: Int = 5
    override val available: Boolean = true
    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_block_address).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_block_address_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val blockAddress = remember { derivedStateOf { mjpegSettingsState.value.blockAddress } }
        val enablePin = remember { derivedStateOf { mjpegSettingsState.value.enablePin } }

        BlockAddressUI(horizontalPadding, blockAddress.value, enablePin.value) {
            if (blockAddress.value != it) {
                coroutineScope.launch { mjpegSettings.updateData { copy(blockAddress = it) } }
            }
        }
    }
}

@Composable
private fun BlockAddressUI(
    horizontalPadding: Dp,
    blockAddress: Boolean,
    enablePin: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = blockAddress, enabled = enablePin, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp)
            .conditional(enablePin.not()) { alpha(0.5F) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Block, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_block_address),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_block_address_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = blockAddress, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_Block: ImageVector = materialIcon(name = "Filled.Block") {
    materialPath {
        moveTo(12.0f, 2.0f)
        curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
        reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
        reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
        reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
        close()
        moveTo(4.0f, 12.0f)
        curveToRelative(0.0f, -4.42f, 3.58f, -8.0f, 8.0f, -8.0f)
        curveToRelative(1.85f, 0.0f, 3.55f, 0.63f, 4.9f, 1.69f)
        lineTo(5.69f, 16.9f)
        curveTo(4.63f, 15.55f, 4.0f, 13.85f, 4.0f, 12.0f)
        close()
        moveTo(12.0f, 20.0f)
        curveToRelative(-1.85f, 0.0f, -3.55f, -0.63f, -4.9f, -1.69f)
        lineTo(18.31f, 7.1f)
        curveTo(19.37f, 8.45f, 20.0f, 10.15f, 20.0f, 12.0f)
        curveToRelative(0.0f, 4.42f, -3.58f, 8.0f, -8.0f, 8.0f)
        close()
    }
}