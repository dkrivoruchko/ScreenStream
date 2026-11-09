package io.screenstream.core.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.os.Build
import android.provider.Settings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class RuntimePermissionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    // Cases: CORE-PERM-01
    @Test
    @Config(sdk = [32, 33])
    fun notificationAvailabilityCombinesRuntimeBoundaryAndAppEnablement() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        assertEquals(Build.VERSION.SDK_INT >= 33, RuntimePermission.Notifications.isRequired(app))
        assertEquals(Build.VERSION.SDK_INT < 33, RuntimePermission.Notifications.isGranted(app))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(RuntimePermission.Notifications.isGranted(app))
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertFalse(RuntimePermission.Notifications.isGranted(app))
    }

    // Cases: CORE-PERM-01
    @Test
    @Config(sdk = [36, 37])
    fun localNetworkNeedsBothModernDeviceAndTarget() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        for (target in listOf(36, 37)) {
            app.applicationInfo.targetSdkVersion = target
            val required = Build.VERSION.SDK_INT >= 37 && target >= 37
            assertEquals(required, RuntimePermission.LocalNetwork.isRequired(app))
            assertEquals(!required, RuntimePermission.LocalNetwork.isGranted(app))
        }
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        assertTrue(RuntimePermission.LocalNetwork.isGranted(app))
    }

    // Cases: CORE-PERM-01
    @Test
    fun audioAliasesUseTheActualSharedGrant() {
        val aliases = listOf(RuntimePermission.RecordAudio, RuntimePermission.Microphone, RuntimePermission.DeviceAudio)
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        aliases.forEach {
            assertTrue(it.isRequired(app))
            assertFalse(it.isGranted(app))
        }
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        aliases.forEach { assertTrue(it.isGranted(app)) }
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        aliases.forEach { assertFalse(it.isGranted(app)) }
    }

    // Cases: CORE-PERM-02
    @Test
    fun rationaleAndSettingsHintsDoNotInventPermanentDenialFromAFirstRequest() {
        val owner = Robolectric.buildActivity(PermissionActivity::class.java).setup()
        try {
            val activity = owner.get()
            shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
            assertFalse(RuntimePermission.Microphone.shouldShowRationale(activity))
            assertFalse(RuntimePermission.Microphone.suggestsSettings(activity, emptyList()))
            activity.rationale = true
            assertTrue(RuntimePermission.DeviceAudio.shouldShowRationale(activity))
            assertFalse(RuntimePermission.DeviceAudio.suggestsSettings(activity, listOf(Manifest.permission.RECORD_AUDIO)))
            activity.rationale = false
            assertTrue(RuntimePermission.RecordAudio.suggestsSettings(activity, listOf(Manifest.permission.RECORD_AUDIO)))
            assertFalse(RuntimePermission.RecordAudio.suggestsSettings(activity, listOf(Manifest.permission.POST_NOTIFICATIONS)))
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            activity.rationale = true
            assertFalse(RuntimePermission.Microphone.shouldShowRationale(activity))
        } finally {
            owner.pause().stop().destroy()
        }
    }

    // Cases: CORE-PERM-02
    @Test
    @Config(sdk = [32, 33])
    fun disabledNotificationsSuggestSettingsOnlyWhenGrantIsHeldOrNotRequired() {
        val owner = Robolectric.buildActivity(PermissionActivity::class.java).setup()
        try {
            val activity = owner.get()
            shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            assertEquals(Build.VERSION.SDK_INT < 33, RuntimePermission.Notifications.suggestsSettings(activity, emptyList()))
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            assertTrue(RuntimePermission.Notifications.suggestsSettings(activity, emptyList()))
        } finally {
            owner.pause().stop().destroy()
        }
    }

    // Cases: CORE-PERM-03
    @Test
    @Config(sdk = [25, 26])
    fun settingsDestinationsCarryTheCallingPackage() {
        val owner = Robolectric.buildActivity(PermissionActivity::class.java).setup()
        try {
            val activity = owner.get()
            RuntimePermission.Notifications.openSettings(activity)
            val notifications = shadowOf(activity).nextStartedActivity
            if (Build.VERSION.SDK_INT >= 26) {
                assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifications.action)
                assertEquals(activity.packageName, notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            } else {
                assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, notifications.action)
                assertEquals("package:${activity.packageName}", notifications.dataString)
            }
            RuntimePermission.Microphone.openSettings(activity)
            val audio = shadowOf(activity).nextStartedActivity
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, audio.action)
            assertEquals("package:${activity.packageName}", audio.dataString)
        } finally {
            owner.pause().stop().destroy()
        }
    }
}

internal class PermissionActivity : Activity() {
    var rationale: Boolean = false
    override fun shouldShowRequestPermissionRationale(permission: String): Boolean = rationale
}
