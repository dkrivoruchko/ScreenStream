package io.screenstream.core.manager

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class StreamingModuleActionReceiverTest {
    private val manager = RecordingActionManager()
    private val receiver = StreamingModuleActionReceiver()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val instance = StreamingModule.InstanceId(StreamingModule.Id("module / encoded"), Uuid.parse("12345678-1234-1234-1234-123456789abc"))
    private val attempt = StreamingModule.CaptureAttemptId(instance, Uuid.parse("abcdefab-1234-1234-1234-123456789abc"))
    private val token = Uuid.parse("fedcbafe-1234-1234-1234-123456789abc")

    @Before
    fun setUp() {
        startKoin { modules(module { single<StreamingModuleManager> { manager } }) }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    // Cases: CORE-ACT-01
    @Test
    fun publicFactoriesRouteExactTargetsAndNotificationOrigin() {
        actions().forEach { receiver.onReceive(context, shadowOf(it).savedIntent) }
        assertEquals(listOf(Action.Exit(instance), Action.Stop(attempt, true), Action.Recover(instance, token)), manager.commands)
    }

    // Cases: CORE-ACT-02
    @Test
    fun immutableExplicitActionsReuseOnlyTheSameTarget() {
        val original = actions()
        assertEquals(original, actions())
        for (action in original) {
            assertTrue(action.isImmutable)
            assertEquals(ComponentName(context, StreamingModuleActionReceiver::class.java), shadowOf(action).savedIntent.component)
        }
        assertEquals(3, original.toSet().size)
        val otherInstance = StreamingModule.InstanceId(instance.moduleId)
        assertNotEquals(original[0], StreamingModuleActionReceiver.exitAction(context, otherInstance))
        assertNotEquals(original[1], StreamingModuleActionReceiver.stopAction(context, StreamingModule.CaptureAttemptId(instance)))
        assertNotEquals(original[1], StreamingModuleActionReceiver.stopAction(context, attempt.copy(instanceId = otherInstance)))
        assertNotEquals(original[2], StreamingModuleActionReceiver.recoveryAction(context, instance, Uuid.random()))
        assertNotEquals(original[2], StreamingModuleActionReceiver.recoveryAction(context, otherInstance, token))
        // Creating newer actions never retargets the retained original ones.
        original.forEach { receiver.onReceive(context, shadowOf(it).savedIntent) }
        assertEquals(listOf(Action.Exit(instance), Action.Stop(attempt, true), Action.Recover(instance, token)), manager.commands)
    }

    // Cases: CORE-ACT-03
    @Test
    fun nullUnknownAndMismatchedActionsDoNotDispatch() {
        receiver.onReceive(context, null)
        receiver.onReceive(context, Intent("unknown"))
        val intents = actions().map { shadowOf(it).savedIntent }
        intents.forEachIndexed { index, intent ->
            receiver.onReceive(context, Intent(intent).setAction(intents[(index + 1) % intents.size].action))
            receiver.onReceive(context, Intent(intent).setData(null))
        }
        assertTrue(manager.commands.isEmpty())
    }

    // Cases: CORE-ACT-03
    @Test
    fun malformedAndNoncanonicalUrisNeverDispatch() {
        for (action in actions()) {
            val intent = shadowOf(action).savedIntent
            val uri = checkNotNull(intent.data)
            val encodedPath = checkNotNull(uri.encodedPath)
            val mutations = listOf(
                uri.buildUpon().scheme("other").build(),
                uri.buildUpon().authority("other").build(),
                uri.buildUpon().appendPath("extra").build(),
                uri.buildUpon().path("/missing").build(),
                uri.buildUpon().encodedQuery("x=1").build(),
                uri.buildUpon().fragment("fragment").build(),
                uri.buildUpon().encodedPath("/%20/" + encodedPath.split('/').drop(2).joinToString("/")).build(),
                android.net.Uri.parse(uri.toString().replace(instance.uuid.toString(), "invalid")),
                android.net.Uri.parse(uri.toString().replace(instance.uuid.toString(), instance.uuid.toString().uppercase())),
                android.net.Uri.parse(uri.toString().replace(instance.uuid.toString(), "%31" + instance.uuid.toString().drop(1))),
            )
            mutations.forEach { receiver.onReceive(context, Intent(intent).setData(it)) }
        }
        for (action in actions().drop(1)) {
            val intent = shadowOf(action).savedIntent
            val uri = checkNotNull(intent.data)
            val encodedPath = checkNotNull(uri.encodedPath)
            val invalidToken = uri.buildUpon().encodedPath(encodedPath.substringBeforeLast('/') + "/invalid").build()
            receiver.onReceive(context, Intent(intent).setData(invalidToken))
        }
        assertTrue(manager.commands.isEmpty())
    }

    private fun actions(): List<PendingIntent> = listOf(
        StreamingModuleActionReceiver.exitAction(context, instance),
        StreamingModuleActionReceiver.stopAction(context, attempt),
        StreamingModuleActionReceiver.recoveryAction(context, instance, token),
    )

    private sealed interface Action {
        data class Exit(val instance: StreamingModule.InstanceId?) : Action
        data class Stop(val attempt: StreamingModule.CaptureAttemptId, val fromNotification: Boolean) : Action
        data class Recover(val instance: StreamingModule.InstanceId, val token: Uuid) : Action
    }

    private class RecordingActionManager : StreamingModuleManager {
        val commands = mutableListOf<Action>()
        override val modules = emptyList<StreamingModule>()
        override val state = MutableStateFlow<StreamingModuleManager.State>(StreamingModuleManager.State.NoModule)
        override fun exit(expectedInstanceId: StreamingModule.InstanceId?) {
            commands.add(Action.Exit(expectedInstanceId))
        }

        override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, fromNotification: Boolean) {
            commands.add(Action.Stop(attempt, fromNotification))
        }

        override fun requestRecovery(instanceId: StreamingModule.InstanceId, errorToken: Uuid) {
            commands.add(Action.Recover(instanceId, errorToken))
        }

        override fun selectModule(moduleId: StreamingModule.Id?) = error("Unexpected selection")
        override fun restartModule(expectedInstanceId: StreamingModule.InstanceId) = error("Unexpected restart")
        override fun requestStreamStart(instanceId: StreamingModule.InstanceId) = error("Unexpected Start")

        @Composable
        override fun InstanceContent(instanceId: StreamingModule.InstanceId, window: WindowSizeClass, modifier: Modifier, fallback: @Composable () -> Unit) =
            error("Unexpected UI")
    }
}
