package info.dvkr.screenstream.mjpeg.ui.settings.image

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
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

internal object Rotation : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.ROTATION.name
    override val position: Int = 4
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_rotate).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_rotate_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()

        RotationUI(horizontalPadding, mjpegSettingsState.value.rotation, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val rotationIndex = rotationList.first { it.second == mjpegSettingsState.value.rotation }.first
        val rotationOptions = stringArrayResource(id = R.array.mjpeg_pref_rotate_options)
        val scope = rememberCoroutineScope()

        RotationDetailUI(headerContent, rotationOptions, rotationIndex) { index ->
            val newRotation = rotationList[index].second
            if (mjpegSettingsState.value.rotation != newRotation) {
                scope.launch { mjpegSettings.updateData { copy(rotation = newRotation) } }
            }
        }
    }

    private val rotationList = listOf(
        0 to MjpegSettings.Values.ROTATION_0,
        1 to MjpegSettings.Values.ROTATION_90,
        2 to MjpegSettings.Values.ROTATION_180,
        3 to MjpegSettings.Values.ROTATION_270
    )
}

@Composable
private fun RotationUI(
    horizontalPadding: Dp,
    rotation: Int,
    onDetailShow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onDetailShow)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.rotate_90_degrees_cw_24px),
            contentDescription = null,
            modifier = Modifier.padding(end = 16.dp)
        )

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_rotate),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_rotate_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = stringResource(id = R.string.mjpeg_pref_rotate_value, rotation),
            modifier = Modifier.defaultMinSize(minWidth = 52.dp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun RotationDetailUI(
    headerContent: @Composable (String) -> Unit,
    rotationOptions: Array<String>,
    rotationIndex: Int,
    onNewOptionIndex: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_rotate))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .selectableGroup()
                .verticalScroll(rememberScrollState())
        ) {
            rotationOptions.forEachIndexed { index, text ->
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = rotationIndex == index,
                            onClick = { onNewOptionIndex.invoke(index) },
                            role = Role.RadioButton
                        )
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = rotationIndex == index, onClick = null)

                    Text(text = text, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}
