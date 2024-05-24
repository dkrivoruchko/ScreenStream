package info.dvkr.screenstream.mjpeg.ui.settings.image

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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

internal object VrMode : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.VR_MODE.name
    override val position: Int = 0
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_vr_mode).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_vr_mode).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_vr_mode_summary).contains(text, ignoreCase = true) ||
                getStringArray(R.array.mjpeg_pref_vr_mode_options).any { it.contains(text, ignoreCase = true) }
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val vrMode = remember { derivedStateOf { mjpegSettingsState.value.vrMode } }

        VrModeUI(horizontalPadding, vrMode.value, onDetailShow) {
            coroutineScope.launch { mjpegSettings.updateData { copy(vrMode = it) } }
        }
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val vrMode = remember { derivedStateOf { mjpegSettingsState.value.vrMode } }

        val vrModeOptions = stringArrayResource(id = R.array.mjpeg_pref_vr_mode_options)

        val scope = rememberCoroutineScope()

        VrModeDetailUI(headerContent, vrModeOptions, vrMode.value) {
            if (vrMode.value != it) {
                scope.launch { mjpegSettings.updateData { copy(vrMode = it) } }
            }
        }
    }
}

@Composable
private fun VrModeUI(
    horizontalPadding: Dp,
    vrMode: Int,
    onDetailShow: () -> Unit,
    onVrModeChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .toggleable(value = vrMode > MjpegSettings.Default.VR_MODE_DISABLE, onValueChange = { onDetailShow.invoke() })
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_VirtualReality, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_vr_mode),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_vr_mode_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        VerticalDivider(modifier = Modifier.padding(vertical = 12.dp).padding(start = 4.dp, end = 8.dp).fillMaxHeight())

        Switch(
            checked = vrMode > MjpegSettings.Default.VR_MODE_DISABLE,
            onCheckedChange = {
                if (it && vrMode == MjpegSettings.Default.VR_MODE_DISABLE)
                    onDetailShow.invoke()
                else {
                    onVrModeChange.invoke(-vrMode)
                }
            },
            modifier = Modifier.scale(0.7F),
        )
    }
}

@Composable
private fun VrModeDetailUI(
    headerContent: @Composable (String) -> Unit,
    vrModeOptions: Array<String>,
    vrMode: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_vr_mode))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .selectableGroup()
                .verticalScroll(rememberScrollState())
        ) {
            vrModeOptions.forEachIndexed { index, text ->
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = if (vrMode > 0) vrMode == index else index == 0,
                            onClick = { onValueChange.invoke(index) },
                            role = Role.RadioButton
                        )
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = if (vrMode > 0) vrMode == index else index == 0, onClick = null)

                    Text(text = text, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

private val Icon_VirtualReality: ImageVector = materialIcon(name = "VirtualReality") {
    materialPath {
        verticalLineToRelative(0.0F)
        moveTo(5.0F, 3.0F)
        curveTo(3.89F, 3.0F, 3.0F, 3.9F, 3.0F, 5.0F)
        verticalLineTo(19.0F)
        arcTo(2.0F, 2.0F, 0.0F, false, false, 5.0F, 21.0F)
        horizontalLineTo(19.0F)
        arcTo(2.0F, 2.0F, 0.0F, false, false, 21.0F, 19.0F)
        verticalLineTo(5.0F)
        arcTo(2.0F, 2.0F, 0.0F, false, false, 19.0F, 3.0F)
        horizontalLineTo(5.0F)
        moveTo(6.0F, 9.0F)
        horizontalLineTo(7.5F)
        lineTo(8.5F, 12.43F)
        lineTo(9.5F, 9.0F)
        horizontalLineTo(11.0F)
        lineTo(9.25F, 15.0F)
        horizontalLineTo(7.75F)
        lineTo(6.0F, 9.0F)
        moveTo(13.0F, 9.0F)
        horizontalLineTo(16.5F)
        curveTo(17.35F, 9.0F, 18.0F, 9.65F, 18.0F, 10.5F)
        verticalLineTo(11.5F)
        curveTo(18.0F, 12.1F, 17.6F, 12.65F, 17.1F, 12.9F)
        lineTo(18.0F, 15.0F)
        horizontalLineTo(16.5F)
        lineTo(15.65F, 13.0F)
        horizontalLineTo(14.5F)
        verticalLineTo(15.0F)
        horizontalLineTo(13.0F)
        verticalLineTo(9.0F)
        moveTo(14.5F, 10.5F)
        verticalLineTo(11.5F)
        horizontalLineTo(16.5F)
        verticalLineTo(10.5F)
        horizontalLineTo(14.5F)
        close()
    }
}