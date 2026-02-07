package info.dvkr.screenstream.mjpeg.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.mjpeg.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsGroupCard(
    settingsGroup: ModuleSettings.Group,
    modifier: Modifier = Modifier,
    scope: CoroutineScope = rememberCoroutineScope()
) {
    val items = settingsGroup.items
    if (items.isEmpty()) return

    ExpandableCard(
        headerContent = {
            settingsGroup.TitleUI(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            )
        },
        modifier = modifier,
        initiallyExpanded = false
    ) {
        var showBottomSheetForItemId by remember(settingsGroup.id) { mutableStateOf<String?>(null) }

        items.forEachIndexed { index, item ->
            item.ItemUI(
                horizontalPadding = 0.dp,
                coroutineScope = scope
            ) {
                showBottomSheetForItemId = item.id
            }

            if (index != items.lastIndex) HorizontalDivider()
        }

        if (showBottomSheetForItemId != null) {
            val selectedItem = items.firstOrNull { it.id == showBottomSheetForItemId }
            if (selectedItem != null) {
                val sheetState = rememberModalBottomSheetState()
                ModalBottomSheet(
                    onDismissRequest = { showBottomSheetForItemId = null },
                    sheetState = sheetState,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 0.dp, bottomStart = 0.dp),
                    sheetMaxWidth = 480.dp,
                    dragHandle = null
                ) {
                    selectedItem.DetailUI { title ->
                        BottomSheetTitle(title) {
                            scope.launch { sheetState.hide() }
                                .invokeOnCompletion { if (!sheetState.isVisible) showBottomSheetForItemId = null }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomSheetTitle(
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
                .padding(start = 12.dp)
                .weight(1f),
            fontSize = 18.sp,
            style = MaterialTheme.typography.titleMedium
        )
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.close_24px), contentDescription = stringResource(id = R.string.mjpeg_close))
        }
    }
}