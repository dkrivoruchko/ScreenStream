package info.dvkr.screenstream.mjpeg.ui.main.settings.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.ui.conditional

@Composable
internal fun SettingSwitchRow(
    enabled: Boolean,
    checked: Boolean,
    @DrawableRes iconRes: Int,
    title: String,
    summary: String,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onValueChange)
            .conditional(enabled.not()) { alpha(0.5F) }
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = title,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = summary,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.scale(0.7F), enabled = enabled)
    }
}

@Composable
internal fun SettingValueRow(
    enabled: Boolean,
    @DrawableRes iconRes: Int,
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    valueText: String? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .conditional(enabled.not()) { alpha(0.5F) }
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = title,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = summary,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        when {
            valueText != null -> Text(
                text = valueText,
                modifier = Modifier.defaultMinSize(minWidth = 52.dp),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            trailingContent != null -> trailingContent()
        }
    }
}

@Composable
internal fun SettingValueSwitchRow(
    enabled: Boolean,
    checked: Boolean,
    @DrawableRes iconRes: Int,
    title: String,
    summary: String,
    onRowClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .conditional(enabled.not()) { alpha(0.5F) }
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1F)
                .clickable(enabled = enabled, role = Role.Button, onClick = onRowClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

            Column(modifier = Modifier.weight(1F)) {
                Text(
                    text = title,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = summary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        VerticalDivider(
            modifier = Modifier
                .padding(vertical = 12.dp)
                .padding(start = 4.dp, end = 8.dp)
                .fillMaxHeight()
        )

        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.7F),
        )
    }
}

@Composable
internal fun SettingEditorLayout(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            content = content
        )
    }
}

@Composable
internal fun SelectionEditor(
    options: List<String>,
    selectedIndex: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    SettingEditorLayout(modifier = modifier) {
        if (description != null) {
            Text(
                text = description,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }

        Column(modifier = Modifier.selectableGroup()) {
            options.forEachIndexed { index, text ->
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = selectedIndex == index,
                            onClick = { onValueChange(index) },
                            role = Role.RadioButton
                        )
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedIndex == index, onClick = null)
                    Text(text = text, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}
