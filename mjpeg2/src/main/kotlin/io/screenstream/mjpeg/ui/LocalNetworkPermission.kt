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

/**
 * Ordinary platform permission; the launcher stays registered even while no request is needed.
 * @param requestKey Exact current selection; null hides the action without removing the launcher.
 * @param onPermissionChange Recheck authoritative permission after a result or host resume.
 * @param claimPermissionRequest Controller admission and automatic-request budget for this exact key.
 */
@SuppressLint("InlinedApi")
@Composable
internal fun LocalNetworkPermission(
    requestKey: UiController.PermissionRequestKey?,
    onPermissionChange: () -> Unit,
    claimPermissionRequest: (UiController.PermissionRequestKey, Boolean) -> Boolean,
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
    val currentOnPermissionChange by rememberUpdatedState(onPermissionChange)
    val currentClaimPermissionRequest by rememberUpdatedState(claimPermissionRequest)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = granted()
        requested = true
        currentOnPermissionChange()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = granted()
        currentOnPermissionChange()
    }
    LaunchedEffect(requestKey, lifecycle) {
        if (requestKey != null) lifecycle.withResumed {
            if (!granted() && currentClaimPermissionRequest(requestKey, true)) {
                requested = true
                launcher.launch(permission)
            }
        }
    }
    if (requestKey != null && !hasPermission) {
        TextButton(onClick = {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                !currentClaimPermissionRequest(requestKey, false)
            ) return@TextButton
            if (requested && !activity.shouldShowRequestPermissionRationale(permission)) {
                val uri = "package:${context.packageName}".toUri()
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            } else {
                requested = true
                launcher.launch(permission)
            }
        }) { Text(stringResource(R.string.mjpeg_allow_local_network)) }
    }
}
