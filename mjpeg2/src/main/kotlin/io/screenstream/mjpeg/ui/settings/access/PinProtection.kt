package io.screenstream.mjpeg.ui.settings.access

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingInfo
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSwitch
import io.screenstream.mjpeg.ui.settings.SettingsItem

internal object PinProtectionSearch {
    val policyLabelRes: Int = R.string.mjpeg_pref_access_policy_label
    val policyHelpRes: Int = R.string.mjpeg_pref_access_policy_help
    val policyLabelResIds: Map<AccessSettings.PinPolicy, Int> = mapOf(
        AccessSettings.PinPolicy.NewOnModuleStart to R.string.mjpeg_pref_access_policy_new_on_module_start,
        AccessSettings.PinPolicy.Permanent to R.string.mjpeg_pref_access_policy_permanent,
    )

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Access,
        titleRes = R.string.mjpeg_pref_access_protection,
        summaryRes = R.string.mjpeg_pref_access_protection_summary,
        extraTermResIds = listOf(policyLabelRes, policyHelpRes) + policyLabelResIds.values,
    )
}

@Composable
internal fun PinProtectionSettingItem(
    pinEnabled: Boolean,
    pinPolicy: AccessSettings.PinPolicy,
    canEditStreamSetup: Boolean,
    onAccessChange: (AccessSettings.() -> AccessSettings) -> Unit,
) {
    var showEditor by retain { mutableStateOf(false) }
    val openerFocusRequester = remember { FocusRequester() }
    var shouldRestoreFocus by remember { mutableStateOf(false) }
    LaunchedEffect(showEditor) {
        if (!showEditor && shouldRestoreFocus) {
            openerFocusRequester.requestFocus()
            shouldRestoreFocus = false
        }
    }
    SettingsItem(
        title = stringResource(PinProtectionSearch.info.titleRes),
        summary = stringResource(PinProtectionSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_pin,
        onClick = { showEditor = true },
        modifier = Modifier.focusRequester(openerFocusRequester),
        valueContent = {
            Text(
                stringResource(PinProtectionSearch.policyLabelResIds.getValue(pinPolicy)),
                color = if (canEditStreamSetup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            val switchInteractions = remember { MutableInteractionSource() }
            val enabledLabel = stringResource(PinProtectionSearch.info.titleRes)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.mjpeg_icon_chevron),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                VerticalDivider(Modifier.height(32.dp))
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .toggleable(
                            value = pinEnabled,
                            enabled = canEditStreamSetup,
                            role = Role.Switch,
                            interactionSource = switchInteractions,
                            indication = null,
                            onValueChange = { if (canEditStreamSetup) onAccessChange { copy(pinEnabled = !this.pinEnabled) } },
                        )
                        .semantics { contentDescription = enabledLabel },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    SettingSwitch(
                        checked = pinEnabled,
                        enabled = canEditStreamSetup,
                        interactionSource = switchInteractions,
                        compact = true,
                    )
                }
            }
        },
    )
    if (showEditor) {
        PinProtectionEditor(pinPolicy, canEditStreamSetup, onAccessChange) {
            showEditor = false
            shouldRestoreFocus = true
        }
    }
}

@Composable
private fun PinProtectionEditor(
    pinPolicy: AccessSettings.PinPolicy,
    canEditStreamSetup: Boolean,
    onAccessChange: (AccessSettings.() -> AccessSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialPinPolicy = retain { pinPolicy }
    var selectedPinPolicy by retain { mutableStateOf(initialPinPolicy) }

    SettingEditor(
        title = stringResource(PinProtectionSearch.info.titleRes),
        description = stringResource(PinProtectionSearch.info.summaryRes),
        onDismiss = {
            if (canEditStreamSetup && selectedPinPolicy != initialPinPolicy && selectedPinPolicy != pinPolicy) {
                val policy = selectedPinPolicy
                onAccessChange { copy(pinPolicy = policy) }
            }
            onDismiss()
        },
    ) {
        Text(
            stringResource(R.string.mjpeg_pref_access_protection_default),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(PinProtectionSearch.policyLabelRes), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PinProtectionSearch.policyLabelResIds.entries.forEachIndexed { index, (policy, labelRes) ->
                SegmentedButton(
                    selected = selectedPinPolicy == policy,
                    enabled = canEditStreamSetup,
                    onClick = { if (canEditStreamSetup) selectedPinPolicy = policy },
                    shape = SegmentedButtonDefaults.itemShape(index, PinProtectionSearch.policyLabelResIds.size),
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        SettingInfo(stringResource(PinProtectionSearch.policyHelpRes))
        if (!canEditStreamSetup) {
            Text(
                stringResource(R.string.mjpeg_pref_setup_locked),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
