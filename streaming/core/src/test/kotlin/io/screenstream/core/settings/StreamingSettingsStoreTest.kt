package io.screenstream.core.settings

import androidx.datastore.preferences.preferencesDataStoreFile
import io.screenstream.core.CoreKoinTestRule
import io.screenstream.core.module.StreamingModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class StreamingSettingsStoreTest {
    @get:Rule(order = Int.MIN_VALUE)
    val koin = CoreKoinTestRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // Cases: CORE-SET-01
    @Test
    fun cancellingAnInitializationWaiterDoesNotCancelTheSharedLoad(): Unit = runBlocking {
        withTimeout(5.seconds) {
            val context = context()
            seedCoreSettings(context, """{"selectedModuleId":{"value":"saved"},"screenCaptureEducationCompleted":true}""")
            val scheduler = TestCoroutineScheduler()
            val settings = StreamingSettingsStore(context, StandardTestDispatcher(scheduler))
            assertFailsWith<IllegalStateException> { settings.data }
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { settings.initialize() }
            assertFalse(cancelled.isCompleted)
            cancelled.cancel()
            val survivor = async(start = CoroutineStart.UNDISPATCHED) { settings.initialize() }
            while (!survivor.isCompleted) {
                scheduler.runCurrent()
                yield()
            }
            survivor.await()
            assertEquals(StreamingSettingsStore.Data(StreamingModule.Id("saved"), true), settings.data.value)
            assertTrue(cancelled.isCancelled)
            settings.initialize()
        }
    }

    // Cases: CORE-SET-02
    @Test
    fun selectionAndEducationEditsPreserveEachOtherAndReloadFromDurableBytes(): Unit = runBlocking {
        withTimeout(5.seconds) {
            val context = context()
            seedCoreSettings(context, """{"selectedModuleId":{"value":"before"}}""")
            val settings = StreamingSettingsStore(context)
            settings.initialize()
            settings.markEducationCompleted()
            settings.updateData { copy(selectedModuleId = StreamingModule.Id("after")) }
            val expected = StreamingSettingsStore.Data(StreamingModule.Id("after"), true)
            assertEquals(expected, settings.data.first { it == expected })
            assertEquals(expected, readCoreSettings(context))
            val copiedContext = context()
            val copiedFile = copiedContext.preferencesDataStoreFile("streaming_settings")
            check(checkNotNull(copiedFile.parentFile).mkdirs())
            context.preferencesDataStoreFile("streaming_settings").copyTo(copiedFile)
            val reloaded = StreamingSettingsStore(copiedContext)
            reloaded.initialize()
            assertEquals(expected, reloaded.data.value)
            reloaded.updateData { copy(selectedModuleId = null) }
            assertEquals(StreamingSettingsStore.Data(null, true), readCoreSettings(copiedContext))
        }
    }

    // Cases: CORE-SET-03
    @Test
    fun cancelledCallersKeepAdmittedEditsAndEachTransformUsesTheLatestValue(): Unit = runBlocking {
        withTimeout(5.seconds) {
            val context = context()
            val scheduler = TestCoroutineScheduler()
            val dispatcher = StandardTestDispatcher(scheduler)
            val settings = StreamingSettingsStore(context, dispatcher)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val ready = scope.async { settings.initialize() }
                while (!ready.isCompleted) {
                    scheduler.runCurrent()
                    yield()
                }
                ready.await()
                val inputs = mutableListOf<StreamingSettingsStore.Data>()
                // UNDISPATCHED reaches withContext(NonCancellable + dispatcher) before cancellation.
                val first = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    settings.updateData {
                        inputs.add(this)
                        copy(selectedModuleId = StreamingModule.Id("selected"))
                    }
                }
                val second = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    settings.updateData {
                        inputs.add(this)
                        copy(screenCaptureEducationCompleted = true)
                    }
                }
                first.cancel()
                second.cancel()
                while (!first.isCompleted || !second.isCompleted) {
                    scheduler.runCurrent()
                    yield()
                }
                val expected = StreamingSettingsStore.Data(StreamingModule.Id("selected"), true)
                assertTrue(first.isCancelled)
                assertTrue(second.isCancelled)
                assertEquals(listOf(StreamingSettingsStore.Data(), StreamingSettingsStore.Data(StreamingModule.Id("selected"))), inputs)
                assertEquals(expected, readCoreSettings(context))
            } finally {
                scope.cancel()
            }
        }
    }

    // Cases: CORE-SET-04
    @Test
    fun transformFailurePreservesDurableStateAndTheNextEditSucceeds(): Unit = runBlocking {
        withTimeout(5.seconds) {
            val context = context()
            seedCoreSettings(context, """{"selectedModuleId":{"value":"before"}}""")
            val settings = StreamingSettingsStore(context)
            settings.initialize()
            val before = context.preferencesDataStoreFile("streaming_settings").readBytes()
            val failure = IllegalStateException("transform")
            val observed = assertFailsWith<IllegalStateException> { settings.updateData { throw failure } }
            assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
            assertContentEquals(before, context.preferencesDataStoreFile("streaming_settings").readBytes())
            assertEquals(StreamingSettingsStore.Data(StreamingModule.Id("before")), settings.data.value)
            settings.markEducationCompleted()
            assertEquals(StreamingSettingsStore.Data(StreamingModule.Id("before"), true), readCoreSettings(context))
        }
    }

    // Cases: CORE-SET-04
    @Test
    fun storageFailurePropagatesWithoutReplacingTheSnapshotAndLaterEditSucceeds(): Unit = runBlocking {
        withTimeout(5.seconds) {
            val context = context()
            seedCoreSettings(context, """{"selectedModuleId":{"value":"before"}}""")
            val file = context.preferencesDataStoreFile("streaming_settings")
            val before = file.readBytes()
            // Supplied FileStorage write fault, arranged before creating the single owner.
            val scratch = File(file.path + ".tmp").apply { check(mkdir()) }
            val blocker = File(scratch, "blocker").apply { writeText("fault") }
            val settings = StreamingSettingsStore(context)
            settings.initialize()
            assertFailsWith<IOException> { settings.markEducationCompleted() }
            assertContentEquals(before, file.readBytes())
            assertEquals(StreamingSettingsStore.Data(StreamingModule.Id("before")), settings.data.value)
            check(blocker.delete())
            check(scratch.delete())
            settings.markEducationCompleted()
            val expected = StreamingSettingsStore.Data(StreamingModule.Id("before"), true)
            assertEquals(expected, readCoreSettings(context))
            assertEquals(expected, settings.data.first { it == expected })
        }
    }

    private fun context(): CoreSettingsTestContext = CoreSettingsTestContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
}
