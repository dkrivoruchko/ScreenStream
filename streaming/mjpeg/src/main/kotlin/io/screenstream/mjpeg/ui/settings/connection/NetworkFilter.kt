package io.screenstream.mjpeg.ui.settings.connection

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.networkaddress.AddressCategory
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import io.screenstream.mjpeg.settings.NetworkSettings
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingInfo
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus

internal object NetworkFilterSearch {
    val ipVersionRes: Int = R.string.mjpeg_pref_connection_filter_ip_version
    val networkTypeRes: Int = R.string.mjpeg_pref_connection_filter_network_type
    val addressTypeRes: Int = R.string.mjpeg_pref_connection_filter_address_type
    val familyLabelResIds: Map<AddressFamily, Int> = mapOf(
        AddressFamily.Ipv4 to R.string.mjpeg_network_ipv4,
        AddressFamily.Ipv6 to R.string.mjpeg_network_ipv6,
    )
    val interfaceLabelResIds: Map<InterfaceType, Int> = mapOf(
        InterfaceType.Wifi to R.string.mjpeg_network_wifi,
        InterfaceType.Ethernet to R.string.mjpeg_network_ethernet,
        InterfaceType.Mobile to R.string.mjpeg_network_mobile,
        InterfaceType.Vpn to R.string.mjpeg_network_vpn,
        InterfaceType.Other to R.string.mjpeg_network_other,
    )
    val categoryLabelResIds: Map<AddressCategory, Int> = mapOf(
        AddressCategory.Private to R.string.mjpeg_pref_connection_filter_private,
        AddressCategory.NonPrivate to R.string.mjpeg_pref_connection_filter_non_private,
        AddressCategory.Loopback to R.string.mjpeg_network_loopback,
        AddressCategory.LinkLocal to R.string.mjpeg_network_ipv4_link_local,
    )

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Connection,
        titleRes = R.string.mjpeg_pref_connection_filter,
        summaryRes = R.string.mjpeg_pref_connection_filter_summary,
        extraTermResIds = listOf(ipVersionRes, networkTypeRes, addressTypeRes) +
                familyLabelResIds.values + interfaceLabelResIds.values + categoryLabelResIds.values,
    )
}

