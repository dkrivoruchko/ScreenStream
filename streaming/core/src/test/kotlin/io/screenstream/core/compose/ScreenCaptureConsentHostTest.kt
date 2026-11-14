package io.screenstream.core.compose

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import info.dvkr.screenstream.common.R
import io.screenstream.core.CoreKoinTestRule
import io.screenstream.core.capture.ConsentLaunchException
import io.screenstream.core.capture.ConsentRequestObserver
import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.capture.ScreenCaptureAccessImpl
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.settings.CoreSettingsTestContext
import io.screenstream.core.settings.StreamingSettingsStore
import io.screenstream.core.settings.readCoreSettings
import io.screenstream.core.settings.seedCoreSettings
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class ScreenCaptureConsentHostTest {
    @get:Rule(order = Int.MIN_VALUE)
    val koin = CoreKoinTestRule()

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val mounted = mutableStateOf(true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val attempts = mutableListOf<CompletableJob>()
    private lateinit var access: ScreenCaptureAccessImpl
    private lateinit var settings: StreamingSettingsStore
    private lateinit var settingsContext: CoreSettingsTestContext
    private var scenario: ActivityScenario<CoreComposeTestActivity>? = null
    private val continueText: String get() = application.getString(R.string.common_continue)
    private val educationTitle: String get() = application.getString(R.string.common_screen_capture_permission_required_title)

    @Before
    fun setUp() {
        access = ScreenCaptureAccessImpl(application, object : ConsentRequestObserver {
            override fun savedConsentTaken(attempt: StreamingModule.CaptureAttemptId) = Unit
            override fun systemConsentSubmitted(attempt: StreamingModule.CaptureAttemptId, educationAccepted: Boolean) = Unit
        })
    }

    @After
    fun tearDown() {
        attempts.forEach { it.cancel() }
        scope.cancel()
        scenario?.close()
        CoreComposeTestActivity.content = null
    }

    // Cases: CORE-UI-01
    @Test
    fun educationCancelDoesNotLaunchPrepareOrSave() {
        val registry = RecordingConsentRegistry()
        createSettings()
        launchHost(registry)
        val pending = request()
        compose.onNodeWithText(educationTitle).assertExists()
        compose.onNodeWithText(application.getString(android.R.string.cancel)).performClick()
        assertReason(pending, ScreenCaptureAccess.NoGrantReason.EducationDeclined)
        assertEducationUnchanged(registry, pending)
    }

    // Cases: CORE-UI-01
    @Test
    fun backDismissalDoesNotLaunchPrepareOrSave() {
        val registry = RecordingConsentRegistry()
        createSettings()
        launchHost(registry)
        val pending = request()
        compose.onNodeWithText(educationTitle).assertExists()
        Espresso.pressBack()
        assertReason(pending, ScreenCaptureAccess.NoGrantReason.EducationDeclined)
        assertEducationUnchanged(registry, pending)
    }

    // Cases: CORE-UI-02, CORE-UI-03
    @Test
    fun educationAcceptanceLaunchesOnceAndIsSavedDespiteAndroidDenial() {
        val registry = RecordingConsentRegistry()
        createSettings()
        launchHost(registry)
        val pending = request()
        compose.onNodeWithText(continueText).performClick()
        compose.waitForIdle()
        assertEquals(1, registry.launches.size)
        compose.onNodeWithText(continueText).assertDoesNotExist()
        compose.runOnIdle { registry.reply(0, Activity.RESULT_CANCELED) }
        assertReason(pending, ScreenCaptureAccess.NoGrantReason.Declined)
        assertTrue(pending.preparations.isEmpty())
        compose.waitUntil(5_000) { settings.screenCaptureEducationCompleted }
        assertTrue(awaitSettingsWrite().isSuccess)
        assertTrue(runBlocking { readCoreSettings(settingsContext).screenCaptureEducationCompleted })

        val next = request()
        compose.onNodeWithText(educationTitle).assertDoesNotExist()
        assertEquals(2, registry.launches.size)
        compose.runOnIdle { registry.reply(1, Activity.RESULT_CANCELED) }
        assertReason(next, ScreenCaptureAccess.NoGrantReason.Declined)
    }

    // Cases: CORE-UI-03
    @Test
    fun savedEducationSkipsDialogAndSuccessfulResultPreparesOnceOnMain() {
        val registry = RecordingConsentRegistry()
        createSettings(educationCompleted = true)
        launchHost(registry)
        val pending = request()
        compose.onNodeWithText(educationTitle).assertDoesNotExist()
        assertEquals(1, registry.launches.size)
        compose.runOnIdle { registry.reply(0, Activity.RESULT_OK, Intent("consent")) }
        assertIs<ScreenCaptureAccess.ConsentResult.Granted>(outcome(pending))
        assertEquals(listOf(Looper.getMainLooper().thread), pending.preparations)
        compose.runOnIdle { registry.reply(0, Activity.RESULT_OK, Intent("duplicate")) }
        assertEquals(1, pending.preparations.size)
    }

    // Cases: CORE-UI-04
    @Test
    fun educationWriteFailureStillLaunchesAndEducationReturnsForLaterRequest() {
        val registry = RecordingConsentRegistry()
        val settingsScheduler = TestCoroutineScheduler()
        createSettings(dispatcher = StandardTestDispatcher(settingsScheduler), initialize = false)
        awaitSettingsInitialization(settingsScheduler)
        val preferences = settingsContext.preferencesDataStoreFile("streaming_settings")
        val saved = preferences.readBytes()
        val blockedTemporaryFile = File(preferences.path + ".tmp").apply { check(mkdir()) }
        File(blockedTemporaryFile, "blocker").writeText("Fixture storage fault")
        launchHost(registry)
        val first = request()
        compose.onNodeWithText(continueText).performClick()
        compose.waitForIdle()
        assertEquals(1, registry.launches.size)
        // Admit the real education edit before enqueuing the later serialization barrier.
        compose.runOnIdle { settingsScheduler.runCurrent() }
        compose.runOnIdle { registry.reply(0, Activity.RESULT_CANCELED) }
        assertReason(first, ScreenCaptureAccess.NoGrantReason.Declined)
        // This no-op edit joins the attempted save without requiring another physical write.
        assertTrue(awaitSettingsWrite { settingsScheduler.runCurrent() }.isSuccess)
        assertFalse(settings.screenCaptureEducationCompleted)
        assertContentEquals(saved, preferences.readBytes())

        val later = request()
        compose.onNodeWithText(educationTitle).assertExists()
        assertEquals(1, registry.launches.size)
        compose.onNodeWithText(application.getString(android.R.string.cancel)).performClick()
        assertReason(later, ScreenCaptureAccess.NoGrantReason.EducationDeclined)
        assertTrue(first.preparations.isEmpty())
        assertTrue(later.preparations.isEmpty())
    }

    // Cases: CORE-UI-05
    @Test
    fun removedHostCannotLaunchAfterDelayedSettingsInitializationCompletes() {
        val scheduler = TestCoroutineScheduler()
        createSettings(educationCompleted = true, dispatcher = StandardTestDispatcher(scheduler), initialize = false)
        val registry = RecordingConsentRegistry()
        launchHost(registry)
        val pending = request()
        assertFalse(pending.result.isCompleted)
        compose.runOnIdle { mounted.value = false }
        assertReason(pending, ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        awaitSettingsInitialization(scheduler)
        compose.waitForIdle()
        assertEquals(0, registry.launches.size)
        compose.onNodeWithText(educationTitle).assertDoesNotExist()
        assertTrue(pending.preparations.isEmpty())
    }

    // Cases: CORE-UI-06
    @Test
    fun actualActivityPauseClosesUnsubmittedRequestAndResumeAdmitsAnother() {
        val registry = RecordingConsentRegistry()
        createSettings()
        launchHost(registry)
        val first = request()
        compose.onNodeWithText(educationTitle).assertExists()
        checkNotNull(scenario).moveToState(Lifecycle.State.STARTED)
        assertReason(first, ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        assertEquals(0, registry.launches.size)

        checkNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        val resumed = request()
        compose.onNodeWithText(educationTitle).assertExists()
        compose.onNodeWithText(application.getString(android.R.string.cancel)).performClick()
        assertReason(resumed, ScreenCaptureAccess.NoGrantReason.EducationDeclined)
    }

    // Cases: CORE-UI-06
    @Test
    fun disposingHostRemovesEligibilityAndRemountAdmitsAnotherRequest() {
        val registry = RecordingConsentRegistry()
        createSettings()
        launchHost(registry)
        val original = request()
        compose.onNodeWithText(educationTitle).assertExists()
        compose.runOnIdle { mounted.value = false }
        assertReason(original, ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        compose.runOnIdle { mounted.value = true }
        val replacement = request()
        compose.onNodeWithText(educationTitle).assertExists()
        compose.onNodeWithText(application.getString(android.R.string.cancel)).performClick()
        assertReason(replacement, ScreenCaptureAccess.NoGrantReason.EducationDeclined)
        assertEquals(0, registry.launches.size)
    }

    // Cases: CORE-UI-07, CORE-UI-08
    @Test
    fun submittedRequestSurvivesActualRecreationAndRestoredRegistryDeliversOnce() {
        createSettings(educationCompleted = true)
        launchHost()
        val original = request()
        var originalActivity: CoreComposeTestActivity? = null
        var requestCode = 0
        val requestKey = checkNotNull(access.currentRequest.value).key
        checkNotNull(scenario).onActivity { activity ->
            originalActivity = activity
            requestCode = checkNotNull(shadowOf(activity).nextStartedActivityForResult).requestCode
            assertNull(shadowOf(activity).nextStartedActivityForResult)
        }
        checkNotNull(scenario).moveToState(Lifecycle.State.STARTED)
        compose.waitForIdle()
        assertFalse(original.result.isCompleted)
        checkNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
        checkNotNull(scenario).recreate()
        compose.waitForIdle()
        val restoredRequest = checkNotNull(access.currentRequest.value)
        assertEquals(requestKey, restoredRequest.key)
        assertTrue(restoredRequest.submitted)
        assertFalse(original.result.isCompleted)
        checkNotNull(scenario).moveToState(Lifecycle.State.STARTED)
        compose.waitForIdle()
        checkNotNull(scenario).onActivity { recreated ->
            assertNotSame(originalActivity, recreated)
            assertNull(shadowOf(recreated).nextStartedActivityForResult)
            assertTrue(recreated.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent("consent")))
        }
        assertIs<ScreenCaptureAccess.ConsentResult.Granted>(outcome(original))
        assertEquals(listOf(Looper.getMainLooper().thread), original.preparations)
        assertEquals(Lifecycle.State.STARTED, checkNotNull(scenario).state)
        checkNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        val newer = request()
        var newerRequestCode = 0
        checkNotNull(scenario).onActivity { recreated ->
            newerRequestCode = checkNotNull(shadowOf(recreated).nextStartedActivityForResult).requestCode
            recreated.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent("stale"))
        }
        compose.waitForIdle()
        assertFalse(newer.result.isCompleted)
        assertTrue(newer.preparations.isEmpty())
        assertEquals(1, original.preparations.size)
        checkNotNull(scenario).onActivity { recreated ->
            assertTrue(recreated.activityResultRegistry.dispatchResult(newerRequestCode, Activity.RESULT_CANCELED, null))
        }
        assertReason(newer, ScreenCaptureAccess.NoGrantReason.Declined)
    }

    // Cases: CORE-UI-07
    @Test
    fun recreatedActivityCanDeliverOriginalDenialWithoutForegroundPreparation() {
        createSettings(educationCompleted = true)
        launchHost()
        val pending = request()
        var requestCode = 0
        checkNotNull(scenario).onActivity { requestCode = checkNotNull(shadowOf(it).nextStartedActivityForResult).requestCode }
        checkNotNull(scenario).recreate()
        compose.waitForIdle()
        assertFalse(pending.result.isCompleted)
        checkNotNull(scenario).onActivity {
            assertNull(shadowOf(it).nextStartedActivityForResult)
            it.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
        }
        assertReason(pending, ScreenCaptureAccess.NoGrantReason.Declined)
        assertTrue(pending.preparations.isEmpty())
    }

    // Cases: CORE-UI-08
    @Test
    fun finishingSubmittingActivitySettlesSubmittedRequest() {
        assertFinishingActivitySettles(recreate = false)
    }

    // Cases: CORE-UI-08
    @Test
    fun finishingRecreatedSubmittingActivitySettlesRestoredRequest() {
        assertFinishingActivitySettles(recreate = true)
    }

    // Cases: CORE-UI-09
    @Test
    fun differentRegistryDisablesFinishingHookAndAttemptCancellationClearsRequest() {
        val registry = RecordingConsentRegistry()
        createSettings(educationCompleted = true)
        launchHost(registry)
        val pending = request()
        assertEquals(1, registry.launches.size)
        checkNotNull(scenario).onActivity { it.finish(); assertTrue(it.isFinishing) }
        checkNotNull(scenario).moveToState(Lifecycle.State.DESTROYED)
        compose.waitForIdle()
        assertEquals(Lifecycle.State.DESTROYED, checkNotNull(scenario).state)
        assertFalse(pending.result.isCompleted)
        assertTrue(checkNotNull(access.currentRequest.value).submitted)
        compose.runOnIdle { pending.job.cancel() }
        compose.waitForIdle()
        assertTrue(pending.result.isCancelled)
        assertNull(access.currentRequest.value)
        compose.runOnIdle { registry.reply(0, Activity.RESULT_OK, Intent("late")) }
        assertTrue(pending.preparations.isEmpty())
    }

    // Cases: CORE-UI-10
    @Test
    fun launcherFailureKeepsOriginalCauseAndLaterRequestCanSucceed() {
        val failure = IllegalStateException("Fixture launch failure")
        val registry = RecordingConsentRegistry().apply { launchFailure = failure }
        createSettings(educationCompleted = true)
        launchHost(registry)
        val failed = request()
        compose.waitUntil(5_000) { failed.result.isCompleted }
        val thrown = runBlocking { failed.result.await() }.exceptionOrNull()
        val chain = generateSequence(thrown) { it.cause }.toList()
        assertTrue(chain.any { it is ConsentLaunchException }, "Expected ConsentLaunchException, got $chain")
        assertTrue(
            chain.any { it === failure },
            "Original launch failure missing: $chain"
        )
        assertNull(access.currentRequest.value)
        assertTrue(failed.preparations.isEmpty())
        compose.runOnIdle { registry.launchFailure = null }
        val later = request()
        compose.runOnIdle { registry.reply(1, Activity.RESULT_OK, Intent("consent")) }
        assertIs<ScreenCaptureAccess.ConsentResult.Granted>(outcome(later))
        assertEquals(1, later.preparations.size)
    }

    private fun createSettings(
        educationCompleted: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        initialize: Boolean = true,
    ) {
        settingsContext = CoreSettingsTestContext(application, temporaryFolder.newFolder())
        runBlocking { seedCoreSettings(settingsContext, """{"screenCaptureEducationCompleted":$educationCompleted}""") }
        settings = StreamingSettingsStore(settingsContext, dispatcher)
        if (initialize) runBlocking { withTimeout(5.seconds) { settings.initialize() } }
    }

    private fun launchHost(registry: RecordingConsentRegistry? = null) {
        loadKoinModules(module {
            single { access }
            single { settings }
        })
        scenario = launchCoreComposeHost {
            if (mounted.value) {
                if (registry == null) ScreenCaptureConsentHost()
                else CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { ScreenCaptureConsentHost() }
            }
        }
        compose.waitForIdle()
    }

    private data class PendingAttempt(
        val job: CompletableJob,
        val result: Deferred<Result<ScreenCaptureAccess.ConsentResult>>,
        val preparations: MutableList<Thread>,
    )

    private fun request(): PendingAttempt {
        val job = SupervisorJob(scope.coroutineContext[Job]).also { attempts.add(it) }
        val preparations = mutableListOf<Thread>()
        val attempt = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("compose-test")))
        lateinit var result: Deferred<Result<ScreenCaptureAccess.ConsentResult>>
        compose.runOnIdle {
            result = CoroutineScope(job + Dispatchers.Main.immediate).async {
                runCatching { access.requestConsent(attempt, job) { preparations.add(Thread.currentThread()); true } }
            }
        }
        compose.waitForIdle()
        return PendingAttempt(job, result, preparations)
    }

    private fun outcome(pending: PendingAttempt): ScreenCaptureAccess.ConsentResult {
        compose.waitForIdle()
        compose.waitUntil(5_000) { pending.result.isCompleted }
        return runBlocking { pending.result.await().getOrThrow() }
    }

    private fun assertReason(pending: PendingAttempt, reason: ScreenCaptureAccess.NoGrantReason) {
        assertEquals(ScreenCaptureAccess.ConsentResult.NotGranted(reason), outcome(pending))
        assertTrue(pending.job.isActive)
        assertNull(access.currentRequest.value)
    }

    private fun assertEducationUnchanged(registry: RecordingConsentRegistry, pending: PendingAttempt) {
        compose.onNodeWithText(educationTitle).assertDoesNotExist()
        assertEquals(0, registry.launches.size)
        assertTrue(pending.preparations.isEmpty())
        assertTrue(awaitSettingsWrite().isSuccess)
        assertFalse(runBlocking { readCoreSettings(settingsContext).screenCaptureEducationCompleted })
    }

    /** Join earlier admitted DataStore edits without blocking the Compose dispatcher. */
    private fun awaitSettingsWrite(pump: () -> Unit = {}): Result<Unit> {
        val write = scope.async(Dispatchers.Default) { runCatching { settings.updateData { this } } }
        compose.waitUntil(5_000) { pump(); write.isCompleted }
        return runBlocking { write.await() }
    }

    private fun awaitSettingsInitialization(scheduler: TestCoroutineScheduler) {
        val initialized = scope.async(Dispatchers.Default) { settings.initialize() }
        compose.waitUntil(5_000) { scheduler.runCurrent(); initialized.isCompleted }
        runBlocking { initialized.await() }
    }

    private fun assertFinishingActivitySettles(recreate: Boolean) {
        createSettings(educationCompleted = true)
        launchHost()
        val pending = request()
        checkNotNull(scenario).onActivity { assertTrue(shadowOf(it).nextStartedActivityForResult != null) }
        if (recreate) {
            checkNotNull(scenario).recreate()
            compose.waitForIdle()
            assertFalse(pending.result.isCompleted)
            checkNotNull(scenario).onActivity { assertNull(shadowOf(it).nextStartedActivityForResult) }
        }
        checkNotNull(scenario).onActivity { it.finish(); assertTrue(it.isFinishing) }
        checkNotNull(scenario).moveToState(Lifecycle.State.DESTROYED)
        compose.waitForIdle()
        assertEquals(Lifecycle.State.DESTROYED, checkNotNull(scenario).state)
        assertReason(pending, ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        assertTrue(pending.preparations.isEmpty())
    }

    private class RecordingConsentRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        val launches = mutableListOf<Int>()
        var launchFailure: Throwable? = null

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches.add(requestCode)
            launchFailure?.let { throw it }
        }

        fun reply(index: Int, resultCode: Int, data: Intent? = null) {
            dispatchResult(launches[index], resultCode, data)
        }
    }
}
