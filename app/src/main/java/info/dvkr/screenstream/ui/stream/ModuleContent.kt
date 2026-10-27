package info.dvkr.screenstream.ui.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.R
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.module.StreamingModule

@Composable
internal fun ColumnScope.ModuleContent(
    state: StreamingModuleManager.State,
    onRestart: (StreamingModule.InstanceId) -> Unit,
    instanceContent: @Composable (StreamingModule.InstanceId, Modifier, @Composable () -> Unit) -> Unit,
) {
    when (state) {
        is StreamingModuleManager.State.Running -> key(state.instanceId) {
            if (state.status.errorMessage != null) {
                ModuleRecoveryMessage(
                    detail = state.status.errorMessage,
                    onRestart = { onRestart(state.instanceId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
            }
            instanceContent(state.instanceId, Modifier.fillMaxSize()) {
                ModuleError(onRestart = { onRestart(state.instanceId) })
            }
        }

        is StreamingModuleManager.State.Failed -> key(state.instanceId) {
            ModuleError(
                onRestart = { onRestart(state.instanceId) },
            )
        }

        is StreamingModuleManager.State.Unresponsive -> key(state.instanceId) {
            ModuleRecoveryMessage(
                onRestart = { onRestart(state.instanceId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )
            instanceContent(state.instanceId, Modifier.fillMaxSize()) {
                ModuleError(onRestart = { onRestart(state.instanceId) })
            }
        }

        StreamingModuleManager.State.Switching -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1F),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(R.string.app_tab_stream_switching_modules))
        }

        StreamingModuleManager.State.NoModule, StreamingModuleManager.State.Exiting -> Unit
    }
}

@Composable
private fun ModuleRecoveryMessage(
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val title = stringResource(R.string.app_module_error_title)
    val message = detail?.takeUnless { it.isBlank() || it == title }

    ElevatedCard(modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.app_icon_error),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(
                onClick = onRestart,
                modifier = Modifier.widthIn(max = 144.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(text = stringResource(R.string.app_module_error_restart))
            }
        }
    }
}

@Composable
private fun ModuleError(
    onRestart: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            painter = painterResource(R.drawable.app_icon_error),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.app_module_error_title),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRestart, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(text = stringResource(R.string.app_module_error_restart))
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
