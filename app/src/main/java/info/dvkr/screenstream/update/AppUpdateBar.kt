package info.dvkr.screenstream.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.AppDialog
import info.dvkr.screenstream.ui.ModalPane
import info.dvkr.screenstream.update.AppUpdate.State

@Composable
internal fun AppUpdateBar(appUpdate: AppUpdate, modalPane: ModalPane?) {
    val state = appUpdate.state.collectAsStateWithLifecycle().value
    if (state == State.Hidden) return

    val canInstallInModuleState = appUpdate.canInstallInModuleState.collectAsStateWithLifecycle(initialValue = false).value
    val isDownloading = state == State.Downloading
    val isDownloadPhase = isDownloading || state == State.Available || state == State.Preparing
    val title = stringResource(if (isDownloadPhase) R.string.app_update_available else R.string.app_update_title)
    val installLabel = stringResource(R.string.app_update_install)
    val downloadProgressDescription = if (isDownloading) stringResource(R.string.app_update_downloading) else null
    val updateIcon = painterResource(R.drawable.app_icon_autorenew)
    val updateIconTint = MaterialTheme.colorScheme.primary
    val stripTextStyle = MaterialTheme.typography.titleMedium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(updateIcon, contentDescription = null, modifier = Modifier.size(24.dp), tint = updateIconTint)
            Text(title, Modifier.weight(1f), style = stripTextStyle)
            TextButton(
                onClick = if (isDownloadPhase) appUpdate::requestDownload else appUpdate::requestInstall,
                enabled = state == State.Available || (state == State.Downloaded && canInstallInModuleState),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        if (downloadProgressDescription != null) {
                            contentDescription = downloadProgressDescription
                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        }
                    },
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = ButtonDefaults.ContentPadding.calculateTopPadding(),
                    end = 16.dp,
                    bottom = ButtonDefaults.ContentPadding.calculateBottomPadding(),
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Measure both labels so the spinner fits without changing the button's size.
                    val hiddenLabelModifier = Modifier
                        .alpha(0f)
                        .clearAndSetSemantics {}
                    Text(
                        stringResource(R.string.app_update_download),
                        if (isDownloadPhase && !isDownloading) Modifier else hiddenLabelModifier,
                        style = stripTextStyle,
                    )
                    Text(installLabel, if (isDownloadPhase) hiddenLabelModifier else Modifier, style = stripTextStyle)
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .clearAndSetSemantics {},
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }

        if (state == State.ConfirmInstall) {
            AppDialog(onDismiss = appUpdate::dismissInstallConfirmation, title = installLabel, pane = modalPane) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        updateIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(24.dp),
                        tint = updateIconTint,
                    )
                    Text(
                        installLabel,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(stringResource(R.string.app_update_message))
                    FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = appUpdate::dismissInstallConfirmation) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        Button(onClick = appUpdate::confirmInstall, enabled = canInstallInModuleState) { Text(installLabel) }
                    }
                }
            }
        }
    }
}
