package io.screenstream.mjpeg.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.ui.settings.access.accessSettingSearchEntries
import io.screenstream.mjpeg.ui.settings.automation.automationSettingSearchEntries
import io.screenstream.mjpeg.ui.settings.connection.connectionSettingSearchEntries
import io.screenstream.mjpeg.ui.settings.images.imageSettingSearchEntries
import io.screenstream.mjpeg.ui.settings.webpage.webPageSettingSearchEntries

internal enum class SettingSearchGroup(@param:StringRes val titleRes: Int) {
    Image(R.string.mjpeg_pref_image_group),
    Automation(R.string.mjpeg_pref_automation_group),
    Connection(R.string.mjpeg_pref_connection_group),
    WebPage(R.string.mjpeg_pref_web_group),
    Access(R.string.mjpeg_pref_access_group),
}

internal data class SettingSearchInfo(
    val group: SettingSearchGroup,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    val extraTermResIds: List<Int> = emptyList(),
)

internal data class SettingSearchOptionText(
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
)

private object SettingSearchIndex {
    private val separators = Regex("[\\s\\p{Z}\\p{Pd}]+")

    fun normalize(text: String): String = text
        .lowercase()
        .replace('ё', 'е')
        .replace(separators, " ")
        .trim()

    fun match(index: Map<SettingSearchInfo, String>, tokens: List<String>): Set<SettingSearchInfo> = index
        .filterValues { text -> tokens.all { token -> token in text } }
        .keys
}

@Composable
internal fun rememberMatchingSettings(query: String): Set<SettingSearchInfo>? {
    val normalizedQuery = SettingSearchIndex.normalize(query)
    if (normalizedQuery.isEmpty()) return null

    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val index = remember(resources, configuration) {
        (imageSettingSearchEntries + automationSettingSearchEntries + connectionSettingSearchEntries + webPageSettingSearchEntries + accessSettingSearchEntries).associateWith { setting ->
            val termResIds = listOf(setting.group.titleRes, setting.titleRes, setting.summaryRes) + setting.extraTermResIds
            SettingSearchIndex.normalize(termResIds.joinToString(" ") { resources.getString(it) })
        }
    }
    return remember(index, normalizedQuery) { SettingSearchIndex.match(index, normalizedQuery.split(' ')) }
}

@Composable
internal fun SearchSettingsField(query: TextFieldState, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    val placeholder = stringResource(R.string.mjpeg_pref_search_placeholder)
    val containerColor = MaterialTheme.colorScheme.surface

    OutlinedTextField(
        state = query,
        modifier = modifier
            .padding(horizontal = 16.dp)
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = placeholder },
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(painterResource(R.drawable.mjpeg_icon_search), contentDescription = null) },
        trailingIcon = if (query.text.isNotEmpty()) {
            {
                IconButton(onClick = { query.clearText() }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(painterResource(R.drawable.mjpeg_icon_close), contentDescription = stringResource(R.string.mjpeg_pref_search_clear))
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = { focusManager.clearFocus() },
        lineLimits = TextFieldLineLimits.SingleLine,
        shape = SearchBarDefaults.inputFieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = containerColor,
            unfocusedContainerColor = containerColor,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    )
}
