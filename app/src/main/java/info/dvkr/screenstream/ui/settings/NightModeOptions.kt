package info.dvkr.screenstream.ui.settings

import android.os.Build
import androidx.annotation.ArrayRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.R

@ArrayRes
private val nightModeOptionsRes =
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) R.array.app_pref_dark_theme_options_battery_saver
    else R.array.app_pref_dark_theme_options_follow_system

private val nightModes = listOf(
    AppCompatDelegate.MODE_NIGHT_YES,
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
    AppCompatDelegate.MODE_NIGHT_NO,
)

private fun nightModeOptionIndex(@AppCompatDelegate.NightMode nightMode: Int): Int {
    val index = nightModes.indexOf(nightMode)
    return if (index >= 0) index else 1
}

internal fun displayedNightMode(@AppCompatDelegate.NightMode nightMode: Int): Int =
    nightModes[nightModeOptionIndex(nightMode)]

@Composable
internal fun NightModeOptions(
    @AppCompatDelegate.NightMode nightMode: Int,
    menuExpanded: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNightModeSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = stringArrayResource(nightModeOptionsRes)
    val selectedIndex = nightModeOptionIndex(nightMode)
    ListItem(
        onClick = onOpenMenu,
        modifier = modifier.semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = { Icon(painterResource(R.drawable.app_icon_dark_mode), contentDescription = null) },
        supportingContent = { Text(stringResource(R.string.app_pref_dark_theme_summary), style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Box {
                Text(
                    labels[selectedIndex],
                    Modifier.widthIn(max = 128.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = onDismissMenu,
                    modifier = Modifier.selectableGroup(),
                    containerColor = Color.Transparent,
                    shadowElevation = 0.dp,
                ) {
                    val colors = MenuDefaults.selectableItemColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    nightModes.forEachIndexed { index, mode ->
                        SelectableDropdownMenuItem(
                            selected = index == selectedIndex,
                            onClick = { onNightModeSelect(mode) },
                            text = { Text(labels[index], fontWeight = FontWeight.Normal) },
                            modifier = Modifier.padding(vertical = 2.dp),
                            shapes = MenuDefaults.itemShape(index, nightModes.size),
                            colors = colors,
                        )
                    }
                }
            }
        },
    ) {
        Text(stringResource(R.string.app_pref_dark_theme), style = MaterialTheme.typography.titleMedium)
    }
}
