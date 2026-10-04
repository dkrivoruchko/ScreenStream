package io.screenstream.mjpeg.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.networkaddress.AddressCategory
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import io.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Edits the live address selection using the controller's shared policy. Each transaction preserves
 * other groups and a nonempty selection; setup restrictions do not disable these live controls.
 */
@Composable
internal fun MjpegNetworkFilters(settings: MjpegSettings, editPolicy: MjpegSettings.EditPolicy) {
    var loadFailed by remember(settings) { mutableStateOf(false) }
    val dataFlow = remember(settings) {
        settings.data.onEach { loadFailed = false }.catch { failure ->
            if (failure is CancellationException) throw failure
            loadFailed = true
        }
    }
    val data by dataFlow.collectAsStateWithLifecycle(initialValue = null)
    var saving by remember(settings) { mutableStateOf(false) }
    var saveFailed by remember(settings) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun updateFilter(transform: (NetworkAddressFilter) -> NetworkAddressFilter) {
        if (saving || loadFailed || data == null) return
        saving = true
        saveFailed = false
        scope.launch {
            try {
                settings.updateData {
                    val filter = transform(network.filter)
                    val proposed = if (filter == network.filter) this else copy(network = network.copy(filter = filter))
                    if (editPolicy.allows(this, proposed)) proposed else this
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                saveFailed = true
            } finally {
                saving = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.mjpeg_network_filter), style = MaterialTheme.typography.titleMedium)
        if (loadFailed) {
            Text(stringResource(R.string.mjpeg_network_filter_load_error), color = MaterialTheme.colorScheme.error)
        }
        val filter = data?.network?.filter
        if (filter == null) {
            if (!loadFailed) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else {
            NetworkFilterGroup(
                title = R.string.mjpeg_network_filter_ip_version,
                options = listOf(
                    AddressFamily.Ipv4 to R.string.mjpeg_network_filter_ipv4,
                    AddressFamily.Ipv6 to R.string.mjpeg_network_filter_ipv6,
                ),
                selected = filter.families,
                enabled = !saving && !loadFailed,
                onToggle = { family ->
                    updateFilter { current ->
                        NetworkAddressFilter(
                            families = toggleChoice(current.families, family),
                            interfaceTypes = current.interfaceTypes,
                            categories = current.categories,
                        )
                    }
                },
            )
            NetworkFilterGroup(
                title = R.string.mjpeg_network_filter_network_type,
                options = listOf(
                    InterfaceType.Wifi to R.string.mjpeg_network_filter_wifi,
                    InterfaceType.Ethernet to R.string.mjpeg_network_filter_ethernet,
                    InterfaceType.Mobile to R.string.mjpeg_network_filter_mobile,
                    InterfaceType.Vpn to R.string.mjpeg_network_filter_vpn,
                    InterfaceType.Other to R.string.mjpeg_network_filter_other,
                ),
                selected = filter.interfaceTypes,
                enabled = !saving && !loadFailed,
                onToggle = { type ->
                    updateFilter { current ->
                        NetworkAddressFilter(
                            families = current.families,
                            interfaceTypes = toggleChoice(current.interfaceTypes, type),
                            categories = current.categories,
                        )
                    }
                },
            )
            NetworkFilterGroup(
                title = R.string.mjpeg_network_filter_address_type,
                options = listOf(
                    AddressCategory.Private to R.string.mjpeg_network_filter_private,
                    AddressCategory.NonPrivate to R.string.mjpeg_network_filter_non_private,
                    AddressCategory.Loopback to R.string.mjpeg_network_filter_loopback,
                    AddressCategory.LinkLocal to R.string.mjpeg_network_filter_link_local,
                ),
                selected = filter.categories,
                enabled = !saving && !loadFailed,
                onToggle = { category ->
                    updateFilter { current ->
                        NetworkAddressFilter(
                            families = current.families,
                            interfaceTypes = current.interfaceTypes,
                            categories = toggleChoice(current.categories, category),
                        )
                    }
                },
            )
            Text(stringResource(R.string.mjpeg_network_filter_keep_one), style = MaterialTheme.typography.bodySmall)
            if (saveFailed) {
                Text(stringResource(R.string.mjpeg_network_filter_save_error), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun <T> NetworkFilterGroup(
    @StringRes title: Int,
    options: List<Pair<T, Int>>,
    selected: Set<T>,
    enabled: Boolean,
    onToggle: (T) -> Unit,
) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (choice, label) ->
                val checked = choice in selected
                FilterChip(
                    selected = checked,
                    onClick = { onToggle(choice) },
                    enabled = enabled && (!checked || selected.size > 1),
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
}

/** Rechecks the latest saved group so concurrent preference changes cannot make it empty. */
private fun <T> toggleChoice(selected: Set<T>, choice: T): Set<T> = when {
    choice !in selected -> selected + choice
    selected.size > 1 -> selected - choice
    else -> selected
}
