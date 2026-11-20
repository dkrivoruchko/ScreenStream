package io.screenstream.core.compose

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import io.screenstream.core.CoreKoinTestRule
import io.screenstream.core.R
import io.screenstream.core.permission.PermissionState
import io.screenstream.core.permission.RuntimePermission
import io.screenstream.core.permission.RuntimePermissions
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class RuntimePermissionHostTest {
    @get:Rule(order = Int.MIN_VALUE)
    val koin = CoreKoinTestRule()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val history = mutableStateOf(emptySet<String>())
    private val callbackVersion = mutableStateOf(1)
    private val showOrigin = mutableStateOf(true)
    private val observations = mutableListOf<Triple<Int, String, Boolean>>()
    private val registry = RecordingPermissionRegistry()
    private lateinit var permissions: RuntimePermissions
    private var scenario: ActivityScenario<RuntimePermissionHostTestActivity>? = null

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun tearDown() {
        scenario?.close()
        RuntimePermissionHostTestActivity.content = null
    }

    // Cases: CORE-PERM-04, CORE-PERM-05
    @Test
    fun initialNotificationOfferIsNotRepeatedAfterDismissalAndRecreation() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        launchHost()
        compose.onNodeWithText(text(R.string.core_permission_notifications_title)).assertExists()
        compose.onNodeWithText(text(R.string.core_permission_not_now)).performClick()

        checkNotNull(scenario).recreate()
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.core_permission_notifications_title)).assertDoesNotExist()
        assertTrue(registry.launches.isEmpty())
        compose.runOnIdle {
            assertEquals(PermissionState.Requestable, permissions.state(RuntimePermission.Notifications).value)
            assertTrue(permissions.tryRequest(RuntimePermission.Notifications))
        }
        compose.onNodeWithText(text(R.string.core_permission_notifications_title)).assertExists()
    }

    // Cases: CORE-PERM-04
    @Test
    fun pausedAndOccupiedRequestsAreRejectedWithoutQueueing() {
        launchHost()
        checkNotNull(scenario).moveToState(Lifecycle.State.STARTED)
        compose.runOnIdle { assertFalse(permissions.tryRequest(RuntimePermission.Microphone)) }
        checkNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
        request(RuntimePermission.Microphone)
        compose.runOnIdle {
            assertFalse(permissions.tryRequest(RuntimePermission.DeviceAudio))
            assertEquals(PermissionState.Requesting, permissions.state(RuntimePermission.RecordAudio).value)
        }
        compose.onNodeWithText(text(R.string.core_permission_not_now)).performClick()
        compose.onNodeWithText(text(R.string.core_permission_device_audio_title)).assertDoesNotExist()
        assertTrue(registry.launches.isEmpty())
        request(RuntimePermission.DeviceAudio)
        compose.onNodeWithText(text(R.string.core_permission_device_audio_title)).assertExists()
    }

    // Cases: CORE-PERM-05, CORE-PERM-06
    @Test
    fun submittedRequestOutlivesOriginAndRecreationAndUsesLatestCallback() {
        launchHost()
        request(RuntimePermission.Microphone)
        compose.runOnIdle {
            showOrigin.value = false
            callbackVersion.value = 2
        }
        compose.onNodeWithText(text(R.string.core_continue)).performClick()
        assertEquals(listOf(listOf(Manifest.permission.RECORD_AUDIO)), registry.launches.map { it.permissions })

        checkNotNull(scenario).recreate()
        compose.waitForIdle()
        assertEquals(1, registry.launches.size)
        compose.onNodeWithText("origin").assertDoesNotExist()
        compose.runOnIdle {
            shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
            registry.reply(granted = true)
        }
        compose.waitForIdle()

        assertEquals(listOf(Triple(2, Manifest.permission.RECORD_AUDIO, false)), observations)
        compose.runOnIdle {
            listOf(RuntimePermission.Microphone, RuntimePermission.DeviceAudio, RuntimePermission.RecordAudio).forEach {
                assertEquals(PermissionState.Granted, permissions.state(it).value)
                assertTrue(permissions.isGranted(it))
            }
        }
    }

    // Cases: CORE-PERM-06, CORE-PERM-07
    @Test
    fun loadedHistoryIsASnapshotAndSettingsReturnSettlesTheSlot() {
        history.value = setOf(Manifest.permission.RECORD_AUDIO)
        launchHost()
        request(RuntimePermission.DeviceAudio)
        compose.runOnIdle { history.value = emptySet() }
        checkNotNull(scenario).recreate()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.core_permission_open_settings)).performClick()
        checkNotNull(scenario).onActivity {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(it).nextStartedActivity.action)
        }
        assertTrue(registry.launches.isEmpty())
        compose.runOnIdle { assertEquals(PermissionState.Requesting, permissions.state(RuntimePermission.RecordAudio).value) }

        checkNotNull(scenario).moveToState(Lifecycle.State.STARTED)
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        checkNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        assertEquals(listOf(Triple(1, Manifest.permission.RECORD_AUDIO, false)), observations)
        compose.runOnIdle { assertEquals(PermissionState.Granted, permissions.state(RuntimePermission.DeviceAudio).value) }
    }

    // Cases: CORE-PERM-06
    @Test
    fun observedRationaleUpdatesLocalHistoryBeforeAnyPersistenceAndRefreshesCallback() {
        launchHost()
        checkNotNull(scenario).onActivity { it.rationale = true }
        request(RuntimePermission.Microphone)
        compose.runOnIdle { callbackVersion.value = 2 }
        compose.onNodeWithText(text(R.string.core_continue)).performClick()
        compose.runOnIdle { registry.reply(granted = false) }
        compose.waitForIdle()

        assertEquals(List(2) { Triple(2, Manifest.permission.RECORD_AUDIO, true) }, observations)
        assertTrue(history.value.isEmpty()) // The callback records events but does not feed storage back.
        checkNotNull(scenario).onActivity { it.rationale = false }
        request(RuntimePermission.DeviceAudio)
        compose.onNodeWithText(text(R.string.core_permission_open_settings)).assertExists()
    }

    // Cases: CORE-PERM-07
    @Test
    fun launcherFailureClearsTheSlotAndLaterRequestCanProceed() {
        launchHost()
        registry.launchFailure = IllegalStateException("Fixture launch failure")
        request(RuntimePermission.Microphone)
        compose.onNodeWithText(text(R.string.core_continue)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(PermissionState.Requestable, permissions.state(RuntimePermission.Microphone).value)
            assertFalse(permissions.isGranted(RuntimePermission.Microphone))
        }
        registry.launchFailure = null
        request(RuntimePermission.DeviceAudio)
        compose.onNodeWithText(text(R.string.core_continue)).performClick()
        assertEquals(2, registry.launches.size)
    }

    private fun text(resource: Int): String = application.getString(resource)

    private fun request(permission: RuntimePermission) {
        compose.runOnIdle { assertTrue(permissions.tryRequest(permission)) }
        compose.waitForIdle()
    }

    private fun launchHost() {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        RuntimePermissionHostTestActivity.content = {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val version = callbackVersion.value
                RuntimePermissionHost(
                    initialRationaleHistory = history.value,
                    onRationaleObserved = { permission, observed -> observations.add(Triple(version, permission, observed)) },
                ) {
                    permissions = LocalRuntimePermissions.current
                    if (showOrigin.value) Text("origin")
                }
            }
        }
        shadowOf(application.packageManager).addActivityIfNotPresent(ComponentName(application, RuntimePermissionHostTestActivity::class.java))
        scenario = ActivityScenario.launch(RuntimePermissionHostTestActivity::class.java)
        compose.waitForIdle()
    }
}

private class RecordingPermissionRegistry : ActivityResultRegistry() {
    data class Launch(val requestCode: Int, val permissions: List<String>)

    val launches = mutableListOf<Launch>()
    var launchFailure: RuntimeException? = null

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        val permissions = (input as Array<*>).map { it as String }
        launches.add(Launch(requestCode, permissions))
        launchFailure?.let { throw it }
    }

    fun reply(granted: Boolean) {
        val launch = launches.last()
        dispatchResult(launch.requestCode, launch.permissions.associateWith { granted })
    }
}

internal class RuntimePermissionHostTestActivity : ComponentActivity() {
    var rationale: Boolean = false

    override fun shouldShowRequestPermissionRationale(permission: String): Boolean = rationale

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { checkNotNull(content).invoke() } }
    }

    internal companion object {
        var content: (@Composable () -> Unit)? = null
    }
}
