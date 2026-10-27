package info.dvkr.screenstream.ui

import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.settings.AppSettings
import io.screenstream.streaming.permission.LocalRuntimePermissions
import io.screenstream.streaming.permission.PermissionState
import io.screenstream.streaming.permission.RuntimePermission
import io.screenstream.streaming.permission.RuntimePermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import info.dvkr.screenstream.common.R as CommonR

private enum class PermissionPhase { Explanation, SystemDialog, Settings }

/** One app-owned permission UI slot; originating screens retain no launchers or permission waiters. */
@Composable
internal fun RuntimePermissionHost(
    appSettings: AppSettings,
    content: @Composable () -> Unit,
) {
    val activity = checkNotNull(LocalActivity.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Only a new UI offers notifications; restored active/null state preserves the prior choice.
    var activePermission by rememberSaveable {
        mutableStateOf(if (RuntimePermission.Notifications.isGranted(activity)) null else RuntimePermission.Notifications)
    }
    var requestPhase by rememberSaveable { mutableStateOf(PermissionPhase.Explanation) }
    var permissionsWithObservedRationale by key("permission-rationale-history") {
        rememberSaveable {
            mutableStateOf(appSettings.data.value.permissionsWithObservedRationale.toList())
        }
    }
    val mutableStateFlows = remember(activity) { mutableMapOf<String, MutableStateFlow<PermissionState>>() }
    val stateFlows = remember(mutableStateFlows) { mutableMapOf<String, StateFlow<PermissionState>>() }

    fun readPermissionState(permission: RuntimePermission): PermissionState = when {
        permission.isGranted(activity) -> PermissionState.Granted
        activePermission?.androidPermission == permission.androidPermission -> PermissionState.Requesting
        permission.suggestsSettings(activity, permissionsWithObservedRationale) -> PermissionState.SettingsSuggested
        else -> PermissionState.Requestable
    }

    fun publishPermissionState(permission: RuntimePermission) {
        mutableStateFlows[permission.androidPermission]?.value = readPermissionState(permission)
    }

    fun clearRationaleHistoryIfGranted(permission: RuntimePermission) {
        if (!permission.isRequired(activity) || activity.checkSelfPermission(permission.androidPermission) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        permissionsWithObservedRationale = permissionsWithObservedRationale - permission.androidPermission
        appSettings.setPermissionRationaleObserved(permission.androidPermission, observed = false)
    }

    fun finishRequest() {
        val permission = activePermission
        activePermission = null
        requestPhase = PermissionPhase.Explanation
        permission?.let(::publishPermissionState)
    }

    val runtimePermissions = remember(activity, lifecycle, stateFlows) {
        object : RuntimePermissions {
            override fun state(permission: RuntimePermission): StateFlow<PermissionState> = stateFlows.getOrPut(permission.androidPermission) {
                val stateFlow = MutableStateFlow(readPermissionState(permission))
                mutableStateFlows[permission.androidPermission] = stateFlow
                stateFlow.asStateFlow()
            }

            override fun isGranted(permission: RuntimePermission): Boolean = permission.isGranted(activity)

            override fun tryRequest(permission: RuntimePermission): Boolean {
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
                if (permission.isGranted(activity)) {
                    clearRationaleHistoryIfGranted(permission)
                    publishPermissionState(permission)
                    return true
                }
                if (activePermission != null) return false
                activePermission = permission
                requestPhase = PermissionPhase.Explanation
                publishPermissionState(permission)
                return true
            }
        }
    }

    fun recordObservedRationale(permission: RuntimePermission) {
        if (!permission.shouldShowRationale(activity)) return
        if (permission.androidPermission !in permissionsWithObservedRationale) {
            permissionsWithObservedRationale = permissionsWithObservedRationale + permission.androidPermission
        }
        appSettings.setPermissionRationaleObserved(permission.androidPermission, observed = true)
    }

    // Always register: Android can deliver a result to a recreated Activity/root.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantResults ->
        val permission = activePermission ?: return@rememberLauncherForActivityResult
        if (requestPhase != PermissionPhase.SystemDialog) return@rememberLauncherForActivityResult
        when (grantResults[permission.androidPermission]) {
            true -> clearRationaleHistoryIfGranted(permission)
            false -> recordObservedRationale(permission)
            null -> Unit
        }
        finishRequest()
    }

    fun confirmPermissionRequest(permission: RuntimePermission, opensSettings: Boolean) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || activePermission != permission || requestPhase != PermissionPhase.Explanation) {
            return
        }
        clearRationaleHistoryIfGranted(permission)
        if (permission.isGranted(activity)) {
            finishRequest()
            return
        }
        requestPhase = if (opensSettings) PermissionPhase.Settings else PermissionPhase.SystemDialog
        try {
            if (opensSettings) {
                permission.openSettings(activity)
            } else {
                recordObservedRationale(permission)
                permissionLauncher.launch(arrayOf(permission.androidPermission))
            }
        } catch (failure: RuntimeException) {
            XLog.e("Could not open permission UI for $permission", failure)
            finishRequest()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val permission = activePermission ?: return@LifecycleEventEffect
        val returnedFromSettings = requestPhase == PermissionPhase.Settings
        val grantedDuringExplanation = requestPhase == PermissionPhase.Explanation && permission.isGranted(activity)
        if (returnedFromSettings || grantedDuringExplanation) {
            clearRationaleHistoryIfGranted(permission)
            finishRequest()
        }
    }

    CompositionLocalProvider(LocalRuntimePermissions provides runtimePermissions) {
        content()

        val permission = activePermission
        if (permission != null && requestPhase == PermissionPhase.Explanation) {
            val opensSettings = permission.suggestsSettings(activity, permissionsWithObservedRationale)
            val onDismiss = {
                if (activePermission == permission && requestPhase == PermissionPhase.Explanation) finishRequest()
            }

            val lifecycleState by lifecycle.currentStateAsState()
            PermissionExplanationDialog(
                permission = permission,
                opensSettings = opensSettings,
                canConfirm = lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
                onConfirm = { confirmPermissionRequest(permission, opensSettings) },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun PermissionExplanationDialog(
    permission: RuntimePermission,
    opensSettings: Boolean,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val titleResId = when (permission) {
        RuntimePermission.Notifications -> R.string.app_permission_notifications_title
        RuntimePermission.LocalNetwork -> if (opensSettings) R.string.app_permission_local_network_off_title else R.string.app_permission_local_network_title
        RuntimePermission.Microphone -> R.string.app_permission_microphone_title
        RuntimePermission.DeviceAudio -> R.string.app_permission_device_audio_title
        RuntimePermission.RecordAudio -> R.string.app_permission_audio_title
    }
    val messageResId = when (permission) {
        RuntimePermission.Notifications -> R.string.app_permission_notifications_message
        RuntimePermission.LocalNetwork -> if (opensSettings) R.string.app_permission_local_network_settings_message else R.string.app_permission_local_network_message
        RuntimePermission.Microphone -> if (opensSettings) R.string.app_permission_audio_settings_message else R.string.app_permission_microphone_message
        RuntimePermission.DeviceAudio -> if (opensSettings) R.string.app_permission_audio_settings_message else R.string.app_permission_device_audio_message
        RuntimePermission.RecordAudio -> if (opensSettings) R.string.app_permission_audio_settings_message else R.string.app_permission_audio_message
    }
    val iconResId = when (permission) {
        RuntimePermission.Notifications -> R.drawable.notifications_24px
        RuntimePermission.LocalNetwork -> R.drawable.app_permission_lan_24px
        RuntimePermission.Microphone -> R.drawable.app_permission_mic_24px
        RuntimePermission.DeviceAudio, RuntimePermission.RecordAudio -> R.drawable.app_permission_volume_up_24px
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleResId)) },
        text = { Text(stringResource(messageResId)) },
        icon = { Icon(painter = painterResource(iconResId), contentDescription = null) },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = onConfirm,
            ) {
                Text(stringResource(if (opensSettings) R.string.app_permission_open_settings else CommonR.string.common_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_permission_not_now)) }
        },
    )
}
