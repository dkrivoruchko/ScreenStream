package info.dvkr.screenstream.ui.tabs.exit

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import info.dvkr.screenstream.common.module.StreamingModuleManager
import org.koin.compose.koinInject

@Composable
public fun ExitTabContent(
    modifier: Modifier = Modifier,
    streamingModulesManager: StreamingModuleManager = koinInject()
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val activity = LocalActivity.current
        LaunchedEffect(Unit) {
            activity?.apply {
                streamingModulesManager.stopModule()
                finishAndRemoveTask()
            }
        }
    }
}