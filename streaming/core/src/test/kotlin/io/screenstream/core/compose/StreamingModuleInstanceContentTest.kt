package io.screenstream.core.compose

import android.app.Notification
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.manager.StreamingModuleManagerImpl
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import io.screenstream.core.module.StreamingModuleService
import io.screenstream.core.settings.CoreSettingsTestContext
import io.screenstream.core.settings.StreamingSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class StreamingModuleInstanceContentTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val module = ContentModule()
    private val requested = mutableStateOf(StreamingModule.InstanceId(module.id))
    private val checkpoint = mutableIntStateOf(0)
    private val window = WindowSizeClass(600, 480)
    private val contentModifier = Modifier.testTag("module-content")
    private val controllers = mutableListOf<ContentController>()
    private var startIntent: Intent? = null
    private lateinit var manager: StreamingModuleManagerImpl
    private lateinit var scenario: ActivityScenario<CoreComposeTestActivity>

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        val settings = StreamingSettingsStore(CoreSettingsTestContext(application, temporaryFolder.newFolder()))
        runBlocking { withTimeout(5.seconds) { settings.initialize() } }
        val context = object : ContextWrapper(application) {
            override fun startService(service: Intent): ComponentName {
                startIntent = Intent(service)
                return checkNotNull(service.component)
            }
        }
        manager = StreamingModuleManagerImpl(listOf(module), context, settings, ContentNotifications(), dispatcher)
        scenario = launchCoreComposeHost {
            // These checkpoints test the manager's rendering decision, not an undocumented invalidation signal.
            key(checkpoint.intValue) {
                manager.InstanceContent(requested.value, window, contentModifier) { Text("fallback") }
            }
        }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        compose.runOnIdle {
            manager.exit()
            controllers.forEach { it.cleanup.complete(true) }
            scheduler.runCurrent()
        }
        scenario.close()
        CoreComposeTestActivity.content = null
    }

    // Cases: CORE-UI-11
    @Test
    fun absentAndUnknownInstanceUseFallbackWhileMatchingControllerReceivesInputs() {
        compose.onNodeWithText("fallback").assertExists()
        compose.onNodeWithTag("module-content").assertDoesNotExist()
        val controller = open()
        show(StreamingModule.InstanceId(StreamingModule.Id("unknown")))
        compose.onNodeWithText("fallback").assertExists()
        assertTrue(controller.renderedInputs.isEmpty())

        show(controller.instanceId)
        compose.onNodeWithText(controller.label).assertExists()
        compose.onNodeWithTag("module-content").assertExists()
        compose.onNodeWithText("fallback").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(controller.renderedInputs.isNotEmpty())
            controller.renderedInputs.forEach { (actualWindow, actualModifier) ->
                assertSame(window, actualWindow)
                assertSame(contentModifier, actualModifier)
            }
        }
    }

    // Cases: CORE-UI-11, CORE-UI-12
    @Test
    fun controllerFactoryFailureLeavesExactFailedIdentityOnFallback() {
        compose.runOnIdle {
            manager.selectModule()
            scheduler.runCurrent()
            manager.onServiceStart(ContentService(), null, checkNotNull(startIntent)) { error("Fixture factory failure") }
            scheduler.runCurrent()
        }
        val failed = assertIs<StreamingModuleManager.State.Failed>(manager.state.value)
        show(failed.instanceId)
        compose.onNodeWithText("fallback").assertExists()
        compose.onNodeWithTag("module-content").assertDoesNotExist()
        assertTrue(controllers.isEmpty())
    }

    // Cases: CORE-UI-12
    @Test
    fun failureClosesContentAtExplicitRecompositionCheckpoint() {
        val controller = open()
        show(controller.instanceId)
        compose.onNodeWithText(controller.label).assertExists()
        compose.runOnIdle {
            controller.handle.reportFailed()
            scheduler.runCurrent()
            assertFalse(controller.handle.isCurrent())
        }
        show(controller.instanceId)
        compose.onNodeWithText("fallback").assertExists()
        compose.onNodeWithText(controller.label).assertDoesNotExist()
        assertIs<StreamingModuleManager.State.Failed>(manager.state.value)
    }

    // Cases: CORE-UI-12
    @Test
    fun closingAndReplacedControllerCannotRenderForStaleIdentity() {
        val old = open()
        show(old.instanceId)
        compose.onNodeWithText(old.label).assertExists()
        compose.runOnIdle {
            manager.restartModule(old.instanceId)
            scheduler.runCurrent()
            assertFalse(old.handle.isCurrent())
        }
        show(old.instanceId)
        compose.onNodeWithText("fallback").assertExists()
        compose.onNodeWithText(old.label).assertDoesNotExist()
        compose.runOnIdle {
            old.cleanup.complete(true)
            scheduler.runCurrent()
        }
        compose.waitUntil(5_000) { scheduler.runCurrent(); startIntent != null }
        val replacement = deliver()
        assertNotEquals(old.instanceId, replacement.instanceId)
        show(old.instanceId)
        compose.onNodeWithText("fallback").assertExists()
        compose.onNodeWithText(replacement.label).assertDoesNotExist()
        show(replacement.instanceId)
        compose.onNodeWithText(replacement.label).assertExists()
        compose.onNodeWithText("fallback").assertDoesNotExist()

        compose.runOnIdle {
            manager.exit(replacement.instanceId)
            scheduler.runCurrent()
        }
        show(replacement.instanceId)
        compose.onNodeWithText("fallback").assertExists()
        compose.onNodeWithText(replacement.label).assertDoesNotExist()
    }

    private fun show(instanceId: StreamingModule.InstanceId) {
        compose.runOnIdle {
            requested.value = instanceId
            checkpoint.intValue++
        }
        compose.waitForIdle()
    }

    private fun open(): ContentController {
        compose.runOnIdle {
            manager.selectModule()
            scheduler.runCurrent()
        }
        return deliver()
    }

    private fun deliver(): ContentController {
        lateinit var controller: ContentController
        compose.runOnIdle {
            val intent = checkNotNull(startIntent).also { startIntent = null }
            // Admit at the real manager callback; Service lifecycle has its own dedicated suite.
            manager.onServiceStart(ContentService(), null, intent) { handle ->
                ContentController(handle, "controller-${controllers.size}").also {
                    controller = it
                    controllers.add(it)
                }
            }
            scheduler.runCurrent()
            assertEquals(controller.instanceId, assertIs<StreamingModuleManager.State.Running>(manager.state.value).instanceId)
        }
        return controller
    }

    private class ContentController(val handle: StreamingModule.Controller.ManagerHandle, val label: String) : StreamingModule.Controller {
        override val instanceId = handle.instanceId
        val cleanup = CompletableDeferred<Boolean>()
        val renderedInputs = mutableListOf<Pair<WindowSizeClass, Modifier>>()
        override fun startModule() = handle.reportRunning(
            StreamingModule.Status(isStreaming = false, hasConsumer = false, captureAttempt = null, error = null),
            SystemClock.uptimeMillis()
        )

        override fun requestStreamStart() = Unit
        override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, fromNotification: Boolean) = Unit
        override fun requestModuleShutdown() = Unit
        override suspend fun awaitCleanup(): Boolean = cleanup.await()
        override fun onServiceDestroyed() = Unit

        @Composable
        override fun Content(window: WindowSizeClass, modifier: Modifier) {
            SideEffect { renderedInputs.add(window to modifier) }
            Text(label, modifier)
        }
    }

    private class ContentModule : StreamingModule {
        override val id = StreamingModule.Id("content")
        override val priority = 1
        override val description = StreamingModule.Description(0, 0, 0, 0, 0, 0, 0)
        override val serviceClass = ContentService::class.java
        override val foregroundSlot = object : StreamingModuleNotifications.ForegroundSlot {
            override val id = 1
        }
    }

    private class ContentService : StreamingModuleService() {
        override fun createController(managerHandle: StreamingModule.Controller.ManagerHandle): StreamingModule.Controller =
            error("Controller admission is supplied directly to the manager in this UI suite")
    }

    private class ContentNotifications : StreamingModuleNotifications {
        override fun buildForeground(action: StreamingModuleNotifications.ForegroundAction): Notification = error("No Service lifecycle in this suite")
        override fun updateError(notificationId: Int, content: StreamingModuleNotifications.ErrorContent?) = Unit
        override fun areNotificationsEnabled(channel: StreamingModuleNotifications.Channel): Boolean = true
        override fun channelSettingsIntent(channel: StreamingModuleNotifications.Channel): Intent? = null
    }
}
