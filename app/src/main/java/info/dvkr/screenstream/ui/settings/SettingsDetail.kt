package info.dvkr.screenstream.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.SettingsDestination

@Composable
internal fun SettingsDetail(destination: SettingsDestination, onBack: () -> Unit) {
    when (destination) {
        SettingsDestination.APP_LANGUAGE -> LanguageSettings(onBack)
        SettingsDestination.DARK_THEME -> NightModeSettings(onBack)
    }
}

@Composable
internal fun SettingsDetailLayout(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        SettingsDetailHeader(title, onBack)
        content()
    }
}

@Composable
private fun SettingsDetailHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = dropUnlessResumed(block = onBack)) {
            Icon(
                painter = painterResource(R.drawable.arrow_back_24px),
                contentDescription = stringResource(R.string.app_pref_back),
            )
        }
        Text(
            text = title,
            modifier = Modifier.padding(start = 16.dp),
            fontSize = 18.sp,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
