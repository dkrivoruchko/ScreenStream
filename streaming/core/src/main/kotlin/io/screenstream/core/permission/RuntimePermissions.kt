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

/** Request explanations; entries sharing [androidPermission] share grants, state and Android UI. */
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
     * Read the applicable OS grant and, for notifications, app-wide enablement on every supported API.
     * Individual notification channels have separate settings. Use this Context-based check for Service
     * or process work; the Activity root owns the request capability. Manifest declarations and other
     * operation prerequisites remain the caller's responsibility.
     */
    public fun isGranted(context: Context): Boolean =
        (!isRequired(context) || context.checkSelfPermission(androidPermission) == PackageManager.PERMISSION_GRANTED) &&
                (this != Notifications || context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())

    /**
     * Whether a denied runtime permission currently has an Android rationale to show. A false result
     * can describe a fresh request, permanent denial, an already granted permission, or an inapplicable
     * runtime grant. The result describes rationale visibility at this moment.
     */
    public fun shouldShowRationale(activity: Activity): Boolean =
        isRequired(activity) && activity.checkSelfPermission(androidPermission) == PackageManager.PERMISSION_DENIED &&
                activity.shouldShowRequestPermissionRationale(androidPermission)

    /**
     * Suggest settings for notifications disabled at app level when the runtime grant is present or inapplicable,
     * or use rationale history as a hint. Permanent denial requires its own evidence.
     */
    public fun suggestsSettings(activity: Activity, permissionsWithObservedRationale: Collection<String>): Boolean =
        (this == Notifications && !isGranted(activity) &&
                (!isRequired(activity) || activity.checkSelfPermission(androidPermission) == PackageManager.PERMISSION_GRANTED)) ||
                (androidPermission in permissionsWithObservedRationale && !activity.shouldShowRequestPermissionRationale(androidPermission))

    /**
     * Open app notification settings on API 26+ for [Notifications], otherwise app details.
     * The caller handles [ActivityNotFoundException] and [SecurityException] and settles its owned
     * request after launch failure or settings return. Observe permission availability after navigation.
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

/** Last-known request/UI hint; use current authorization for work and treat settings as a suggestion. */
public enum class PermissionState {
    /** The last published check found availability; recheck before protected work. */
    Granted,

    /** Offer an explanation/request action; Android decides whether to show another prompt. */
    Requestable,

    /** Settings or rationale history suggests navigation; permanent denial requires its own evidence. */
    SettingsSuggested,

    /** This Android permission owns app request UI; the host may be showing its explanation, system dialog, or settings. */
    Requesting,
}

/**
 * Activity-root-owned permission request UI, independent of screen consent and business actions.
 *
 * The host owns explanations, Android launchers, rationale history, and request settlement across
 * Activity recreation. It confirms before opening Android UI or settings, rechecks availability,
 * and settles on result, settings return, dismissal, or launch failure. An admitted request stays with
 * the Activity root after its originating module screen leaves.
 *
 * The capability exposes request admission and current availability; the feature owns its business
 * action, rechecks authority, and decides whether the original action is still relevant.
 */
public interface RuntimePermissions {
    /**
     * Last-known request/UI hint, initialized on first observation and updated for this permission's
     * requests. Use [isGranted] for current availability after independent OS changes. Entries sharing
     * [RuntimePermission.androidPermission] share the same read-only flow and UI slot.
     */
    @MainThread
    public fun state(permission: RuntimePermission): StateFlow<PermissionState>

    /**
     * Read the current grant and, for notifications, app-wide enablement; recheck before protected work.
     * A pending request UI lifetime is settled by its host.
     */
    public fun isGranted(permission: RuntimePermission): Boolean

    /**
     * Attempt synchronous admission on Main. An inactive Activity returns false even if granted.
     * When resumed, an existing grant returns true immediately, even if another request occupies the
     * slot. Otherwise an occupied slot returns false, including for the same Android permission; a free
     * slot returns true and admits the app-owned explanation.
     *
     * True indicates admission or existing availability; use [isGranted] before protected work. A new explanation
     * requires a free slot and the caller may retry after rejection. An admitted explanation survives the
     * originating screen's exit.
     */
    @MainThread
    public fun tryRequest(permission: RuntimePermission): Boolean
}
