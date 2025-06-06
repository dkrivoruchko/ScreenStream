package info.dvkr.screenstream.mjpeg.ui.settings.advanced

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
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

internal object AddressFilter : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.ADDRESS_FILTER.name
    override val position: Int = 1
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_address_filter).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_address_filter_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val state = mjpegSettings.data.collectAsStateWithLifecycle()

        AddressFilterUI(horizontalPadding, state.value.addressFilter, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val state = mjpegSettings.data.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        AddressFilterDetailUI(headerContent, state.value.addressFilter) {
            if (state.value.addressFilter != it) {
                scope.launch { mjpegSettings.updateData { copy(addressFilter = it) } }
            }
        }
    }
}

@Composable
private fun AddressFilterUI(horizontalPadding: Dp, addressFilter: Int, onDetailShow: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onDetailShow)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Address, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_address_filter),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_address_filter_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun AddressFilterDetailUI(
    headerContent: @Composable (String) -> Unit,
    addressFilter: Int,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_address_filter))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .toggleable(value = addressFilter == 0, onValueChange = { if (it) onValueChange(0) })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.mjpeg_pref_filter_no_restriction),
                    modifier = Modifier.weight(1F)
                )
                Switch(checked = addressFilter == 0, onCheckedChange = null, modifier = Modifier.scale(0.7F))
            }
            val enabled = addressFilter != 0
            FilterCheckBox(
                text = stringResource(id = R.string.mjpeg_pref_filter_private),
                checked = addressFilter and MjpegSettings.Values.ADDRESS_PRIVATE != 0,
                enabled = enabled
            ) { checked ->
                var value = addressFilter
                value = if (checked) value or MjpegSettings.Values.ADDRESS_PRIVATE else value and MjpegSettings.Values.ADDRESS_PRIVATE.inv()
                onValueChange(value)
            }
            FilterCheckBox(
                text = stringResource(id = R.string.mjpeg_pref_filter_localhost),
                checked = addressFilter and MjpegSettings.Values.ADDRESS_LOCALHOST != 0,
                enabled = enabled
            ) { checked ->
                var value = addressFilter
                value = if (checked) value or MjpegSettings.Values.ADDRESS_LOCALHOST else value and MjpegSettings.Values.ADDRESS_LOCALHOST.inv()
                onValueChange(value)
            }
            FilterCheckBox(
                text = stringResource(id = R.string.mjpeg_pref_filter_public),
                checked = addressFilter and MjpegSettings.Values.ADDRESS_PUBLIC != 0,
                enabled = enabled
            ) { checked ->
                var value = addressFilter
                value = if (checked) value or MjpegSettings.Values.ADDRESS_PUBLIC else value and MjpegSettings.Values.ADDRESS_PUBLIC.inv()
                onValueChange(value)
            }
        }
    }
}

@Composable
private fun FilterCheckBox(text: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp)
            .conditional(enabled.not()) { alpha(0.5F) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text = text, modifier = Modifier.padding(start = 8.dp))
    }
}

private val Icon_Address: ImageVector = materialIcon(name = "Address") {
    materialPath { moveTo(12f,2f); lineTo(12f,22f) }
}