@Composable
internal fun NetworkFilterSettingItem(
    filter: NetworkAddressFilter,
    onNetworkChange: (NetworkSettings.() -> NetworkSettings) -> Unit,
) {
    var showEditor by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor)
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val familySummary = remember(filter.families, resources, configuration) {
        NetworkFilterSearch.familyLabelResIds.filterKeys { it in filter.families }.values.joinToString { resources.getString(it) }
    }
    val interfaceSummary = remember(filter.interfaceTypes, resources, configuration) {
        NetworkFilterSearch.interfaceLabelResIds.filterKeys { it in filter.interfaceTypes }.values.joinToString { resources.getString(it) }
    }
    val categorySummary = remember(filter.categories, resources, configuration) {
        NetworkFilterSearch.categoryLabelResIds.filterKeys { it in filter.categories }.values.joinToString { resources.getString(it) }
    }
    SettingsItem(
        title = stringResource(NetworkFilterSearch.info.titleRes),
        summary = stringResource(NetworkFilterSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_filter_alt,
        onClick = { showEditor = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        valueContent = {
            Text(
                "$familySummary · $interfaceSummary · $categorySummary",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Icon(painterResource(R.drawable.mjpeg_icon_chevron), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
    )
    if (showEditor) {
        NetworkFilterEditor(filter, onNetworkChange) {
            showEditor = false
            openerFocus.markDismissed()
        }
    }
}

@Composable
private fun NetworkFilterEditor(
    filter: NetworkAddressFilter,
    onNetworkChange: (NetworkSettings.() -> NetworkSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val initialFilter = retain { filter }
    var draftFilter by retain { mutableStateOf(initialFilter) }
    SettingEditor(
        title = stringResource(NetworkFilterSearch.info.titleRes),
        description = stringResource(NetworkFilterSearch.info.summaryRes),
        onDismiss = {
            val nextFilter = draftFilter
            if (nextFilter != initialFilter) onNetworkChange { copy(filter = nextFilter) }
            onDismiss()
        },
    ) {
        Text(
            stringResource(
                R.string.mjpeg_pref_connection_filter_default,
                NetworkFilterSearch.familyLabelResIds.filterKeys { it in NetworkSettings.DEFAULT_FILTER.families }
                    .values.joinToString { resources.getString(it) },
                NetworkFilterSearch.interfaceLabelResIds.filterKeys { it in NetworkSettings.DEFAULT_FILTER.interfaceTypes }
                    .values.joinToString { resources.getString(it) },
                NetworkFilterSearch.categoryLabelResIds.filterKeys { it in NetworkSettings.DEFAULT_FILTER.categories }
                    .values.joinToString { resources.getString(it) },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.mjpeg_pref_connection_filter_selection_help), style = MaterialTheme.typography.bodyMedium)
        NetworkFilterGroups(draftFilter) { transform -> draftFilter = draftFilter.transform() }
        SettingInfo(stringResource(R.string.mjpeg_pref_connection_filter_help))
    }
}

@Composable
private fun NetworkFilterGroups(
    filter: NetworkAddressFilter,
    onFilterChange: (NetworkAddressFilter.() -> NetworkAddressFilter) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NetworkFilterOptionGroup(
            titleRes = NetworkFilterSearch.ipVersionRes,
            optionLabelResIds = NetworkFilterSearch.familyLabelResIds,
            selectedOptions = filter.families,
            onOptionToggle = { family ->
                onFilterChange {
                    NetworkAddressFilter(
                        families = toggleFilterOption(families, family),
                        interfaceTypes = interfaceTypes,
                        categories = categories,
                    )
                }
            },
        )
        NetworkFilterOptionGroup(
            titleRes = NetworkFilterSearch.networkTypeRes,
            optionLabelResIds = NetworkFilterSearch.interfaceLabelResIds,
            selectedOptions = filter.interfaceTypes,
            onOptionToggle = { type ->
                onFilterChange {
                    NetworkAddressFilter(
                        families = families,
                        interfaceTypes = toggleFilterOption(interfaceTypes, type),
                        categories = categories,
                    )
                }
            },
        )
        NetworkFilterOptionGroup(
            titleRes = NetworkFilterSearch.addressTypeRes,
            optionLabelResIds = NetworkFilterSearch.categoryLabelResIds,
            selectedOptions = filter.categories,
            onOptionToggle = { category ->
                onFilterChange {
                    NetworkAddressFilter(
                        families = families,
                        interfaceTypes = interfaceTypes,
                        categories = toggleFilterOption(categories, category),
                    )
                }
            },
        )
    }
}

@Composable
private fun <T> NetworkFilterOptionGroup(
    @StringRes titleRes: Int,
    optionLabelResIds: Map<T, Int>,
    selectedOptions: Set<T>,
    onOptionToggle: (T) -> Unit,
) {
    Column {
        Text(stringResource(titleRes), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((option, labelRes) in optionLabelResIds) {
                val isSelected = option in selectedOptions
                val isOnlySelectedOption = isSelected && selectedOptions.size == 1
                FilterChip(
                    selected = isSelected,
                    onClick = { onOptionToggle(option) },
                    enabled = !isOnlySelectedOption,
                    label = { Text(stringResource(labelRes)) },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                painterResource(R.drawable.mjpeg_icon_check),
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                    colors = if (isOnlySelectedOption) {
                        FilterChipDefaults.filterChipColors(
                            disabledSelectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            disabledLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            disabledLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else {
                        FilterChipDefaults.filterChipColors()
                    },
                )
            }
        }
    }
}

/** Rechecks the current draft group so rapid edits cannot make it empty. */
private fun <T> toggleFilterOption(selectedOptions: Set<T>, option: T): Set<T> = when {
    option !in selectedOptions -> selectedOptions + option
    selectedOptions.size > 1 -> selectedOptions - option
    else -> selectedOptions
}
