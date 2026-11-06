package io.screenstream.core.permission

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
import androidx.annotation.MainThread
import androidx.core.net.toUri
import kotlinx.coroutines.flow.StateFlow

/**
 * Runtime permissions used by streaming modules. Each entry selects a request explanation; entries sharing
 * [androidPermission] share one grant, [PermissionState], and request slot.
 */
@SuppressLint("InlinedApi")
public enum class RuntimePermission(public val androidPermission: String) {
    /** POST_NOTIFICATIONS on API 33+; availability also requires app-wide enablement on every API. */
    Notifications(Manifest.permission.POST_NOTIFICATIONS),

    /** ACCESS_LOCAL_NETWORK is required when both device and target SDK are API 37+. */
    LocalNetwork(Manifest.permission.ACCESS_LOCAL_NETWORK),

    /** General RECORD_AUDIO explanation, sharing grant, state and request UI with the audio aliases. */
    RecordAudio(Manifest.permission.RECORD_AUDIO),

    /** Microphone explanation for the same RECORD_AUDIO grant and request slot. */
    Microphone(Manifest.permission.RECORD_AUDIO),

    /** Device-audio explanation for the same RECORD_AUDIO grant and request slot. */
    DeviceAudio(Manifest.permission.RECORD_AUDIO);

    /** Whether this OS and target SDK require a runtime grant for this permission. */
    public fun isRequired(context: Context): Boolean = when (this) {
        Notifications -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        LocalNetwork -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.CINNAMON_BUN
        RecordAudio, Microphone, DeviceAudio -> true
    }

    /**
     * Whether the runtime grant is held or not required and, for [Notifications], notifications are enabled
     * app-wide. Channel settings, manifest declarations, and other prerequisites are not checked. Usable from
     * Services and process work.
     */
    public fun isGranted(context: Context): Boolean =
        (!isRequired(context) || context.checkSelfPermission(androidPermission) == PackageManager.PERMISSION_GRANTED) &&
                (this != Notifications || context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())

    /**
     * Whether Android currently recommends a rationale for this required, denied permission. False does not
     * distinguish a first request from permanent denial.
     */
    public fun shouldShowRationale(activity: Activity): Boolean =
        isRequired(activity) && activity.checkSelfPermission(androidPermission) == PackageManager.PERMISSION_DENIED &&
                activity.shouldShowRequestPermissionRationale(androidPermission)

    /**
     * Whether a request should open settings: [Notifications] are disabled app-wide although the runtime
     * grant is held or not required, or this permission showed a rationale before (it is listed in
     * [permissionsWithObservedRationale]) and no longer does, which suggests but does not prove permanent denial.
     */
    public fun suggestsSettings(activity: Activity, permissionsWithObservedRationale: Collection<String>): Boolean =
        (this == Notifications && !isGranted(activity) &&
                (!isRequired(activity) || activity.checkSelfPermission(androidPermission) == PackageManager.PERMISSION_GRANTED)) ||
                (androidPermission in permissionsWithObservedRationale && !activity.shouldShowRequestPermissionRationale(androidPermission))

    /**
     * Opens app notification settings for [Notifications] on API 26+, otherwise app details. The caller
     * handles launch exceptions, settles its request on failure or return, and then rechecks availability.
     */
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

/** Last-known request UI hint; check current availability before protected work. */
public enum class PermissionState {
    /** The last published check found availability. */
    Granted,

    /** Offer an explanation and request; Android decides whether to show another prompt. */
    Requestable,

    /** The next request opens settings; see [RuntimePermission.suggestsSettings]. */
    SettingsSuggested,

    /** This Android permission owns app request UI; the host may be showing its explanation, system dialog, or settings. */
    Requesting,
}

/**
 * Activity-root permission request capability, separate from screen-capture consent.
 *
 * The implementing host owns explanations, Android launchers, rationale history, and request settlement
 * across Activity recreation. It settles a request on result, settings return, dismissal, or launch
 * failure; an admitted request outlives the module screen that made it. Callers get admission and
 * availability only: after a request, recheck [isGranted] and decide whether the original action still applies.
 */
public interface RuntimePermissions {
    /**
     * Last-known UI hint, initialized on first observation and updated around this host's requests, not on
     * independent OS changes. Entries sharing [RuntimePermission.androidPermission] share one flow.
     */
    @MainThread
    public fun state(permission: RuntimePermission): StateFlow<PermissionState>

    /** Current availability, as [RuntimePermission.isGranted]; can be true while request UI is still open. */
    public fun isGranted(permission: RuntimePermission): Boolean

    /**
     * Requests [permission] on Main. Returns false when the Activity is not resumed, or when the permission
     * is missing and another request, even for the same Android permission, occupies the slot. Returns true
     * when already granted, without UI, or when a new explanation is admitted. True is not authorization;
     * check [isGranted] before protected work. Rejected requests are not queued.
     */
    @MainThread
    public fun tryRequest(permission: RuntimePermission): Boolean
}
