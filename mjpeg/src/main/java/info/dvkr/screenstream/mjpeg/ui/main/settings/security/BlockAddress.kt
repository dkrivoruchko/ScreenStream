package info.dvkr.screenstream.mjpeg.ui.main.settings.security

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun BlockAddressRow(
    blockAddress: Boolean,
    enablePin: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = enablePin,
        checked = blockAddress,
        iconRes = R.drawable.block_24px,
        title = stringResource(R.string.mjpeg_pref_block_address),
        summary = stringResource(R.string.mjpeg_pref_block_address_summary),
        onValueChange = onValueChange
    )
}
