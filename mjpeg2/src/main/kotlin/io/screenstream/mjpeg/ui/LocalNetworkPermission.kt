package io.screenstream.mjpeg.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import info.dvkr.screenstream.common.findActivity
import io.screenstream.mjpeg.R
import java.util.concurrent.atomic.AtomicBoolean

/** One module-created selection episode; eligibility remains bound to its originating module. */
internal class LocalNetworkPermissionRequest(
    private val isEligible: (LocalNetworkPermissionRequest) -> Boolean,
) {
    private val automaticClaimed = AtomicBoolean()

    /** Automatic admission consumes one budget; manual admission leaves it intact. */
    fun tryClaim(automatic: Boolean): Boolean =
        isEligible(this) && (!automatic || automaticClaimed.compareAndSet(false, true))
}

/**
 * Keep the launcher registered when no request is needed; a result must still recheck permission.
 * Automatic launches claim the exact current selection only while the host is resumed.
 */
@SuppressLint("InlinedApi")
@Composable
internal fun LocalNetworkPermission(
    request: LocalNetworkPermissionRequest?,
    onPermissionCheck: () -> Unit,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
    val context = LocalContext.current
    if (context.applicationInfo.targetSdkVersion < Build.VERSION_CODES.CINNAMON_BUN) return
    val activity = remember(context) { context.findActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
    fun granted(): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(granted()) }
    var requested by remember { mutableStateOf(false) }
    val currentOnPermissionCheck by rememberUpdatedState(onPermissionCheck)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = granted()
        requested = true
        currentOnPermissionCheck()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = granted()
        currentOnPermissionCheck()
    }
    LaunchedEffect(request, lifecycle) {
        if (request != null) lifecycle.withResumed {
            if (!granted() && request.tryClaim(automatic = true)) {
                requested = true
                launcher.launch(permission)
            }
        }
    }
    if (request != null && !hasPermission) {
        TextButton(onClick = {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                !request.tryClaim(automatic = false)
            ) return@TextButton
            if (requested && !activity.shouldShowRequestPermissionRationale(permission)) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            } else {
                requested = true
                launcher.launch(permission)
            }
        }) { Text(stringResource(R.string.mjpeg_allow_local_network)) }
    }
}
