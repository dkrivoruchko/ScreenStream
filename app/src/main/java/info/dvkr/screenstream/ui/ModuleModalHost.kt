package info.dvkr.screenstream.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.screenstream.core.module.StreamingModule.Ui.ModalHost

internal class ModuleModalHost(private val pane: ModalPane?) : ModalHost {
    @Composable
    override fun Modal(title: String?, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        if (title == null) {
            AppDialog(onDismiss = onDismissRequest, pane = pane, compact = true, content = content)
        } else {
            AdaptiveAppModal(title = title, onDismiss = onDismissRequest, pane = pane) {
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    content = { content() }
                )
            }
        }
    }
}
