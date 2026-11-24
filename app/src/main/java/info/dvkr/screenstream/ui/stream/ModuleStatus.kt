package info.dvkr.screenstream.ui.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.R
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule

internal val StreamingModuleManager.State.instanceIdOrNull: StreamingModule.InstanceId?
    get() = when (this) {
        is StreamingModuleManager.State.Running -> instanceId
        is StreamingModuleManager.State.Unresponsive -> instanceId
        is StreamingModuleManager.State.Failed -> instanceId
        StreamingModuleManager.State.NoModule,
        StreamingModuleManager.State.Switching,
        StreamingModuleManager.State.Exiting -> null
    }

@Composable
internal fun ModuleRecoveryCard(
    onRestart: () -> Unit,
    errorDetail: String?,
) {
    val title = stringResource(R.string.app_module_error_title)
    val message = errorDetail?.takeUnless { it.isBlank() || it == title }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
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
internal fun ModuleUnavailableContent(
    onRestart: () -> Unit,
    message: String?,
    modifier: Modifier,
) {
    ElevatedCard(
        modifier = modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(
                painter = painterResource(R.drawable.app_icon_error),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message ?: stringResource(R.string.app_module_error_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRestart) { Text(text = stringResource(R.string.app_module_error_restart)) }
        }
    }
}
