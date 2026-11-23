package io.screenstream.mjpeg.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.screenstream.core.compose.LocalRuntimePermissions
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModule.Ui.ModalHost
import io.screenstream.core.permission.PermissionState
import io.screenstream.core.permission.RuntimePermission
import io.screenstream.core.permission.RuntimePermissions
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Join admitted saves without blocking the Compose dispatcher needed to finish those saves. */
internal fun AndroidComposeTestRule<*, *>.awaitSavedMjpegSettings(settings: MjpegSettings): MjpegSettings.Data {
    val saveScope = CoroutineScope(Dispatchers.Default)
    val savedSnapshot = saveScope.async {
        settings.updateData { this }
        settings.data.first()
    }
    return try {
        waitUntil(5_000) { savedSnapshot.isCompleted }
        runBlocking { savedSnapshot.await() }
    } finally {
        saveScope.cancel()
    }
}

internal class RecordingUiController : UiController {
    val instanceId = StreamingModule.InstanceId(StreamingModule.Id("MJPEG"))
    override val state = MutableStateFlow(UiController.State(instanceId, UiController.Action.Start(false)))
    val commands = mutableListOf<UiController.Command>()

    override fun send(uiCommand: UiController.Command) {
        commands += uiCommand
    }
}

internal class RecordingClipboard : Clipboard {
    var clipEntry: ClipEntry? = null
    override suspend fun getClipEntry(): ClipEntry? = clipEntry
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        this.clipEntry = clipEntry
    }
}

internal class RecordingRuntimePermissions : RuntimePermissions {
    var localNetworkGranted: Boolean = false
    val localNetworkState = MutableStateFlow(PermissionState.Requestable)
    val requests = mutableListOf<RuntimePermission>()
    private val granted = MutableStateFlow(PermissionState.Granted)

    override fun state(permission: RuntimePermission) =
        if (permission == RuntimePermission.LocalNetwork) localNetworkState else granted

    override fun isGranted(permission: RuntimePermission): Boolean =
        permission != RuntimePermission.LocalNetwork || localNetworkGranted

    override fun tryRequest(permission: RuntimePermission): Boolean {
        requests += permission
        return true
    }
}

@Composable
internal fun MjpegTestTheme(content: @Composable () -> Unit) {
    val grantedPermissions = remember {
        object : RuntimePermissions {
            private val granted = MutableStateFlow(PermissionState.Granted)
            override fun state(permission: RuntimePermission) = granted
            override fun isGranted(permission: RuntimePermission): Boolean = true
            override fun tryRequest(permission: RuntimePermission): Boolean = true
        }
    }
    CompositionLocalProvider(LocalRuntimePermissions provides grantedPermissions) {
        MaterialTheme {
            ModalHost.Provide(TestMjpegModalHost, content)
        }
    }
}

@Composable
internal fun MjpegTestLayout(ui: StreamingModule.Ui) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        LazyColumn(Modifier.weight(1f)) {
            items(ui.sections, key = { it.id }, contentType = { it.id }) { section -> section.content() }
        }
        ui.CaptureActionButton(Modifier.padding(8.dp))
    }
}

internal object TestMjpegModalHost : ModalHost {
    @Composable
    override fun Modal(title: String?, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        Dialog(onDismissRequest = onDismissRequest) {
            if (title == null) {
                content()
            } else {
                Surface {
                    Column(Modifier.fillMaxWidth()) {
                        Text(title)
                        val close = stringResource(R.string.mjpeg_action_close)
                        TextButton(onClick = onDismissRequest, modifier = Modifier.semantics { contentDescription = close }) {
                            Text(close)
                        }
                        Box(Modifier.weight(1f, fill = false)) { content() }
                    }
                }
            }
        }
    }
}
