package info.dvkr.screenstream.ui.tabs.exit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import info.dvkr.screenstream.app.SingleActivityViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.qualifier.named

@Composable
internal fun ExitTabContent(
    modifier: Modifier = Modifier,
    viewModel: SingleActivityViewModel = koinViewModel(qualifier = named("SingleActivityViewModel"))
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LaunchedEffect(Unit) { viewModel.requestExit() }
    }
}
