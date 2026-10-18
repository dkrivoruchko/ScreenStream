package io.screenstream.streaming.permission

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.net.toUri
import kotlinx.coroutines.flow.StateFlow

/** Request explanations; entries sharing [androidPermission] share grants, state and Android UI. */
@SuppressLint("InlinedApi")
public enum class RuntimePermission(public val androidPermission: String) {
    Notifications(Manifest.permission.POST_NOTIFICATIONS),
    LocalNetwork(Manifest.permission.ACCESS_LOCAL_NETWORK),
    RecordAudio(Manifest.permission.RECORD_AUDIO),
    Microphone(Manifest.permission.RECORD_AUDIO),
    DeviceAudio(Manifest.permission.RECORD_AUDIO);

    /** Whether this OS and target SDK require a runtime grant for this permission. */
    public fun isRequired(context: Context): Boolean = when (this) {
        Notifications -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        LocalNetwork -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.CINNAMON_BUN
        RecordAudio, Microphone, DeviceAudio -> true
    }

    /** Read the applicable OS grant and, for notifications, app-wide enablement. */
    public fun isGranted(context: Context): Boolean =
        (!isRequired(context) || context.checkSelfPermission(androidPermission) == PackageManager.PERMISSION_GRANTED) &&
                (this != Notifications || context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())

    public fun shouldShowRequestPermissionRationale(activity: Activity): Boolean =
        activity.shouldShowRequestPermissionRationale(androidPermission)

    /**
     * Settings route for notifications disabled at app level with no missing runtime grant,
     * otherwise a rationale-history hint. Not proof of permanent denial.
     */
    public fun suggestsSettings(activity: Activity, permissionsWithObservedRationale: Collection<String>): Boolean =
        (this == Notifications && !isGranted(activity) &&
                (!isRequired(activity) || activity.checkSelfPermission(androidPermission) == PackageManager.PERMISSION_GRANTED)) ||
                (androidPermission in permissionsWithObservedRationale && !shouldShowRequestPermissionRationale(activity))

    /** Open this permission's settings destination; the caller handles launch failures. */
    @Throws(ActivityNotFoundException::class, SecurityException::class)
    public fun openSettings(activity: Activity) {
        activity.startActivity(
            if (this == Notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri())
            },
        )
    }
}

/** Last-known request/UI hint; [Granted] is not authorization and [SettingsSuggested] is not permanent denial. */
public enum class PermissionState {
    Granted,
    Requestable,
    SettingsSuggested,
    Requesting,
}

/** Activity-root-owned ordinary permission UI, independent of capture consent and business actions. */
public interface RuntimePermissions {
    /**
     * Last-known request/UI hint, initialized on first observation and updated for this permission's
     * requests. Unrelated OS changes are not monitored; use [isGranted] for the current grant.
     */
    public fun state(permission: RuntimePermission): StateFlow<PermissionState>

    /** Read the current grant and notification enablement; recheck before performing protected work. */
    public fun isGranted(permission: RuntimePermission): Boolean

    /**
     * Return true when already granted or admitted to the app-owned explanation. This is admission,
     * not a permission grant: use [isGranted] before protected work. Return false while another
     * permission owns the UI or the Activity is not resumed.
     * Call from the UI thread. Requests are not queued; an admitted explanation survives the
     * originating screen's exit.
     */
    public fun tryRequest(permission: RuntimePermission): Boolean
}

/** Provided by the app's Activity root; modules receive the same permission host capability. */
public val LocalRuntimePermissions: ProvidableCompositionLocal<RuntimePermissions> = staticCompositionLocalOf {
    error("RuntimePermissions is not provided")
}
