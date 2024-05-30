package info.dvkr.screenstream.logger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.R
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CollectingLogsUi(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val showBottomSheet = remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val description = remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.error
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(id = R.string.app_logs_collecting),
                modifier = Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(
                    onClick = { showBottomSheet.value = true },
                    modifier = Modifier
                        .weight(1F)
                        .padding(end = 8.dp),
                    border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(MaterialTheme.colorScheme.onError))
                ) {
                    Text(
                        text = stringResource(id = R.string.app_logs_send_title),
                        color = MaterialTheme.colorScheme.onError,
                        textAlign = TextAlign.Center
                    )
                }
                OutlinedButton(
                    onClick = { AppLogger.disableLogging(context) },
                    modifier = Modifier
                        .weight(1F)
                        .padding(start = 8.dp),
                    border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(MaterialTheme.colorScheme.onError))
                ) {
                    Text(
                        text = stringResource(id = R.string.app_logs_stop),
                        color = MaterialTheme.colorScheme.onError,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (showBottomSheet.value) {
                ModalBottomSheet(
                    onDismissRequest = { showBottomSheet.value = false },
                    sheetState = sheetState,
                    dragHandle = null,
                ) {
                    Text(
                        text = stringResource(id = R.string.app_logs_send_title),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(id = R.string.app_logs_send_message),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )

                    TextField(
                        value = description.value,
                        onValueChange = { description.value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .focusRequester(focusRequester)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    sheetState.hide()
                                    AppLogger.sendLogsInEmail(context, description.value)
                                }.invokeOnCompletion {
                                    if (sheetState.isVisible.not()) showBottomSheet.value = false
                                }
                            }
                        ) { Text(text = stringResource(id = R.string.app_logs_send)) }

                        TextButton(
                            onClick = {
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    if (sheetState.isVisible.not()) showBottomSheet.value = false
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) { Text(text = stringResource(id = android.R.string.cancel)) }
                    }

                    Spacer(Modifier.navigationBarsPadding())
                }

                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            }
        }
    }
}