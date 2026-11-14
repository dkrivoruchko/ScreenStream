package io.screenstream.core.manager

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.core.CoreKoinTestRule
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import io.screenstream.core.module.StreamingModuleService
import io.screenstream.core.settings.StreamingSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
internal class StreamingModuleManagerTest {
    @get:Rule(order = Int.MIN_VALUE)
    val koin = CoreKoinTestRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val scheduler = TestCoroutineScheduler()
    private val queued = Channel<Unit>(Channel.UNLIMITED)

    private val testDispatcher = StandardTestDispatcher(scheduler)
    private val dispatcher = NotifyingDispatcher(testDispatcher, queued)
    private val testScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val notifications = Notifications()
    private val services = mutableListOf<ServiceController<out StreamingModuleService>>()
    private val controllers = mutableListOf<FakeController>()
    private lateinit var settings: StreamingSettingsStore
    private lateinit var manager: StreamingModuleManagerImpl
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val first = Descriptor("first", 20)
    private val second = Descriptor("second", 10)

    @Before
    fun setUp() {
        val directory = temporaryFolder.newFolder()
        settings = StreamingSettingsStore(object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        })
        runBlocking { withTimeout(5.seconds) { settings.initialize() } }
        manager = newManager(listOf(first, second))
        loadKoinModules(module {
            single { manager }
            single<StreamingModuleNotifications> { notifications }
        })
        ManagerHost.factory = { handle -> FakeController(handle).also { controllers.add(it) } }
    }

    @After
    fun tearDown() {
        notifications.onErrorUpdate = {}
        controllers.forEach { it.cleanup.complete(true) }
        manager.exit()
        scheduler.runCurrent()
        testScope.cancel()
        scheduler.runCurrent()
        services.reversed().forEach { it.destroy() }
        scheduler.runCurrent()
        ManagerHost.factory = { error("No test factory installed") }
    }

    // Cases: CORE-MGR-01
    @Test
    fun catalogIsSortedCopiedAndRejectsInvalidRegistrations() {
        val source = mutableListOf<StreamingModule>(second, first)
        val catalog = newManager(source).modules
        source.clear()
        assertEquals(listOf(first, second), catalog)
        assertFailsWith<UnsupportedOperationException> { (catalog as MutableList).clear() }
        val nonblank = first.copy(name = " module / encoded ")
        assertEquals(listOf(nonblank), newManager(listOf(nonblank)).modules)
        assertFailsWith<IllegalArgumentException> { newManager(emptyList()) }
        assertFailsWith<IllegalArgumentException> { newManager(listOf(first.copy(name = ""))) }
        assertFailsWith<IllegalArgumentException> { newManager(listOf(first.copy(name = " \t\n"))) }
        assertFailsWith<IllegalArgumentException> { newManager(listOf(first, first)) }
        assertFailsWith<IllegalArgumentException> { newManager(listOf(first.copy(slot = 0))) }
        assertFailsWith<IllegalArgumentException> { newManager(listOf(first.copy(slot = 1000))) }
        assertFailsWith<IllegalArgumentException> { newManager(listOf(first, second.copy(slot = 401))) }
        assertFailsWith<IllegalArgumentException> { newManager(listOf(first, second.copy(service = OtherManagerHost::class.java))) }
    }

    // Cases: CORE-MGR-02, CORE-MGR-03, CORE-MGR-04
    @Test
    fun unknownSelectionDoesNothingAndInitialNullUsesHighestPriority() {
        manager.restartModule(StreamingModule.InstanceId(first.id))
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.NoModule, manager.state.value)
        assertNull(nextStart())
        manager.selectModule(StreamingModule.Id("missing"))
        assertEquals(StreamingModuleManager.State.NoModule, manager.state.value)
        assertNull(shadowOf(app).nextStartedService)
        val controller = open()
        assertEquals(first.id, controller.instanceId.moduleId)
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, IDLE), manager.state.value)
        manager.selectModule(first.id)
        manager.selectModule()
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, IDLE), manager.state.value)
        assertTrue(controller.handle.isCurrent())
        assertFalse(controller.shutdown.isCompleted)
        assertNull(nextStart())
    }

    // Cases: CORE-MGR-02
    @Test
    fun initialExplicitSelectionOverridesAndPersistsDifferentSavedSelection() {
        runBlocking {
            withTimeout(5.seconds) {
                settings.updateData { copy(selectedModuleId = first.id) }
                settings.data.first { it.selectedModuleId == first.id }
            }
        }
        manager.selectModule(second.id)
        scheduler.runCurrent()
        val controller = deliver(checkNotNull(nextStart()))
        assertEquals(second.id, controller.instanceId.moduleId)
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, IDLE), manager.state.value)
        runBlocking { withTimeout(5.seconds) { settings.data.first { it.selectedModuleId == second.id } } }
    }

    // Cases: CORE-MGR-02
    @Test
    fun initialNullUsesAvailableSavedSelection() {
        runBlocking {
            withTimeout(5.seconds) {
                settings.updateData { copy(selectedModuleId = second.id) }
                settings.data.first { it.selectedModuleId == second.id }
            }
        }
        assertEquals(second.id, open().instanceId.moduleId)
    }

    // Cases: CORE-MGR-02
    @Test
    fun unavailableSavedSelectionFallsBack() {
        runBlocking {
            withTimeout(5.seconds) {
                settings.updateData { copy(selectedModuleId = StreamingModule.Id("removed")) }
                settings.data.first { it.selectedModuleId == StreamingModule.Id("removed") }
            }
        }
        assertEquals(first.id, open().instanceId.moduleId)
    }

    // Cases: CORE-MGR-03, CORE-MGR-04, CORE-MGR-05, CORE-MGR-09
    @Test
    fun startupWaitsForStatusAndDropsBusySelectionRestartAndCommands() {
        val controller = open(report = false)
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        manager.selectModule(second.id)
        manager.restartModule(controller.instanceId)
        manager.requestStreamStart(controller.instanceId)
        manager.requestRecovery(controller.instanceId, Uuid.random())
        manager.requestStreamStop(StreamingModule.CaptureAttemptId(controller.instanceId))
        scheduler.runCurrent()
        assertTrue(controller.commands.isEmpty())
        assertNull(nextStart())
        controller.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, IDLE), manager.state.value)
    }

    // Cases: CORE-MGR-05
    @Test
    fun openingUsesOrdinaryDispatchAndPromotesBeforeControllerFactory() {
        var ordinaryStarts = 0
        var foregroundStarts = 0
        val context = object : ContextWrapper(app) {
            override fun startService(service: Intent): ComponentName {
                ordinaryStarts++
                return checkNotNull(super.startService(service))
            }

            override fun startForegroundService(service: Intent): ComponentName {
                foregroundStarts++
                throw IllegalStateException("Unexpected foreground Service dispatch")
            }
        }
        manager = newManager(listOf(first, second), context)
        manager.selectModule(first.id)
        scheduler.runCurrent()
        // Production catches dispatch failures, so assert the observed boundary outside that call.
        assertEquals(1, ordinaryStarts)
        assertEquals(0, foregroundStarts)
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        assertTrue(controllers.isEmpty())
        assertTrue(notifications.foregroundActions.isEmpty())
        val start = checkNotNull(nextStart())
        assertEquals(ComponentName(app, first.serviceClass), start.component)
        val host = createHost(start)
        val foregroundAtFactory = mutableListOf<Pair<Int, Boolean>>()
        ManagerHost.factory = { handle ->
            val service = shadowOf(host.get())
            foregroundAtFactory += service.lastForegroundNotificationId to service.isLastForegroundNotificationAttached
            FakeController(handle).also { controllers.add(it) }
        }
        host.startCommand(0, 1)
        val controller = controllers.single()
        // Factory assertions must also remain outside the production startup catch.
        assertEquals(listOf(first.slot to true), foregroundAtFactory)
        assertEquals(first.id, controller.instanceId.moduleId)
        assertEquals(1, controller.startCalls)
        assertTrue(controller.handle.isCurrent())
        assertEquals(1, notifications.foregroundActions.size)
        assertIs<StreamingModuleNotifications.ForegroundAction.Exit>(notifications.foregroundActions.single())
        assertFalse(shadowOf(host.get()).isStoppedBySelf)
        controller.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, IDLE), manager.state.value)
        assertEquals(1, ordinaryStarts)
        assertEquals(0, foregroundStarts)
    }

    // Cases: CORE-MGR-05
    @Test
    fun duplicateAndMalformedServiceDeliveriesDoNotCreateAnotherController() {
        manager.selectModule()
        scheduler.runCurrent()
        val intent = checkNotNull(nextStart())
        createHost(Intent("unrelated")).startCommand(0, 1)
        createHost(Intent(intent).replaceExtras(null as android.os.Bundle?)).startCommand(0, 1)
        assertTrue(controllers.isEmpty())
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        val host = createHost(intent)
        host.startCommand(0, 1)
        val controller = controllers.single()
        assertEquals(1, controller.startCalls)
        host.startCommand(0, 4)
        assertEquals(listOf(controller), controllers)
        assertEquals(1, controller.startCalls)
        controller.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertTrue(controller.handle.isCurrent())
    }

    // Cases: CORE-MGR-06
    @Test
    fun wrongServiceRejectsBeforeFactoryAndFailsOpening() {
        manager.selectModule()
        scheduler.runCurrent()
        val intent = checkNotNull(nextStart())
        val host = Robolectric.buildService(OtherManagerHost::class.java, intent).create().also { services.add(it) }
        host.startCommand(0, 1)
        scheduler.runCurrent()
        assertTrue(controllers.isEmpty())
        assertIs<StreamingModuleManager.State.Failed>(manager.state.value)
    }

    // Cases: CORE-MGR-06
    @Test
    fun factoryFailureUsesGenericFailure() {
        ManagerHost.factory = { throw IllegalStateException("factory") }
        manager.selectModule()
        scheduler.runCurrent()
        createHost(checkNotNull(nextStart())).startCommand(0, 1)
        scheduler.runCurrent()
        assertNull(assertIs<StreamingModuleManager.State.Failed>(manager.state.value).messageResource)
    }

    // Cases: CORE-MGR-06
    @Test
    fun mismatchedControllerIsOwnedAndShutDownWithoutStarting() {
        ManagerHost.factory = { handle ->
            FakeController(handle, StreamingModule.InstanceId(handle.instanceId.moduleId))
                .also { controllers.add(it) }
        }
        open(report = false)
        scheduler.runCurrent()
        assertEquals(0, controllers.single().startCalls)
        assertTrue(controllers.single().shutdown.isCompleted)
        assertFalse(controllers.single().handle.isCurrent())
        assertIs<StreamingModuleManager.State.Failed>(manager.state.value)
    }

    // Cases: CORE-MGR-09
    @Test
    fun commandRoutingPreservesExactAttemptAndOriginAndRejectsStaleInstance() {
        val controller = open()
        val stale = StreamingModule.InstanceId(first.id)
        val token = Uuid.random()
        manager.requestStreamStart(stale)
        manager.requestRecovery(stale, token)
        manager.requestStreamStop(StreamingModule.CaptureAttemptId(stale))
        manager.requestStreamStart(controller.instanceId)
        manager.requestRecovery(controller.instanceId, token)
        val attempt = StreamingModule.CaptureAttemptId(controller.instanceId)
        manager.requestStreamStop(attempt, true)
        assertEquals(listOf<Command>(Command.Stop(attempt, true)), controller.commands)
        scheduler.runCurrent()
        assertEquals(listOf(Command.Stop(attempt, true), Command.Start, Command.Recover(token)), controller.commands)
    }

    // Cases: CORE-MGR-03, CORE-MGR-04, CORE-MGR-10, CORE-MGR-12
    @Test
    fun failureClosesAdmissionRetainsMessageAndRequiresExplicitRestart() {
        val controller = open()
        controller.handle.reportFailed(123)
        assertFalse(controller.handle.isCurrent())
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Failed(controller.instanceId, 123), manager.state.value)
        controller.handle.reportFailed(456)
        controller.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        manager.requestStreamStart(controller.instanceId)
        manager.requestRecovery(controller.instanceId, Uuid.random())
        manager.requestStreamStop(StreamingModule.CaptureAttemptId(controller.instanceId))
        scheduler.runCurrent()
        assertTrue(controller.commands.isEmpty())
        assertEquals(StreamingModuleManager.State.Failed(controller.instanceId, 123), manager.state.value)
        manager.exit(controller.instanceId)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Failed(controller.instanceId, 123), manager.state.value)
        assertNull(nextStart())
        manager.selectModule(first.id)
        scheduler.runCurrent()
        assertNull(nextStart())
        manager.restartModule(StreamingModule.InstanceId(first.id))
        assertIs<StreamingModuleManager.State.Failed>(manager.state.value)
        settleCleanup(controller)
        assertEquals(StreamingModuleManager.State.Failed(controller.instanceId, 123), manager.state.value)
        manager.restartModule(controller.instanceId)
        scheduler.runCurrent()
        val replacement = deliver(checkNotNull(nextStart()))
        assertNotEquals(controller.instanceId, replacement.instanceId)
    }

    // Cases: CORE-MGR-09, CORE-MGR-11, CORE-MGR-12
    @Test
    fun replacementRevokesOldHandleAndQueuedCommandsBeforeCleanupCompletes() {
        val old = open()
        manager.requestStreamStart(old.instanceId)
        manager.requestRecovery(old.instanceId, Uuid.random())
        manager.selectModule(second.id)
        assertFalse(old.handle.isCurrent())
        assertTrue(old.shutdown.isCompleted)
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        scheduler.runCurrent()
        assertTrue(old.commands.isEmpty())
        assertNull(nextStart())
        settleCleanup(old)
        val replacement = deliver(checkNotNull(nextStart()))
        old.handle.reportRunning(IDLE.copy(isStreaming = true), SystemClock.uptimeMillis())
        old.handle.reportFailed(456)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Running(replacement.instanceId, IDLE), manager.state.value)
        manager.exit(old.instanceId)
        assertTrue(replacement.handle.isCurrent())
    }

    // Cases: CORE-MGR-12
    @Test
    fun exitRevokesAdmissionImmediatelyAndStaysExitingAfterCleanupThenReopens() {
        val old = open()
        manager.exit(old.instanceId)
        assertFalse(old.handle.isCurrent())
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        manager.exit()
        settleCleanup(old)
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        val replacement = open()
        assertNotEquals(old.instanceId, replacement.instanceId)
    }

    // Cases: CORE-MGR-07
    @Test
    fun elapsedStartupDeadlineExpiresAfterDeepSleepBeforeLateServiceDelivery() {
        manager.selectModule()
        scheduler.runCurrent()
        val intent = checkNotNull(nextStart())
        val uptime = SystemClock.uptimeMillis()
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(3001))
        assertEquals(uptime, SystemClock.uptimeMillis())
        val host = createHost(intent)
        host.startCommand(0, 1)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(3001.milliseconds)
        scheduler.runCurrent()
        assertTrue(controllers.isEmpty())
        assertTrue(notifications.foregroundActions.isEmpty())
        val service = shadowOf(host.get())
        assertNull(service.lastForegroundNotification)
        assertTrue(service.isStoppedBySelf)
        assertEquals(1, service.stopSelfResultId)
        assertIs<StreamingModuleManager.State.Failed>(manager.state.value)
    }

    // Cases: CORE-MGR-08, CORE-MGR-09, CORE-MGR-15
    @Test
    fun heartbeatUsesUptimeIgnoresOlderReportsAndRecoversSameInstance() {
        val controller = open()
        val initial = SystemClock.uptimeMillis()
        notifications.errorUpdates.clear()
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(10000))
        scheduler.advanceTimeBy(3001.milliseconds)
        scheduler.runCurrent()
        assertIs<StreamingModuleManager.State.Running>(manager.state.value)
        ShadowSystemClock.advanceBy(Duration.ofMillis(3000))
        scheduler.advanceTimeBy(3000.milliseconds)
        scheduler.runCurrent()
        assertIs<StreamingModuleManager.State.Running>(manager.state.value)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        scheduler.advanceTimeBy(1.milliseconds)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Unresponsive(controller.instanceId, IDLE), manager.state.value)
        assertEquals(
            listOf<Pair<Int, StreamingModuleNotifications.ErrorContent?>>(StreamingModuleNotifications.NOTIFICATION_ID_MODULE_ERROR to StreamingModuleNotifications.ErrorContent.ModuleProblem()),
            notifications.errorUpdates,
        )
        manager.requestStreamStart(controller.instanceId)
        scheduler.runCurrent()
        assertEquals(listOf<Command>(Command.Start), controller.commands)
        val fresh = IDLE.copy(hasConsumer = true)
        controller.handle.reportRunning(fresh, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        controller.handle.reportRunning(IDLE, initial)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, fresh), manager.state.value)
        assertEquals(
            listOf<Pair<Int, StreamingModuleNotifications.ErrorContent?>>(
                StreamingModuleNotifications.NOTIFICATION_ID_MODULE_ERROR to StreamingModuleNotifications.ErrorContent.ModuleProblem(),
                StreamingModuleNotifications.NOTIFICATION_ID_MODULE_ERROR to null,
            ),
            notifications.errorUpdates,
        )
    }

    // Cases: CORE-MGR-07, CORE-MGR-08
    @Test
    fun staleFirstHeartbeatSettlesStartupAsUnresponsiveAndFreshProgressRestoresIt() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(4000))
        val controller = open(report = false)
        val staleStatus = IDLE.copy(hasConsumer = true)
        controller.handle.reportRunning(staleStatus, SystemClock.uptimeMillis() - 3001)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Unresponsive(controller.instanceId, staleStatus), manager.state.value)
        assertTrue(controller.handle.isCurrent())
        assertFalse(controller.shutdown.isCompleted)
        assertNull(nextStart())

        val freshStatus = staleStatus.copy(isStreaming = true)
        controller.handle.reportRunning(freshStatus, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, freshStatus), manager.state.value)
        assertTrue(controller.handle.isCurrent())
    }

    // Cases: CORE-MGR-07
    @Test
    fun firstStatusAtDeadlineIsAccepted() {
        val controller = open(report = false)
        ShadowSystemClock.advanceBy(Duration.ofMillis(3000))
        controller.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, IDLE), manager.state.value)
    }

    // Cases: CORE-MGR-07
    @Test
    fun lateFirstStatusCannotRestoreExpiredStartupAdmission() {
        val controller = open(report = false)
        ShadowSystemClock.advanceBy(Duration.ofMillis(3001))
        controller.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        scheduler.advanceTimeBy(3001.milliseconds)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Failed(controller.instanceId), manager.state.value)
        assertFalse(controller.handle.isCurrent())
        assertTrue(controller.shutdown.isCompleted)
    }

    // Cases: CORE-MGR-11
    @Test
    fun shutdownDeadlineOpensReplacementWhileOldCleanupRemainsOwned() {
        val replacementModule = second.copy(slot = 401, service = OtherManagerHost::class.java)
        manager = newManager(listOf(first, replacementModule))
        val old = open()
        manager.selectModule(replacementModule.id)
        scheduler.runCurrent()
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(3000))
        scheduler.advanceTimeBy(3000.milliseconds)
        scheduler.runCurrent()
        assertNull(nextStart())
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(1))
        scheduler.advanceTimeBy(1.milliseconds)
        scheduler.runCurrent()
        val start = checkNotNull(nextStart())
        assertEquals(ComponentName(app, OtherManagerHost::class.java), start.component)
        val replacementHost = Robolectric.buildService(OtherManagerHost::class.java, start).create().also { services.add(it) }
        replacementHost.startCommand(0, 1)
        val replacement = controllers.last()
        replacement.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertEquals(replacementModule.id, replacement.instanceId.moduleId)
        assertFalse(old.cleanup.isCompleted)
        assertFalse(old.handle.isCurrent())
        settleCleanup(old)
        assertEquals(StreamingModuleManager.State.Running(replacement.instanceId, IDLE), manager.state.value)
    }

    // Cases: CORE-MGR-04, CORE-MGR-10, CORE-MGR-11
    @Test
    fun failedInstanceRestartUsesOriginalShutdownDeadlineWithoutExtendingCleanupWait() {
        var ordinaryStarts = 0
        var foregroundStarts = 0
        val context = object : ContextWrapper(app) {
            override fun startService(service: Intent): ComponentName {
                ordinaryStarts++
                return checkNotNull(super.startService(service))
            }

            override fun startForegroundService(service: Intent): ComponentName {
                foregroundStarts++
                error("Expected ordinary Service dispatch")
            }
        }
        manager = newManager(listOf(first, second), context)
        val old = open()
        old.handle.reportFailed(123)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Failed(old.instanceId, 123), manager.state.value)
        assertFalse(old.handle.isCurrent())
        assertTrue(old.shutdown.isCompleted)
        assertFalse(old.cleanup.isCompleted)

        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(1500))
        scheduler.advanceTimeBy(1500.milliseconds)
        scheduler.runCurrent()
        manager.restartModule(old.instanceId)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        assertEquals(1, ordinaryStarts)
        assertNull(nextStart())

        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(1500))
        scheduler.advanceTimeBy(1500.milliseconds)
        scheduler.runCurrent()
        assertEquals(1, ordinaryStarts)
        assertNull(nextStart())
        assertFalse(old.cleanup.isCompleted)

        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(1))
        scheduler.advanceTimeBy(1.milliseconds)
        scheduler.runCurrent()
        assertEquals(ComponentName(app, first.serviceClass), checkNotNull(nextStart()).component)
        assertEquals(2, ordinaryStarts)
        assertEquals(0, foregroundStarts)
        assertFalse(old.cleanup.isCompleted)
        assertFalse(old.handle.isCurrent())
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
    }

    // Cases: CORE-MGR-13
    @Test
    fun exitFromFactoryRetainsReturnedControllerForCleanupWithoutStartingIt() {
        ManagerHost.factory = { handle ->
            manager.exit(handle.instanceId)
            FakeController(handle).also { controllers.add(it) }
        }
        val controller = open(report = false)
        assertEquals(0, controller.startCalls)
        assertTrue(controller.shutdown.isCompleted)
        assertFalse(controller.handle.isCurrent())
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        settleCleanup(controller)
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
    }

    // Cases: CORE-MGR-12, CORE-MGR-13
    @Test
    fun exitBeforeServiceDeliveryPreventsFactory() {
        manager.selectModule()
        scheduler.runCurrent()
        val stale = checkNotNull(nextStart())
        manager.exit()
        val host = createHost(stale)
        host.startCommand(0, 1)
        scheduler.runCurrent()
        assertTrue(controllers.isEmpty())
        assertTrue(notifications.foregroundActions.isEmpty())
        val service = shadowOf(host.get())
        assertNull(service.lastForegroundNotification)
        assertTrue(service.isStoppedBySelf)
        assertEquals(1, service.stopSelfResultId)
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
    }

    // Cases: CORE-MGR-05, CORE-MGR-12, CORE-MGR-13
    // Supplied Android conditional-stop result; the default Robolectric shadow always returns true.
    @Test
    @Config(shadows = [PendingStartServiceShadow::class])
    fun rejectedOlderStartRetainsHostForNewerPendingDelivery() {
        manager.selectModule(first.id)
        scheduler.runCurrent()
        val stale = checkNotNull(nextStart())
        manager.exit()
        // Let the bounded Exit settle even if its process-owned cleanup report arrives later.
        ShadowSystemClock.advanceBy(Duration.ofMillis(3001))
        scheduler.advanceTimeBy(3001.milliseconds)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        manager.selectModule(first.id)
        scheduler.runCurrent()
        val current = checkNotNull(nextStart())
        val host = createHost(stale)
        val service = assertIs<PendingStartServiceShadow>(shadowOf(host.get()))
        // Both starts are accepted before Android delivers the older one to this fresh host.
        service.latestStartId = 2
        host.startCommand(0, 1)
        assertEquals(listOf(1), service.conditionalStopIds)
        assertFalse(service.isStoppedBySelf)
        assertNull(service.lastForegroundNotification)
        assertTrue(notifications.foregroundActions.isEmpty())
        assertTrue(controllers.isEmpty())
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        // Robolectric 4.17 requires withIntent for a different delivery to the same Service.
        host.withIntent(current).startCommand(0, 2)
        val controller = controllers.single()
        assertEquals(first.id, controller.instanceId.moduleId)
        assertEquals(1, controller.startCalls)
        assertTrue(controller.handle.isCurrent())
        assertEquals(first.slot, service.lastForegroundNotificationId)
        assertTrue(service.isLastForegroundNotificationAttached)
        assertFalse(service.isStoppedBySelf)
        assertEquals(listOf(1), service.conditionalStopIds)
        controller.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, IDLE), manager.state.value)
    }


    // Cases: CORE-MGR-02, CORE-MGR-12
    @Test
    fun explicitSelectionPersistsAndNullAfterExitUsesProcessSelection() {
        val controller = open()
        manager.selectModule(second.id)
        settleCleanup(controller)
        val selected = deliver(checkNotNull(nextStart()))
        assertEquals(second.id, selected.instanceId.moduleId)
        runBlocking { withTimeout(5.seconds) { settings.data.first { it.selectedModuleId == second.id } } }
        runBlocking {
            withTimeout(5.seconds) {
                settings.updateData { copy(selectedModuleId = first.id) }
                settings.data.first { it.selectedModuleId == first.id }
            }
        }
        manager.exit()
        settleCleanup(selected)
        assertEquals(second.id, open().instanceId.moduleId)
    }

    // Cases: CORE-MGR-14
    @Test
    fun synchronousStatusDuringStartIsAccepted() {
        ManagerHost.factory = { handle ->
            FakeController(handle).also {
                it.onStart = { handle.reportRunning(IDLE, SystemClock.uptimeMillis()) }
                controllers.add(it)
            }
        }
        val controller = open(report = false)
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, IDLE), manager.state.value)
    }

    // Cases: CORE-MGR-06, CORE-MGR-14
    @Test
    fun failureDuringStartClosesAdmissionBeforeFirstStatusAndUsesGenericMessage() {
        ManagerHost.factory = { handle ->
            FakeController(handle).also {
                it.onStart = { handle.reportFailed(123) }
                controllers.add(it)
            }
        }
        val controller = open(report = false)
        assertEquals(1, controller.startCalls)
        assertFalse(controller.handle.isCurrent())
        assertTrue(controller.shutdown.isCompleted)
        assertEquals(StreamingModuleManager.State.Failed(controller.instanceId), manager.state.value)
    }

    // Cases: CORE-MGR-06
    @Test
    fun startExceptionRetainsControllerForCleanupAndUsesGenericFailure() {
        ManagerHost.factory = { handle ->
            FakeController(handle).also {
                it.onStart = { throw IllegalStateException("start") }
                controllers.add(it)
            }
        }
        val controller = open(report = false)
        assertTrue(controller.shutdown.isCompleted)
        assertFalse(controller.handle.isCurrent())
        assertEquals(StreamingModuleManager.State.Failed(controller.instanceId), manager.state.value)
    }

    // Cases: CORE-MGR-07
    @Test
    fun firstStatusQueuedBeforeExpiryMustBeAppliedBeforeElapsedDeadline() {
        val controller = open(report = false)
        ShadowSystemClock.advanceBy(Duration.ofMillis(2999))
        controller.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        assertTrue(controller.handle.isCurrent())
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(2))
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Failed(controller.instanceId), manager.state.value)
        assertFalse(controller.handle.isCurrent())
        assertTrue(controller.shutdown.isCompleted)
    }

    // Cases: CORE-MGR-11
    @Test
    fun successorDeliveredToServiceWithOldControllerFailsWithoutTakingOldOwnership() {
        val old = open()
        val originalService = services.single()
        val oldIdentity = old.instanceId
        manager.restartModule(oldIdentity)
        scheduler.runCurrent()
        assertTrue(old.shutdown.isCompleted)
        assertFalse(old.handle.isCurrent())
        assertFalse(old.cleanup.isCompleted)
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(3001))
        scheduler.advanceTimeBy(3001.milliseconds)
        scheduler.runCurrent()
        val successorStart = checkNotNull(nextStart())
        // Keep the original Service owner while delivering a different start Intent.
        originalService.withIntent(successorStart).startCommand(0, 2)
        scheduler.runCurrent()
        val failed = assertIs<StreamingModuleManager.State.Failed>(manager.state.value)
        assertNotEquals(oldIdentity, failed.instanceId)
        assertEquals(oldIdentity.moduleId, failed.instanceId.moduleId)
        assertNull(failed.messageResource)
        assertEquals(listOf(old), controllers)
        assertEquals(oldIdentity, old.handle.instanceId)
        assertFalse(old.cleanup.isCompleted)
        old.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertEquals(failed, manager.state.value)
        settleCleanup(old)
        assertEquals(failed, manager.state.value)
    }

    // Cases: CORE-MGR-11, CORE-MGR-13
    @Test
    fun replacementContinuesWithoutDeadlineWhenCleanupReturnsFalse() {
        assertUnsuccessfulCleanupSettles(exit = false)
    }

    // Cases: CORE-MGR-11, CORE-MGR-13
    @Test
    fun replacementContinuesWithoutDeadlineWhenCleanupThrows() {
        assertUnsuccessfulCleanupSettles(exit = false, failure = IllegalStateException("cleanup"))
    }

    // Cases: CORE-MGR-12, CORE-MGR-13
    @Test
    fun exitSettlesWithoutDeadlineWhenCleanupReturnsFalse() {
        assertUnsuccessfulCleanupSettles(exit = true)
    }

    // Cases: CORE-MGR-12, CORE-MGR-13
    @Test
    fun exitSettlesWithoutDeadlineWhenCleanupThrows() {
        assertUnsuccessfulCleanupSettles(exit = true, failure = IllegalStateException("cleanup"))
    }

    // Cases: CORE-MGR-11, CORE-MGR-13
    @Test
    fun throwingShutdownRequestStillWaitsForHeldCleanup() {
        val old = open()
        var shutdownCalls = 0
        old.onShutdown = {
            shutdownCalls++
            throw IllegalStateException("shutdown")
        }
        manager.selectModule(second.id)
        awaitCheckpoint(old.cleanupEntered)
        assertTrue(shutdownCalls > 0)
        assertFalse(old.handle.isCurrent())
        assertFalse(old.cleanup.isCompleted)
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        assertNull(nextStart())
        settleCleanup(old)
        val replacement = deliver(checkNotNull(nextStart()))
        assertEquals(second.id, replacement.instanceId.moduleId)
    }

    // Cases: CORE-MGR-11, CORE-MGR-14
    @Test
    fun synchronousReportsInsideShutdownCannotChangeReplacement() {
        val old = open()
        val admissionAtShutdown = mutableListOf<Boolean>()
        var reported = false
        old.onShutdown = {
            admissionAtShutdown.add(old.handle.isCurrent())
            old.handle.reportRunning(IDLE.copy(isStreaming = true), SystemClock.uptimeMillis())
            old.handle.reportFailed(123)
            reported = true
        }
        manager.selectModule(second.id)
        assertTrue(reported)
        scheduler.runCurrent()
        assertFalse(old.handle.isCurrent())
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        assertNull(nextStart())
        settleCleanup(old)
        val replacement = deliver(checkNotNull(nextStart()))
        assertTrue(admissionAtShutdown.isNotEmpty())
        assertTrue(admissionAtShutdown.none { it })
        assertEquals(StreamingModuleManager.State.Running(replacement.instanceId, IDLE), manager.state.value)
    }

    // Cases: CORE-MGR-02, CORE-MGR-03, CORE-MGR-04, CORE-MGR-09
    @Test
    fun competingCommandsDuringSettingsInitializationKeepOriginalSelection() {
        val settingsScheduler = holdSettingsInitialization()
        val initialized = testScope.async { settings.initialize() }
        manager.selectModule(first.id)
        scheduler.runCurrent()
        assertFalse(initialized.isCompleted)
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        assertNull(nextStart())
        val unavailable = StreamingModule.InstanceId(first.id)
        manager.selectModule(second.id)
        manager.restartModule(unavailable)
        manager.requestStreamStart(unavailable)
        manager.requestStreamStop(StreamingModule.CaptureAttemptId(unavailable))
        manager.requestRecovery(unavailable, Uuid.random())
        scheduler.runCurrent()
        assertTrue(controllers.isEmpty())
        assertNull(nextStart())
        awaitCheckpoint(initialized) { settingsScheduler.runCurrent() }
        val controller = deliver(checkNotNull(nextStart()))
        assertEquals(first.id, controller.instanceId.moduleId)
        assertTrue(controller.commands.isEmpty())
        assertNull(nextStart())
    }

    // Cases: CORE-MGR-02, CORE-MGR-12
    @Test
    fun exitDuringSettingsInitializationCancelsOpeningAfterSettingsBecomeReady() {
        val settingsScheduler = holdSettingsInitialization()
        val initialized = testScope.async { settings.initialize() }
        manager.selectModule(first.id)
        scheduler.runCurrent()
        assertFalse(initialized.isCompleted)
        manager.exit()
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        scheduler.runCurrent()
        awaitCheckpoint(initialized) { settingsScheduler.runCurrent() }
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        assertTrue(controllers.isEmpty())
        assertNull(nextStart())
        assertEquals(first.id, open().instanceId.moduleId)
    }

    // Cases: CORE-MGR-11, CORE-MGR-12
    @Test
    fun exitDuringHeldReplacementPreventsItsServiceStart() {
        val old = open()
        manager.selectModule(second.id)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        assertNull(nextStart())
        manager.exit()
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        settleCleanup(old)
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        assertNull(nextStart())
        assertEquals(second.id, open().instanceId.moduleId)
    }

    // Cases: CORE-MGR-03, CORE-MGR-09, CORE-MGR-12
    @Test
    fun heldExitDropsCommandsAndRepeatedExitDoesNotExtendOriginalDeadline() {
        val old = open()
        manager.exit()
        scheduler.runCurrent()
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(2000))
        scheduler.advanceTimeBy(2000.milliseconds)
        scheduler.runCurrent()
        manager.exit()
        manager.selectModule(second.id)
        manager.requestStreamStart(old.instanceId)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        assertTrue(old.commands.isEmpty())
        assertNull(nextStart())
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(1000))
        scheduler.advanceTimeBy(1000.milliseconds)
        scheduler.runCurrent()
        manager.selectModule(second.id)
        scheduler.runCurrent()
        assertNull(nextStart())
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(1))
        scheduler.advanceTimeBy(1.milliseconds)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        assertFalse(old.cleanup.isCompleted)
        manager.selectModule(second.id)
        scheduler.runCurrent()
        val start = checkNotNull(nextStart())
        assertEquals(ComponentName(app, second.serviceClass), start.component)
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        assertEquals(listOf(old), controllers)
        settleCleanup(old)
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        assertNull(nextStart())
    }

    // Cases: CORE-MGR-09
    @Test
    fun throwingCommandsPreserveAdmissionAndLaterCommandsStillRoute() {
        val controller = open()
        val running = StreamingModuleManager.State.Running(controller.instanceId, IDLE)
        val attempt = StreamingModule.CaptureAttemptId(controller.instanceId)
        val token = Uuid.random()
        var failedCalls = 0
        val fail: () -> Unit = {
            failedCalls++
            throw IllegalStateException("command")
        }
        val commandThread = Thread.currentThread()
        val previousHandler = commandThread.uncaughtExceptionHandler
        val uncaught = mutableListOf<Throwable>()
        commandThread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> uncaught.add(failure) }
        try {
            controller.onStartRequest = fail
            controller.onStopRequest = fail
            controller.onRecovery = fail
            manager.requestStreamStart(controller.instanceId)
            scheduler.runCurrent()
            assertTrue(uncaught.isEmpty(), "Unexpected uncaught command failures: $uncaught")
            assertEquals(1, failedCalls)
            assertEquals(running, manager.state.value)
            assertTrue(controller.handle.isCurrent())
            manager.requestStreamStop(attempt, true)
            assertEquals(2, failedCalls)
            assertEquals(running, manager.state.value)
            assertTrue(controller.handle.isCurrent())
            manager.requestRecovery(controller.instanceId, token)
            scheduler.runCurrent()
            assertTrue(uncaught.isEmpty(), "Unexpected uncaught command failures: $uncaught")
            assertEquals(3, failedCalls)
            assertEquals(running, manager.state.value)
            assertTrue(controller.handle.isCurrent())
            controller.onStartRequest = {}
            controller.onStopRequest = {}
            controller.onRecovery = {}
            manager.requestStreamStart(controller.instanceId)
            manager.requestStreamStop(attempt, true)
            manager.requestRecovery(controller.instanceId, token)
            assertEquals(listOf<Command>(Command.Stop(attempt, true)), controller.commands)
            scheduler.runCurrent()
            assertTrue(uncaught.isEmpty(), "Unexpected uncaught command failures: $uncaught")
            assertEquals(listOf(Command.Stop(attempt, true), Command.Start, Command.Recover(token)), controller.commands)
            assertEquals(running, manager.state.value)
            assertTrue(controller.handle.isCurrent())
        } finally {
            commandThread.uncaughtExceptionHandler = previousHandler
        }
    }

    // Cases: CORE-MGR-04, CORE-MGR-06
    // Supplied defensive dispatch failure, not qualification of Android ordinary-start restrictions.
    @Test
    fun platformStartDispatchFailureSettlesOpeningAndExactRestartCanSucceed() {
        var rejectStart = true
        var ordinaryStarts = 0
        var foregroundStarts = 0
        val context = object : ContextWrapper(app) {
            override fun startService(service: Intent): ComponentName {
                ordinaryStarts++
                if (rejectStart) throw IllegalStateException("Service start rejected")
                return checkNotNull(super.startService(service))
            }

            override fun startForegroundService(service: Intent): ComponentName {
                foregroundStarts++
                throw IllegalStateException("Unexpected foreground Service dispatch")
            }
        }
        manager = newManager(listOf(first, second), context)
        val before = SystemClock.elapsedRealtime()
        manager.selectModule(first.id)
        scheduler.runCurrent()
        val failed = assertIs<StreamingModuleManager.State.Failed>(manager.state.value)
        assertEquals(1, ordinaryStarts)
        assertEquals(0, foregroundStarts)
        assertEquals(first.id, failed.instanceId.moduleId)
        assertNull(failed.messageResource)
        assertTrue(controllers.isEmpty())
        assertNull(nextStart())
        assertEquals(before, SystemClock.elapsedRealtime())
        rejectStart = false
        manager.restartModule(failed.instanceId)
        val restarted = deliver(awaitStartedService())
        assertNotEquals(failed.instanceId, restarted.instanceId)
        assertEquals(first.id, restarted.instanceId.moduleId)
        assertEquals(StreamingModuleManager.State.Running(restarted.instanceId, IDLE), manager.state.value)
        assertEquals(2, ordinaryStarts)
        assertEquals(0, foregroundStarts)
        assertEquals(before, SystemClock.elapsedRealtime())
    }

    // Cases: CORE-MGR-14
    @Test
    fun notificationProviderCanExitDuringPublicationAndReturnsToExiting() {
        val controller = open()
        val updates = mutableListOf<StreamingModuleNotifications.ErrorContent?>()
        var exitReturned = false
        notifications.onErrorUpdate = { content ->
            updates.add(content)
            if (content != null) {
                manager.exit()
                exitReturned = true
            }
        }
        controller.handle.reportRunning(
            IDLE.copy(error = StreamingModule.Status.Error("operation")),
            SystemClock.uptimeMillis(),
        )
        scheduler.runCurrent()
        assertTrue(exitReturned)
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        assertFalse(controller.handle.isCurrent())
        assertTrue(controller.shutdown.isCompleted)
        assertTrue(updates.any { it != null })
        assertNull(updates.last())
        settleCleanup(controller)
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        assertNull(nextStart())
    }

    // Cases: CORE-MGR-15
    @Test
    fun warningEpisodeSuppressesDetailAndHealthChangesUntilRecovery() {
        val controller = open()
        notifications.errorUpdates.clear()
        fun report(error: String?) {
            controller.handle.reportRunning(IDLE.copy(error = error?.let { StreamingModule.Status.Error(it) }), SystemClock.uptimeMillis())
            scheduler.runCurrent()
        }
        report("first")
        report("changed")
        ShadowSystemClock.advanceBy(Duration.ofMillis(3001))
        scheduler.advanceTimeBy(3001.milliseconds)
        scheduler.runCurrent()
        assertIs<StreamingModuleManager.State.Unresponsive>(manager.state.value)
        assertEquals(
            listOf<Pair<Int, StreamingModuleNotifications.ErrorContent?>>(1000 to StreamingModuleNotifications.ErrorContent.ModuleProblem()),
            notifications.errorUpdates
        )
        report(null)
        report("new episode")
        assertEquals(
            listOf<Pair<Int, StreamingModuleNotifications.ErrorContent?>>(
                1000 to StreamingModuleNotifications.ErrorContent.ModuleProblem(), 1000 to null,
                1000 to StreamingModuleNotifications.ErrorContent.ModuleProblem()
            ),
            notifications.errorUpdates,
        )
    }

    // Cases: CORE-MGR-15
    @Test
    fun terminalProblemReplacesWarningWithItsMessageResource() {
        val controller = open()
        notifications.errorUpdates.clear()
        controller.handle.reportRunning(IDLE.copy(error = StreamingModule.Status.Error("warning")), SystemClock.uptimeMillis())
        scheduler.runCurrent()
        controller.handle.reportFailed(123)
        scheduler.runCurrent()
        assertEquals(
            listOf<Pair<Int, StreamingModuleNotifications.ErrorContent?>>(
                1000 to StreamingModuleNotifications.ErrorContent.ModuleProblem(),
                1000 to StreamingModuleNotifications.ErrorContent.ModuleProblem(123)
            ),
            notifications.errorUpdates,
        )
        assertEquals(StreamingModuleManager.State.Failed(controller.instanceId, 123), manager.state.value)
    }

    // Cases: CORE-MGR-15
    @Test
    fun throwingProblemProviderIsNotRetriedUntilAnotherEpisode() {
        val controller = open()
        notifications.errorUpdates.clear()
        notifications.onErrorUpdate = { if (it != null) throw IllegalStateException("delivery") }
        val error = IDLE.copy(error = StreamingModule.Status.Error("warning"))
        controller.handle.reportRunning(error, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, error), manager.state.value)
        assertTrue(controller.handle.isCurrent())
        controller.handle.reportRunning(error.copy(error = StreamingModule.Status.Error("changed")), SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertEquals(1, notifications.errorUpdates.size)
        controller.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        notifications.onErrorUpdate = {}
        controller.handle.reportRunning(error, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertEquals(
            listOf<Pair<Int, StreamingModuleNotifications.ErrorContent?>>(
                1000 to StreamingModuleNotifications.ErrorContent.ModuleProblem(), 1000 to null,
                1000 to StreamingModuleNotifications.ErrorContent.ModuleProblem()
            ),
            notifications.errorUpdates,
        )
    }

    // Cases: CORE-MGR-15
    @Test
    fun replacementAndExitClearTheOriginalProblem() {
        val original = open()
        original.handle.reportRunning(IDLE.copy(error = StreamingModule.Status.Error("old")), SystemClock.uptimeMillis())
        scheduler.runCurrent()
        notifications.errorUpdates.clear()
        manager.selectModule(second.id)
        assertEquals(listOf<Pair<Int, StreamingModuleNotifications.ErrorContent?>>(1000 to null), notifications.errorUpdates)
        settleCleanup(original)
        val replacement = deliver(checkNotNull(nextStart()))
        replacement.handle.reportRunning(IDLE.copy(error = StreamingModule.Status.Error("new")), SystemClock.uptimeMillis())
        scheduler.runCurrent()
        notifications.errorUpdates.clear()
        manager.exit()
        assertEquals(listOf<Pair<Int, StreamingModuleNotifications.ErrorContent?>>(1000 to null), notifications.errorUpdates)
    }

    // Cases: CORE-MGR-05, CORE-MGR-06
    @Test
    fun waitingNotificationConstructionFailureCanBeRetriedExplicitly() {
        assertWaitingForegroundFailure(buildFailure = true)
    }

    // Cases: CORE-MGR-05, CORE-MGR-06
    @Test
    fun waitingPlatformPromotionFailureCanBeRetriedExplicitly() {
        assertWaitingForegroundFailure(buildFailure = false)
    }

    private fun assertWaitingForegroundFailure(buildFailure: Boolean) {
        manager.selectModule(first.id)
        scheduler.runCurrent()
        val host = createHost(checkNotNull(nextStart()))
        val failure = IllegalStateException("waiting foreground")
        if (buildFailure) notifications.buildFailure = failure else shadowOf(host.get()).setThrowInStartForeground(failure)
        host.startCommand(0, 1)
        scheduler.runCurrent()
        val failed = assertIs<StreamingModuleManager.State.Failed>(manager.state.value)
        assertEquals(first.id, failed.instanceId.moduleId)
        assertNull(failed.messageResource)
        assertTrue(controllers.isEmpty())
        assertTrue(shadowOf(host.get()).isStoppedBySelf)
        assertFalse(host.get().enterCaptureForeground(StreamingModule.CaptureAttemptId(failed.instanceId), requiresMicrophoneType = false))
        notifications.buildFailure = null
        manager.restartModule(failed.instanceId)
        scheduler.runCurrent()
        val restarted = deliver(awaitStartedService())
        assertNotEquals(failed.instanceId, restarted.instanceId)
        assertEquals(first.id, restarted.instanceId.moduleId)
        assertEquals(StreamingModuleManager.State.Running(restarted.instanceId, IDLE), manager.state.value)
    }

    // Cases: CORE-MGR-12
    @Test
    fun exactExitDuringStartupClosesAdmissionBeforeLateStatus() {
        val controller = open(report = false)
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        manager.exit(controller.instanceId)
        assertFalse(controller.handle.isCurrent())
        assertTrue(controller.shutdown.isCompleted)
        controller.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        settleCleanup(controller)
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
        assertNull(nextStart())
    }

    // Cases: CORE-MGR-08, CORE-MGR-09
    @Test
    fun unresponsiveControllerReceivesExactStopAndRecoveryWithoutNotificationOrigin() {
        val controller = open()
        ShadowSystemClock.advanceBy(Duration.ofMillis(3001))
        scheduler.advanceTimeBy(3001.milliseconds)
        scheduler.runCurrent()
        assertIs<StreamingModuleManager.State.Unresponsive>(manager.state.value)
        val attempt = StreamingModule.CaptureAttemptId(controller.instanceId)
        val token = Uuid.random()
        manager.requestStreamStop(attempt)
        manager.requestRecovery(controller.instanceId, token)
        assertEquals(listOf<Command>(Command.Stop(attempt, false)), controller.commands)
        scheduler.runCurrent()
        assertEquals(listOf(Command.Stop(attempt, false), Command.Recover(token)), controller.commands)
        assertTrue(controller.handle.isCurrent())
    }

    private fun newManager(descriptors: List<StreamingModule>, context: Context = app): StreamingModuleManagerImpl =
        StreamingModuleManagerImpl(descriptors, context, settings, notifications, dispatcher)

    private fun nextStart(): Intent? = shadowOf(app).nextStartedService
    private fun createHost(intent: Intent): ServiceController<ManagerHost> =
        Robolectric.buildService(ManagerHost::class.java, intent).create().also { services.add(it) }

    private fun open(report: Boolean = true): FakeController {
        manager.selectModule()
        scheduler.runCurrent()
        return deliver(checkNotNull(nextStart()), report)
    }

    private fun deliver(intent: Intent, report: Boolean = true): FakeController {
        createHost(intent).startCommand(0, 1)
        val controller = controllers.last()
        if (report) controller.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        return controller
    }

    private fun holdSettingsInitialization(): TestCoroutineScheduler {
        val settingsScheduler = TestCoroutineScheduler()
        val directory = temporaryFolder.newFolder()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val settingsDispatcher = NotifyingDispatcher(StandardTestDispatcher(settingsScheduler), queued)
        settings = StreamingSettingsStore(context, settingsDispatcher)
        manager = newManager(listOf(first, second))
        return settingsScheduler
    }

    private fun assertUnsuccessfulCleanupSettles(exit: Boolean, failure: Exception? = null) {
        val old = open()
        val before = SystemClock.elapsedRealtime()
        if (exit) manager.exit() else manager.selectModule(second.id)
        scheduler.runCurrent()
        assertFalse(old.handle.isCurrent())
        assertFalse(old.cleanup.isCompleted)
        assertNull(nextStart())
        settleCleanup(old, completed = false, failure = failure)
        assertEquals(before, SystemClock.elapsedRealtime())
        val replacement = if (exit) {
            assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
            assertNull(nextStart())
            open()
        } else deliver(checkNotNull(nextStart()))
        assertEquals(if (exit) first.id else second.id, replacement.instanceId.moduleId)
        assertEquals(StreamingModuleManager.State.Running(replacement.instanceId, IDLE), manager.state.value)
    }

    private fun awaitStartedService(): Intent {
        var intent: Intent? = null
        runBlocking {
            withTimeout(5.seconds) {
                while (intent == null) {
                    scheduler.runCurrent()
                    intent = nextStart()
                    if (intent == null) queued.receive()
                }
            }
        }
        return checkNotNull(intent)
    }

    private fun <T> awaitCheckpoint(signal: Deferred<T>, runOtherTasks: () -> Unit = {}): T {
        val result = runBlocking {
            withTimeout(5.seconds) {
                while (!signal.isCompleted) {
                    runOtherTasks()
                    scheduler.runCurrent()
                    if (!signal.isCompleted) {
                        select {
                            signal.onAwait {}
                            queued.onReceive {}
                        }
                    }
                }
                signal.await()
            }
        }
        scheduler.runCurrent()
        return result
    }

    private fun settleCleanup(controller: FakeController, completed: Boolean = true, failure: Exception? = null) {
        if (failure == null) controller.cleanup.complete(completed)
        else controller.cleanup.completeExceptionally(failure)
        // The current caller queues Finished later in this same cleanup launch. Joining its public
        // Job before draining the manager scheduler includes that report without inspecting private state.
        val worker = awaitCheckpoint(controller.cleanupWorker)
        awaitCheckpoint(testScope.async { worker.join() })
    }

    @OptIn(InternalCoroutinesApi::class)
    private class NotifyingDispatcher(
        private val delegate: CoroutineDispatcher,
        private val queued: Channel<Unit>,
    ) : CoroutineDispatcher(), Delay by (delegate as Delay) {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context, block)
            queued.trySend(Unit)
        }
    }

    private data class Descriptor(
        val name: String,
        override val priority: Int,
        val slot: Int = 400,
        val service: Class<out Service> = ManagerHost::class.java,
    ) : StreamingModule {
        override val id = StreamingModule.Id(name)
        override val description = StreamingModule.Description(1, 1, 1, 1, 1, 1, 1)
        override val serviceClass get() = service
        override val foregroundSlot = object : StreamingModuleNotifications.ForegroundSlot {
            override val id = slot
        }
    }

    internal sealed interface Command {
        data object Start : Command
        data class Stop(val attempt: StreamingModule.CaptureAttemptId, val fromNotification: Boolean) : Command
        data class Recover(val token: Uuid) : Command
    }

    internal class FakeController(
        val handle: StreamingModule.Controller.ManagerHandle,
        override val instanceId: StreamingModule.InstanceId = handle.instanceId,
    ) : StreamingModule.Controller {
        val commands = mutableListOf<Command>()
        var startCalls = 0
        var onStart: () -> Unit = {}
        var onStartRequest: () -> Unit = {}
        var onStopRequest: () -> Unit = {}
        var onRecovery: () -> Unit = {}
        var onShutdown: () -> Unit = {}
        val shutdown = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Boolean>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupWorker = CompletableDeferred<Job>()
        override fun startModule() {
            startCalls++
            onStart()
        }

        override fun requestStreamStart() {
            onStartRequest()
            commands.add(Command.Start)
        }

        override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, fromNotification: Boolean) {
            onStopRequest()
            commands.add(Command.Stop(attempt, fromNotification))
        }

        override fun requestRecovery(errorToken: Uuid) {
            onRecovery()
            commands.add(Command.Recover(errorToken))
        }

        override fun requestModuleShutdown() {
            shutdown.complete(Unit)
            onShutdown()
        }

        override suspend fun awaitCleanup(): Boolean {
            cleanupWorker.complete(currentCoroutineContext().job)
            cleanupEntered.complete(Unit)
            return cleanup.await()
        }

        override fun onServiceDestroyed() = Unit

        @Composable
        override fun Content(window: WindowSizeClass, modifier: Modifier) = Unit
    }

    private class Notifications : StreamingModuleNotifications {
        var onErrorUpdate: (StreamingModuleNotifications.ErrorContent?) -> Unit = {}
        var buildFailure: RuntimeException? = null
        val errorUpdates = mutableListOf<Pair<Int, StreamingModuleNotifications.ErrorContent?>>()
        val foregroundActions = mutableListOf<StreamingModuleNotifications.ForegroundAction>()

        override fun buildForeground(action: StreamingModuleNotifications.ForegroundAction): Notification {
            buildFailure?.let { throw it }
            foregroundActions.add(action)
            return Notification.Builder(RuntimeEnvironment.getApplication(), "test").setSmallIcon(1).build()
        }

        override fun updateError(notificationId: Int, content: StreamingModuleNotifications.ErrorContent?) {
            errorUpdates.add(notificationId to content)
            onErrorUpdate(content)
        }

        override fun areNotificationsEnabled(channel: StreamingModuleNotifications.Channel) = true
        override fun channelSettingsIntent(channel: StreamingModuleNotifications.Channel): Intent? = null
    }

    private companion object {
        val IDLE = StreamingModule.Status(isStreaming = false, hasConsumer = false, captureAttempt = null, error = null)
    }
}

internal open class ManagerHost : StreamingModuleService() {
    override fun createController(managerHandle: StreamingModule.Controller.ManagerHandle): StreamingModule.Controller = factory(managerHandle)

    companion object {
        var factory: (StreamingModule.Controller.ManagerHandle) -> StreamingModule.Controller = { error("No factory") }
    }
}

internal class OtherManagerHost : StreamingModuleService() {
    override fun createController(managerHandle: StreamingModule.Controller.ManagerHandle): StreamingModule.Controller =
        ManagerHost.factory(managerHandle)
}

@Implements(Service::class)
internal class PendingStartServiceShadow : ShadowService() {
    var latestStartId: Int = 0
    val conditionalStopIds = mutableListOf<Int>()

    @Implementation
    override fun stopSelfResult(startId: Int): Boolean {
        conditionalStopIds.add(startId)
        return startId == latestStartId && super.stopSelfResult(startId)
    }
}
