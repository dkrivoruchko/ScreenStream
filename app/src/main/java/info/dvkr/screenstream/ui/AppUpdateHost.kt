package info.dvkr.screenstream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.R
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun AppUpdateHost(updateFlow: StateFlow<((Boolean) -> Unit)?>) {
    val updateCallbackState = updateFlow.collectAsStateWithLifecycle()
    if (updateCallbackState.value != null) {
        AppUpdateSheet(
            onConfirm = { updateCallbackState.value?.invoke(true) },
            onDismiss = { updateCallbackState.value?.invoke(false) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppUpdateSheet(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.medium,
        dragHandle = null
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(painter = painterResource(R.drawable.ic_notification_small_24dp), contentDescription = null)
            Text(
                text = stringResource(id = R.string.app_activity_update_dialog_title),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp + 24.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )
        }
        Text(
            text = stringResource(id = R.string.app_activity_update_dialog_message),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .padding(end = 16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.padding(end = 16.dp)
            ) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.app_activity_update_dialog_restart))
            }
        }
    }
}
