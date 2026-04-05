package info.dvkr.screenstream.mjpeg.ui.main.settings.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.mjpeg.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MjpegSettingModal(
    windowWidthSizeClass: StreamingModule.WindowWidthSizeClass,
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    when (windowWidthSizeClass) {
        StreamingModule.WindowWidthSizeClass.COMPACT -> {
            val sheetState = rememberModalBottomSheetState()
            val scope = rememberCoroutineScope()
            val closeSheet = {
                scope.launch { sheetState.hide() }
                    .invokeOnCompletion { if (!sheetState.isVisible) onDismissRequest() }
                Unit
            }

            ModalBottomSheet(
                onDismissRequest = onDismissRequest,
                sheetState = sheetState,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 0.dp, bottomStart = 0.dp),
                sheetMaxWidth = 480.dp,
                dragHandle = null
            ) {
                MjpegSettingModalTitle(
                    title = title,
                    onClose = closeSheet
                )
                content()
            }
        }

        StreamingModule.WindowWidthSizeClass.MEDIUM,
        StreamingModule.WindowWidthSizeClass.EXPANDED -> {
            val dialogMaxWidth = when (windowWidthSizeClass) {
                StreamingModule.WindowWidthSizeClass.MEDIUM -> 440.dp
                StreamingModule.WindowWidthSizeClass.EXPANDED -> 520.dp
            }

            Dialog(
                onDismissRequest = onDismissRequest,
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Card(
                    modifier = Modifier
                        .padding(16.dp)
                        .widthIn(min = 320.dp, max = dialogMaxWidth),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        MjpegSettingModalTitle(
                            title = title,
                            onClose = onDismissRequest
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MjpegSettingModalTitle(
    title: String,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .background(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier
                .padding(start = 20.dp)
                .weight(1f),
            fontSize = 18.sp,
            style = MaterialTheme.typography.titleMedium
        )
        IconButton(onClick = onClose) {
            Icon(
                painter = painterResource(R.drawable.close_24px),
                contentDescription = stringResource(R.string.mjpeg_close)
            )
        }
    }
}
