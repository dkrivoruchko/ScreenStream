package info.dvkr.screenstream.ui.tabs.settings.app

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.tabs.settings.app.common.SettingActionRow

@Composable
@SuppressLint("NewApi")
internal fun TileRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingActionRow(
        iconRes = R.drawable.tile_small_24px,
        title = stringResource(id = R.string.app_pref_tile),
        summary = stringResource(id = R.string.app_pref_tile_summary),
        onClick = onClick,
        modifier = modifier
    )
}
