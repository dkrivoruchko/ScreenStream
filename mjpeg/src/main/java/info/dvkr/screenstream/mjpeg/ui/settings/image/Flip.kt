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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
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

internal object Flip : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.FLIP.name
    override val position: Int = 5
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_flip).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_flip_summary).contains(text, ignoreCase = true) ||
                getStringArray(R.array.mjpeg_pref_flip_options).any { it.contains(text, ignoreCase = true) }
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()

        FlipUI(horizontalPadding, mjpegSettingsState.value.flip, onDetailShow) {
            coroutineScope.launch { mjpegSettings.updateData { copy(flip = it) } }
        }
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val flipOptions = stringArrayResource(id = R.array.mjpeg_pref_flip_options)
        val scope = rememberCoroutineScope()

        FlipDetailUI(headerContent, flipOptions, mjpegSettingsState.value.flip) {
            if (mjpegSettingsState.value.flip != it) {
                scope.launch { mjpegSettings.updateData { copy(flip = it) } }
            }
        }
    }
}

@Composable
private fun FlipUI(
    horizontalPadding: Dp,
    flipMode: Int,
    onDetailShow: () -> Unit,
    onFlipModeChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .toggleable(value = flipMode > MjpegSettings.Values.FLIP_NONE, onValueChange = { onDetailShow.invoke() })
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(R.drawable.flip_24px), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_flip),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_flip_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        VerticalDivider(
            modifier = Modifier
                .padding(vertical = 12.dp)
                .padding(start = 4.dp, end = 8.dp)
                .fillMaxHeight()
        )

        Switch(
            checked = flipMode > MjpegSettings.Values.FLIP_NONE,
            onCheckedChange = {
                if (it && flipMode == MjpegSettings.Values.FLIP_NONE)
                    onDetailShow.invoke()
                else {
                    onFlipModeChange.invoke(-flipMode)
                }
            },
            modifier = Modifier.scale(0.7F),
        )
    }
}

@Composable
private fun FlipDetailUI(
    headerContent: @Composable (String) -> Unit,
    flipOptions: Array<String>,
    flipMode: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_flip))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .selectableGroup()
                .verticalScroll(rememberScrollState())
        ) {
            flipOptions.forEachIndexed { index, text ->
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = if (flipMode > 0) flipMode == index else index == 0,
                            onClick = { onValueChange.invoke(index) },
                            role = Role.RadioButton
                        )
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = if (flipMode > 0) flipMode == index else index == 0, onClick = null)

                    Text(text = text, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}
