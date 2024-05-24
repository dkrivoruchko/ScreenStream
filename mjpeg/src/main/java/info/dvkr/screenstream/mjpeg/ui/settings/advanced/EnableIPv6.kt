package info.dvkr.screenstream.mjpeg.ui.settings.advanced

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

internal object EnableIPv6 : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.ENABLE_IPV6.name
    override val position: Int = 1
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_enable_ipv6).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_enable_ipv6_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val enableIPv6 = remember { derivedStateOf { mjpegSettingsState.value.enableIPv6 } }

        EnableIPv6UI(horizontalPadding, enableIPv6.value) {
            if (enableIPv6.value != it) {
                coroutineScope.launch { mjpegSettings.updateData { copy(enableIPv6 = it) } }
            }
        }
    }
}

@Composable
private fun EnableIPv6UI(
    horizontalPadding: Dp,
    enableIPv6: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = enableIPv6, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Ipv6, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_enable_ipv6),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_enable_ipv6_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = enableIPv6, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_Ipv6: ImageVector = materialIcon(name = "Ipv6") {
    materialPath {
        moveTo(1.1F, 16.833F)
        verticalLineTo(7.04F)
        horizontalLineToRelative(1.621F)
        verticalLineToRelative(9.793F)
        close()
    }
    materialPath {
        moveTo(4.282F, 16.833F)
        lineTo(4.282F, 7.04F)
        horizontalLineToRelative(2.613F)
        quadToRelative(1.46F, 0.0F, 1.909F, 0.14F)
        quadToRelative(0.717F, 0.227F, 1.18F, 0.969F)
        quadToRelative(0.468F, 0.741F, 0.468F, 1.91F)
        quadToRelative(0.0F, 1.063F, -0.402F, 1.784F)
        quadToRelative(-0.402F, 0.715F, -1.005F, 1.009F)
        quadToRelative(-0.603F, 0.287F, -2.077F, 0.287F)
        lineTo(5.903F, 13.139F)
        verticalLineToRelative(3.694F)
        moveTo(5.903F, 8.697F)
        verticalLineToRelative(2.779F)
        horizontalLineToRelative(0.898F)
        quadToRelative(0.904F, 0.0F, 1.226F, -0.127F)
        quadToRelative(0.328F, -0.127F, 0.536F, -0.455F)
        quadToRelative(0.208F, -0.334F, 0.208F, -0.815F)
        quadToRelative(0.0F, -0.487F, -0.215F, -0.821F)
        quadToRelative(-0.214F, -0.334F, -0.53F, -0.448F)
        quadToRelative(-0.314F, -0.113F, -1.332F, -0.113F)
        close()
    }
    materialPath {
        moveTo(13.38F, 16.833F)
        lineToRelative(-2.352F, -7.094F)
        horizontalLineToRelative(1.621F)
        lineToRelative(1.099F, 3.62F)
        lineToRelative(0.315F, 1.21F)
        lineToRelative(0.328F, -1.21F)
        lineToRelative(1.112F, -3.62F)
        horizontalLineToRelative(1.581F)
        lineToRelative(-2.318F, 7.094F)
        close()
    }
    materialPath {
        moveTo(22.946F, 9.438F)
        lineToRelative(-1.494F, 0.2F)
        quadToRelative(-0.107F, -1.095F, -0.878F, -1.095F)
        quadToRelative(-0.502F, 0.0F, -0.837F, 0.548F)
        quadToRelative(-0.328F, 0.548F, -0.415F, 2.21F)
        quadToRelative(0.288F, -0.413F, 0.643F, -0.62F)
        quadToRelative(0.355F, -0.207F, 0.784F, -0.207F)
        quadToRelative(0.944F, 0.0F, 1.648F, 0.881F)
        quadToRelative(0.703F, 0.875F, 0.703F, 2.332F)
        quadToRelative(0.0F, 1.55F, -0.744F, 2.431F)
        quadToRelative(-0.743F, 0.882F, -1.842F, 0.882F)
        quadToRelative(-1.206F, 0.0F, -2.003F, -1.142F)
        quadToRelative(-0.79F, -1.15F, -0.79F, -3.801F)
        quadToRelative(0.0F, -2.692F, 0.824F, -3.875F)
        quadTo(19.369F, 7.0F, 20.662F, 7.0F)
        quadToRelative(0.89F, 0.0F, 1.5F, 0.608F)
        quadToRelative(0.616F, 0.601F, 0.784F, 1.83F)
        moveTo(19.456F, 13.533F)
        quadToRelative(0.0F, 0.929F, 0.341F, 1.423F)
        quadToRelative(0.349F, 0.488F, 0.79F, 0.488F)
        quadToRelative(0.43F, 0.0F, 0.71F, -0.408F)
        quadToRelative(0.289F, -0.407F, 0.289F, -1.336F)
        quadToRelative(0.0F, -0.962F, -0.308F, -1.403F)
        quadToRelative(-0.308F, -0.44F, -0.764F, -0.44F)
        quadToRelative(-0.442F, 0.0F, -0.75F, 0.42F)
        quadToRelative(-0.308F, 0.421F, -0.308F, 1.256F)
        close()
    }
}