package io.screenstream.mjpeg.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.screenstream.mjpeg.R
import io.screenstream.streaming.module.StreamingModule

/** One capture action for this controller; Stop retains the exact attempt shown by the snapshot. */
@Composable
internal fun MjpegCaptureButton(
    uiController: UiController,
    onStart: () -> Unit,
    onStop: (StreamingModule.CaptureAttemptId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by uiController.state.collectAsStateWithLifecycle()
    val action = state.action
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(
            onClick = {
                when (action) {
                    is UiController.Action.Start -> onStart()
                    UiController.Action.Busy -> Unit
                    is UiController.Action.Stop -> onStop(action.attempt)
                }
            },
            enabled = when (action) {
                is UiController.Action.Start -> action.enabled
                UiController.Action.Busy -> false
                is UiController.Action.Stop -> true
            },
        ) {
            when (action) {
                is UiController.Action.Start -> Text(stringResource(R.string.mjpeg_start_stream))
                UiController.Action.Busy -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                is UiController.Action.Stop -> Text(stringResource(R.string.mjpeg_stop_stream))
            }
        }
    }
}
