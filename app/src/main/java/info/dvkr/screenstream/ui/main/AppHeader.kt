package info.dvkr.screenstream.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.R
import io.screenstream.core.module.StreamingModule

@Composable
internal fun AppHeader(
    streamingModuleDescription: StreamingModule.Description?,
    methodSelectionEnabled: Boolean,
    inlineMethodSelector: Boolean,
    onChooseMethod: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onRequestExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inlineMethodSelector) {
                StreamingMethodCard(
                    streamingModuleDescription = streamingModuleDescription,
                    onClick = onChooseMethod,
                    enabled = methodSelectionEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    inlineWithActions = true
                )
            } else {
                Text(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .weight(1f)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(painterResource(R.drawable.app_icon_settings), stringResource(R.string.app_settings_title))
            }
            IconButton(onClick = onOpenAbout) {
                Icon(painterResource(R.drawable.app_icon_info), stringResource(R.string.app_about_title))
            }
            IconButton(onClick = onRequestExit) {
                Icon(painterResource(R.drawable.app_icon_power_settings_new), stringResource(R.string.app_action_exit))
            }
        }

        if (!inlineMethodSelector) {
            StreamingMethodCard(
                streamingModuleDescription = streamingModuleDescription,
                onClick = onChooseMethod,
                enabled = methodSelectionEnabled,
            )
        }
    }
}

@Composable
private fun StreamingMethodCard(
    streamingModuleDescription: StreamingModule.Description?,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    inlineWithActions: Boolean = false,
) {
    val chooseLabel = stringResource(R.string.app_method_choose)

    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                if (inlineWithActions) heading()
                this.onClick(label = chooseLabel, action = null)
            },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(
                    horizontal = if (inlineWithActions) 8.dp else 16.dp,
                    vertical = 12.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val methodColor = if (enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
            if (streamingModuleDescription != null) {
                Icon(
                    painter = painterResource(streamingModuleDescription.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = methodColor,
                )
            }

            val supportingColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (inlineWithActions || streamingModuleDescription != null) {
                    Text(
                        text = stringResource(R.string.app_method_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = supportingColor,
                        maxLines = if (inlineWithActions) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = streamingModuleDescription?.let { stringResource(it.titleRes) } ?: chooseLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (streamingModuleDescription != null) methodColor else LocalContentColor.current,
                    maxLines = if (inlineWithActions) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(painterResource(R.drawable.app_icon_chevron), contentDescription = null, tint = supportingColor)
        }
    }
}

