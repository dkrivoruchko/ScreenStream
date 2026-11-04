package io.screenstream.core.manager

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import com.elvishew.xlog.XLog
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import io.screenstream.core.module.StreamingModuleService
import io.screenstream.core.settings.StreamingSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
internal class StreamingModuleManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val scheduler = TestCoroutineScheduler()
    private val queued = Channel<Unit>(Channel.UNLIMITED)

    @Volatile
    private var cleanupReportPosted: Pair<FakeController, CompletableDeferred<Unit>>? = null
    private val testDispatcher = StandardTestDispatcher(scheduler)
    private val dispatcher = NotifyingDispatcher(testDispatcher, queued) {
        cleanupReportPosted?.let { (controller, signal) ->
            if (controller.cleanupReturned.isCompleted) signal.complete(Unit)
        }
    }
    private val services = mutableListOf<ServiceController<out StreamingModuleService>>()
    private val controllers = mutableListOf<FakeController>()
    private lateinit var settings: StreamingSettingsStore
    private lateinit var manager: StreamingModuleManagerImpl
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val first = Descriptor("first", 20)
    private val second = Descriptor("second", 10)

    @Before
    fun setUp() {
        XLog.init(Int.MAX_VALUE)
        val directory = temporaryFolder.newFolder()
        settings = StreamingSettingsStore(object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        })
        runBlocking { withTimeout(5000) { settings.initialize() } }
        manager = newManager(listOf(first, second))
        startKoin {
            modules(module {
                single { manager }
                single<StreamingModuleNotifications> { Notifications }
            })
        }
        ManagerHost.factory = { handle -> FakeController(handle).also { controllers.add(it) } }
    }

    @After
    fun tearDown() {
        controllers.forEach { it.cleanup.complete(true) }
        manager.exit()
        scheduler.runCurrent()
        services.reversed().forEach { it.destroy() }
        scheduler.runCurrent()
        stopKoin()
        ManagerHost.factory = { error("No test factory installed") }
    }

    // Cases: CORE-MGR-01
    @Test
    fun catalogIsSortedCopiedAndRejectsInvalidRegistrations() {
        val source = mutableListOf<StreamingModule>(second, first)
        val catalog = newManager(source).modules
        source.clear()
        assertEquals(listOf(first, second), catalog)
        assertThrows(UnsupportedOperationException::class.java) { (catalog as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { newManager(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { newManager(listOf(first, first)) }
        assertThrows(IllegalArgumentException::class.java) { newManager(listOf(first.copy(slot = 0))) }
        assertThrows(IllegalArgumentException::class.java) { newManager(listOf(first.copy(slot = 1000))) }
        assertThrows(IllegalArgumentException::class.java) { newManager(listOf(first, second.copy(slot = 401))) }
        assertThrows(IllegalArgumentException::class.java) { newManager(listOf(first, second.copy(service = OtherManagerHost::class.java))) }
    }

    // Cases: CORE-MGR-02, CORE-MGR-03
    @Test
    fun unknownSelectionDoesNothingAndInitialNullUsesHighestPriority() {
        manager.selectModule(StreamingModule.Id("missing"))
        assertEquals(StreamingModuleManager.State.NoModule, manager.state.value)
        assertNull(shadowOf(app as android.app.Application).nextStartedService)
        val controller = open()
        assertEquals(first.id, controller.instanceId.moduleId)
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, IDLE), manager.state.value)
    }

    // Cases: CORE-MGR-02
    @Test
    fun initialNullUsesAvailableSavedSelection() {
        runBlocking {
            withTimeout(5000) {
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
            withTimeout(5000) {
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
    fun duplicateAndMalformedServiceDeliveriesDoNotCreateAnotherController() {
        manager.selectModule()
        scheduler.runCurrent()
        val intent = checkNotNull(nextStart())
        createHost().withIntent(Intent("unrelated")).startCommand(0, 1)
        createHost().withIntent(Intent(intent).replaceExtras(null as android.os.Bundle?)).startCommand(0, 1)
        assertTrue(controllers.isEmpty())
        assertEquals(StreamingModuleManager.State.Switching, manager.state.value)
        val host = createHost()
        host.withIntent(intent).startCommand(0, 1)
        val controller = controllers.single()
        host.withIntent(intent).startCommand(0, 4)
        assertEquals(listOf(controller), controllers)
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
        val host = Robolectric.buildService(OtherManagerHost::class.java).create().also { services.add(it) }
        host.withIntent(intent).startCommand(0, 1)
        scheduler.runCurrent()
        assertTrue(controllers.isEmpty())
        assertTrue(manager.state.value is StreamingModuleManager.State.Failed)
    }

    // Cases: CORE-MGR-06
    @Test
    fun factoryFailureUsesGenericFailure() {
        ManagerHost.factory = { throw IllegalStateException("factory") }
        manager.selectModule()
        scheduler.runCurrent()
        createHost().withIntent(checkNotNull(nextStart())).startCommand(0, 1)
        scheduler.runCurrent()
        assertEquals(null, (manager.state.value as StreamingModuleManager.State.Failed).messageResource)
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
        assertFalse(controllers.single().started)
        assertTrue(controllers.single().shutdown.isCompleted)
        assertFalse(controllers.single().handle.isCurrent())
        assertTrue(manager.state.value is StreamingModuleManager.State.Failed)
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
        assertEquals(listOf("stop:$attempt:true"), controller.commands)
        scheduler.runCurrent()
        assertEquals(listOf("stop:$attempt:true", "start", "recover:$token"), controller.commands)
    }

    // Cases: CORE-MGR-03, CORE-MGR-04, CORE-MGR-10
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
        manager.selectModule(first.id)
        scheduler.runCurrent()
        assertNull(nextStart())
        manager.restartModule(StreamingModule.InstanceId(first.id))
        assertTrue(manager.state.value is StreamingModuleManager.State.Failed)
        settleCleanup(controller)
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
        createHost().withIntent(intent).startCommand(0, 1)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(3001)
        scheduler.runCurrent()
        assertTrue(controllers.isEmpty())
        assertTrue(manager.state.value is StreamingModuleManager.State.Failed)
    }

    // Cases: CORE-MGR-08, CORE-MGR-09
    @Test
    fun heartbeatUsesUptimeIgnoresOlderReportsAndRecoversSameInstance() {
        val controller = open()
        val initial = SystemClock.uptimeMillis()
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(10000))
        scheduler.advanceTimeBy(3001)
        scheduler.runCurrent()
        assertTrue(manager.state.value is StreamingModuleManager.State.Running)
        ShadowSystemClock.advanceBy(Duration.ofMillis(3000))
        scheduler.advanceTimeBy(3000)
        scheduler.runCurrent()
        assertTrue(manager.state.value is StreamingModuleManager.State.Running)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Unresponsive(controller.instanceId, IDLE), manager.state.value)
        manager.requestStreamStart(controller.instanceId)
        scheduler.runCurrent()
        assertEquals(listOf("start"), controller.commands)
        val fresh = IDLE.copy(hasConsumer = true)
        controller.handle.reportRunning(fresh, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        controller.handle.reportRunning(IDLE, initial)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Running(controller.instanceId, fresh), manager.state.value)
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
        scheduler.advanceTimeBy(3001)
        scheduler.runCurrent()
        assertEquals(StreamingModuleManager.State.Failed(controller.instanceId), manager.state.value)
        assertFalse(controller.handle.isCurrent())
        assertTrue(controller.shutdown.isCompleted)
    }

    // Cases: CORE-MGR-11
    @Test
    fun shutdownDeadlineOpensReplacementWhileOldCleanupRemainsOwned() {
        val old = open()
        manager.selectModule(second.id)
        scheduler.runCurrent()
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(3000))
        scheduler.advanceTimeBy(3000)
        scheduler.runCurrent()
        assertNull(nextStart())
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(1))
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        val replacement = deliver(checkNotNull(nextStart()))
        assertFalse(old.cleanup.isCompleted)
        assertFalse(old.handle.isCurrent())
        settleCleanup(old)
        assertEquals(StreamingModuleManager.State.Running(replacement.instanceId, IDLE), manager.state.value)
    }

    // Cases: CORE-MGR-13
    @Test
    fun exitFromFactoryRetainsReturnedControllerForCleanupWithoutStartingIt() {
        ManagerHost.factory = { handle ->
            manager.exit(handle.instanceId)
            FakeController(handle).also { controllers.add(it) }
        }
        val controller = open(report = false)
        assertFalse(controller.started)
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
        createHost().withIntent(stale).startCommand(0, 1)
        scheduler.runCurrent()
        assertTrue(controllers.isEmpty())
        assertEquals(StreamingModuleManager.State.Exiting, manager.state.value)
    }


    // Cases: CORE-MGR-02, CORE-MGR-12
    @Test
    fun explicitSelectionPersistsAndNullAfterExitUsesProcessSelection() {
        val controller = open()
        manager.selectModule(second.id)
        settleCleanup(controller)
        val selected = deliver(checkNotNull(nextStart()))
        assertEquals(second.id, selected.instanceId.moduleId)
        runBlocking { withTimeout(5000) { settings.data.first { it.selectedModuleId == second.id } } }
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
        assertTrue(controller.started)
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
        scheduler.advanceTimeBy(3001)
        scheduler.runCurrent()
        val successorStart = checkNotNull(nextStart())
        originalService.withIntent(successorStart).startCommand(0, 2)
        scheduler.runCurrent()
        val failed = manager.state.value as StreamingModuleManager.State.Failed
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

    private fun newManager(descriptors: List<StreamingModule>): StreamingModuleManagerImpl =
        StreamingModuleManagerImpl(descriptors, app, settings, Notifications, dispatcher)

    private fun nextStart(): Intent? = shadowOf(app as android.app.Application).nextStartedService
    private fun createHost(): ServiceController<ManagerHost> =
        Robolectric.buildService(ManagerHost::class.java).create().also { services.add(it) }

    private fun open(report: Boolean = true): FakeController {
        manager.selectModule()
        scheduler.runCurrent()
        return deliver(checkNotNull(nextStart()), report)
    }

    private fun deliver(intent: Intent, report: Boolean = true): FakeController {
        createHost().withIntent(intent).startCommand(0, 1)
        val controller = controllers.last()
        if (report) controller.handle.reportRunning(IDLE, SystemClock.uptimeMillis())
        scheduler.runCurrent()
        return controller
    }

    private fun settleCleanup(controller: FakeController) {
        val posted = CompletableDeferred<Unit>()
        cleanupReportPosted = controller to posted
        controller.cleanup.complete(true)
        runBlocking {
            withTimeout(5000) {
                while (!controller.cleanupReturned.isCompleted || !posted.isCompleted) {
                    scheduler.runCurrent()
                    if (!controller.cleanupReturned.isCompleted || !posted.isCompleted) queued.receive()
                }
            }
        }
        scheduler.runCurrent()
        cleanupReportPosted = null
    }

    @OptIn(InternalCoroutinesApi::class)
    private class NotifyingDispatcher(
        private val delegate: CoroutineDispatcher,
        private val queued: Channel<Unit>,
        private val onPost: () -> Unit,
    ) : CoroutineDispatcher(), Delay by (delegate as Delay) {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context, block)
            onPost()
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

    internal class FakeController(
        val handle: StreamingModule.Controller.ManagerHandle,
        override val instanceId: StreamingModule.InstanceId = handle.instanceId,
    ) : StreamingModule.Controller {
        val commands = mutableListOf<String>()
        var started = false
        var onStart: () -> Unit = {}
        val shutdown = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Boolean>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupReturned = CompletableDeferred<Unit>()
        override fun startModule() {
            started = true
            onStart()
        }

        override fun requestStreamStart() {
            commands.add("start")
        }

        override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, fromNotification: Boolean) {
            commands.add("stop:$attempt:$fromNotification")
        }

        override fun requestRecovery(errorToken: Uuid) {
            commands.add("recover:$errorToken")
        }

        override fun requestModuleShutdown() {
            shutdown.complete(Unit)
        }

        override suspend fun awaitCleanup(): Boolean {
            cleanupEntered.complete(Unit)
            return cleanup.await().also { cleanupReturned.complete(Unit) }
        }

        override fun onServiceDestroyed() = Unit

        @Composable
        override fun Content(window: WindowSizeClass, modifier: Modifier) = Unit
    }

    private object Notifications : StreamingModuleNotifications {
        override fun buildForeground(action: StreamingModuleNotifications.ForegroundAction): Notification =
            Notification.Builder(RuntimeEnvironment.getApplication(), "test").setSmallIcon(1).build()

        override fun updateError(notificationId: Int, content: StreamingModuleNotifications.ErrorContent?) = Unit
        override fun areNotificationsEnabled(channel: StreamingModuleNotifications.Channel) = true
        override fun channelSettingsIntent(channel: StreamingModuleNotifications.Channel): Intent? = null
    }

    private companion object {
        val IDLE = StreamingModule.Status(false, false, null, null)
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
