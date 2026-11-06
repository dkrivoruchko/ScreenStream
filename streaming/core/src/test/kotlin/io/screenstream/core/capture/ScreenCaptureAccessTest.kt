package io.screenstream.core.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import androidx.activity.result.ActivityResult
import io.screenstream.core.module.StreamingModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [ScreenCaptureAccessTest.ProjectionManagerShadow::class])
internal class ScreenCaptureAccessTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val attempts = mutableListOf<Job>()
    private val host = Any()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var access: ScreenCaptureAccessImpl
    private lateinit var platform: ProjectionManagerShadow

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        access = coordinator()
        platform = Shadow.extract(context.getSystemService(MediaProjectionManager::class.java))
    }

    @After
    fun tearDown() {
        scope.cancel()
        attempts.forEach { it.cancel() }
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    // Cases: CORE-CAP-01, CORE-CAP-02, CORE-CAP-06
    @Test
    fun noHostAndBusyHaveDistinctImmediateOutcomes() {
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        access.onHostResumed(host)
        val first = request()
        val original = checkNotNull(access.currentRequest.value)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.Busy)
        assertSame(original, access.currentRequest.value)
        first.result.cancel()
        scheduler.runCurrent()
        assertNull(access.currentRequest.value)
        assertReason(requestWithoutHost(), ScreenCaptureAccess.NoGrantReason.HostUnavailable)
    }

    // Cases: CORE-CAP-01
    @Test
    fun latestReferenceHostOwnsUnsubmittedRequestAndFallbackCanSubmitNext() {
        val firstHost = String(charArrayOf('h'))
        val equalHost = String(charArrayOf('h'))
        access.onHostResumed(firstHost)
        val pending = request()
        val key = key()
        assertTrue(access.canSubmit(key, firstHost))
        assertFalse(access.canSubmit(key, equalHost))
        access.onHostResumed(equalHost)
        scheduler.runCurrent()
        assertReason(pending, ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        val next = request()
        assertTrue(access.canSubmit(key(), equalHost))
        access.onHostInactive(firstHost)
        assertFalse(next.result.isCompleted)
        access.onHostInactive(equalHost)
        scheduler.runCurrent()
        assertReason(next, ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        access.onHostResumed(firstHost)
        access.onHostResumed(equalHost)
        access.onHostInactive(equalHost)
        request()
        assertTrue(access.canSubmit(key(), firstHost))
    }

    // Cases: CORE-CAP-03
    @Test
    fun staleEducationCancellationAndWrongHostCannotSettleRequest() {
        access.onHostResumed(host)
        val pending = request()
        access.cancelBeforeSubmission("stale", host)
        access.cancelBeforeSubmission(key(), Any())
        assertFalse(pending.result.isCompleted)
        access.cancelBeforeSubmission(key(), host)
        scheduler.runCurrent()
        assertReason(pending, ScreenCaptureAccess.NoGrantReason.EducationDeclined)
        assertNull(access.currentRequest.value)
    }

    // Cases: CORE-CAP-04, CORE-CAP-05
    @Test
    fun submittedRequestSurvivesHostChangesAndForegroundPreparationWhilePaused() {
        access.onHostResumed(host)
        var preparationCount = 0
        val pending = request(prepare = {
            preparationCount++
            true
        })
        val key = key()
        assertNotNull(access.submit(key, host, false))
        assertNull(access.submit(key, host, false))
        assertTrue(checkNotNull(access.currentRequest.value).submitted)
        access.onHostInactive(host)
        val replacementHost = Any()
        access.onHostResumed(replacementHost)
        access.onHostInactive(replacementHost)
        access.deliver("stale", ActivityResult(Activity.RESULT_CANCELED, null))
        assertEquals(0, preparationCount)
        assertFalse(pending.result.isCompleted)
        access.deliver(key, success())
        assertEquals(1, preparationCount)
        scheduler.runCurrent()
        assertTrue(pending.result.getCompleted() is ScreenCaptureAccess.ConsentResult.Granted)
        assertNull(access.currentRequest.value)
        assertSame(platform.projection, access.createProjection(grant(pending)))
        access.deliver(key, success())
        access.deliver(key, ActivityResult(Activity.RESULT_CANCELED, null))
        assertEquals(1, preparationCount)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.Busy)
        access.finishProjectionCreation(pending.attempt)

        access.onHostResumed(host)
        var successorPreparationCount = 0
        val successor = request(prepare = {
            successorPreparationCount++
            true
        })
        val successorRequest = checkNotNull(access.currentRequest.value)
        access.deliver(key, success())
        scheduler.runCurrent()
        assertSame(successorRequest, access.currentRequest.value)
        assertFalse(successor.result.isCompleted)
        assertEquals(1, preparationCount)
        assertEquals(0, successorPreparationCount)
    }

    @Test
    fun submittedAbandonmentSettlesWithoutPreparingForegroundAndAllowsFreshConsent() {
        access.onHostResumed(host)
        var preparationCount = 0
        val pending = request(prepare = {
            preparationCount++
            true
        })
        val submittedKey = key()
        assertNotNull(access.submit(submittedKey, host, false))
        access.onHostInactive(host)
        val replacementHost = Any()
        access.onHostResumed(replacementHost)

        access.abandonSubmitted(submittedKey)
        access.abandonSubmitted(submittedKey)
        access.deliver(submittedKey, success())
        scheduler.runCurrent()
        assertReason(pending, ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        assertTrue(pending.job.isActive)
        assertEquals(0, preparationCount)
        assertNull(access.currentRequest.value)

        val next = request()
        val nextKey = key()
        assertNotNull(access.submit(nextKey, replacementHost, false))
        access.deliver(nextKey, ActivityResult(Activity.RESULT_CANCELED, null))
        scheduler.runCurrent()
        assertReason(next, ScreenCaptureAccess.NoGrantReason.Declined)
    }

    @Test
    fun unknownAndUnsubmittedAbandonmentLeaveConsentAvailable() {
        access.onHostResumed(host)
        val pending = request()
        val pendingKey = key()
        access.abandonSubmitted("unknown")
        access.abandonSubmitted(pendingKey)
        scheduler.runCurrent()
        assertFalse(pending.result.isCompleted)
        assertTrue(access.canSubmit(pendingKey, host))
        assertNotNull(access.submit(pendingKey, host, false))
        access.abandonSubmitted("unknown")
        access.deliver(pendingKey, ActivityResult(Activity.RESULT_CANCELED, null))
        scheduler.runCurrent()
        assertReason(pending, ScreenCaptureAccess.NoGrantReason.Declined)
    }

    @Test
    fun abandonedKeyCannotSettleNewerSubmittedConsent() {
        access.onHostResumed(host)
        val first = request()
        val firstKey = key()
        assertNotNull(access.submit(firstKey, host, false))
        access.abandonSubmitted(firstKey)
        scheduler.runCurrent()
        assertReason(first, ScreenCaptureAccess.NoGrantReason.HostUnavailable)

        var preparationCount = 0
        val next = request(prepare = {
            preparationCount++
            true
        })
        val nextKey = key()
        assertNotNull(access.submit(nextKey, host, false))
        access.abandonSubmitted(firstKey)
        access.deliver(firstKey, success())
        assertFalse(access.failLaunch(firstKey, IllegalStateException("late launcher failure")))
        scheduler.runCurrent()
        assertFalse(next.result.isCompleted)
        assertEquals(0, preparationCount)
        assertEquals(nextKey, key())

        access.deliver(nextKey, success())
        scheduler.runCurrent()
        assertTrue(next.result.getCompleted() is ScreenCaptureAccess.ConsentResult.Granted)
        assertEquals(1, preparationCount)
    }

    @Test
    fun successfulResultBeforeAbandonmentPreservesGrantAndForegroundPreparation() {
        access.onHostResumed(host)
        var preparationCount = 0
        val pending = request(prepare = {
            preparationCount++
            true
        })
        val submittedKey = key()
        assertNotNull(access.submit(submittedKey, host, false))
        access.deliver(submittedKey, success())
        access.abandonSubmitted(submittedKey)
        scheduler.runCurrent()
        assertEquals(1, preparationCount)
        assertTrue(pending.job.isActive)
        assertNull(access.currentRequest.value)
        assertSame(platform.projection, access.createProjection(grant(pending)))
        access.finishProjectionCreation(pending.attempt)
    }

    @Test
    fun attemptCancellationAroundAbandonmentCannotLeavePendingConsent() {
        access.onHostResumed(host)
        var preparationCount = 0
        for (abandonFirst in listOf(false, true)) {
            val pending = request(prepare = {
                preparationCount++
                true
            })
            val submittedKey = key()
            assertNotNull(access.submit(submittedKey, host, false))
            if (abandonFirst) access.abandonSubmitted(submittedKey)
            pending.job.cancel()
            if (!abandonFirst) access.abandonSubmitted(submittedKey)
            access.deliver(submittedKey, success())
            scheduler.runCurrent()
            assertTrue(pending.result.isCancelled)
            assertNull(access.currentRequest.value)
        }
        assertEquals(0, preparationCount)
        val next = request()
        val nextKey = key()
        assertNotNull(access.submit(nextKey, host, false))
        access.deliver(nextKey, ActivityResult(Activity.RESULT_CANCELED, null))
        scheduler.runCurrent()
        assertReason(next, ScreenCaptureAccess.NoGrantReason.Declined)
    }

    // Cases: CORE-CAP-04, CORE-CAP-06
    @Test
    fun cancelledChildClearsCorrelationAndLateResultCannotAffectSuccessor() {
        access.onHostResumed(host)
        val cancelled = request()
        val oldKey = key()
        access.submit(oldKey, host, false)
        cancelled.result.cancel()
        scheduler.runCurrent()
        assertNull(access.currentRequest.value)
        val successor = request()
        val newKey = key()
        access.deliver(oldKey, success())
        assertFalse(access.failLaunch(oldKey, IllegalStateException("late")))
        assertEquals(newKey, key())
        assertFalse(successor.result.isCompleted)
        access.submit(newKey, host, false)
        access.deliver(newKey, ActivityResult(Activity.RESULT_CANCELED, null))
        scheduler.runCurrent()
        assertReason(successor, ScreenCaptureAccess.NoGrantReason.Declined)
    }

    // Cases: CORE-CAP-05
    @Test
    fun declineRejectAndTechnicalResultFailuresRemainDistinct() {
        access.onHostResumed(host)
        var declinedPreparationCount = 0
        val declined = request(prepare = {
            declinedPreparationCount++
            true
        })
        val declinedKey = key()
        access.submit(declinedKey, host, false)
        access.deliver(declinedKey, ActivityResult(Activity.RESULT_CANCELED, null))
        scheduler.runCurrent()
        assertReason(declined, ScreenCaptureAccess.NoGrantReason.Declined)
        assertEquals(0, declinedPreparationCount)
        var rejectedPreparationCount = 0
        val rejected = request(prepare = {
            rejectedPreparationCount++
            false
        })
        val rejectedKey = key()
        completeConsent()
        assertReason(rejected, ScreenCaptureAccess.NoGrantReason.ForegroundRejected)
        assertEquals(1, rejectedPreparationCount)
        var missingPreparationCount = 0
        val missing = request(prepare = {
            missingPreparationCount++
            true
        })
        val missingKey = key()
        access.submit(missingKey, host, false)
        access.deliver(missingKey, ActivityResult(Activity.RESULT_OK, null))
        scheduler.runCurrent()
        assertFailure<IllegalStateException>(missing)
        assertEquals(0, missingPreparationCount)
        var prematurePreparationCount = 0
        val premature = request(prepare = {
            prematurePreparationCount++
            true
        })
        val prematureKey = key()
        access.deliver(prematureKey, success())
        scheduler.runCurrent()
        assertFailure<IllegalStateException>(premature)
        assertEquals(0, prematurePreparationCount)

        var successorPreparationCount = 0
        val successor = request(prepare = {
            successorPreparationCount++
            true
        })
        val successorRequest = checkNotNull(access.currentRequest.value)
        val settledResults = listOf(
            declinedKey to ActivityResult(Activity.RESULT_CANCELED, null),
            rejectedKey to success(),
            missingKey to ActivityResult(Activity.RESULT_OK, null),
            prematureKey to success(),
        )
        for ((settledKey, settledResult) in settledResults) {
            access.deliver(settledKey, settledResult)
            access.deliver(settledKey, success())
        }
        scheduler.runCurrent()
        assertSame(successorRequest, access.currentRequest.value)
        assertFalse(successor.result.isCompleted)
        assertEquals(0, declinedPreparationCount)
        assertEquals(1, rejectedPreparationCount)
        assertEquals(0, missingPreparationCount)
        assertEquals(0, prematurePreparationCount)
        assertEquals(0, successorPreparationCount)
    }

    // Cases: CORE-CAP-05
    @Test
    fun launcherFailureRequiresMatchingSubmittedKeyAndPreservesCause() {
        access.onHostResumed(host)
        val pending = request()
        val key = key()
        val cause = IllegalArgumentException("launch failed")
        assertFalse(access.failLaunch(key, cause))
        access.submit(key, host, false)
        assertFalse(access.failLaunch("stale", cause))
        assertTrue(access.failLaunch(key, cause))
        access.abandonSubmitted(key)
        scheduler.runCurrent()
        val failure = assertFailure<ConsentLaunchException>(pending)
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === cause })
        assertFalse(access.failLaunch(key, cause))
    }

    // Cases: CORE-CAP-05, CORE-CAP-07
    @Test
    fun foregroundFailurePropagatesAndCancelledAttemptCannotAcquireDeliveredGrant() {
        access.onHostResumed(host)
        val cause = IllegalArgumentException("foreground failed")
        val failed = request(prepare = { throw cause })
        completeConsent()
        val failure = assertFailure<IllegalArgumentException>(failed)
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === cause })
        val pending = request()
        completeConsent()
        val grant = grant(pending)
        assertFalse(pending.result.isActive)
        assertTrue(pending.job.isActive)
        pending.job.cancel()
        assertThrows(CancellationException::class.java) { access.createProjection(grant) }
        assertEquals(0, platform.calls)
    }

    // Cases: CORE-CAP-02, CORE-CAP-07
    @Test
    fun grantsAreCoordinatorBoundSingleUseAndGuardBlocksUntilMatchingFinish() {
        access.onHostResumed(host)
        val first = request()
        completeConsent()
        val firstGrant = grant(first)
        val foreign = coordinator()
        assertThrows(IllegalStateException::class.java) { foreign.createProjection(firstGrant) }
        assertThrows(IllegalStateException::class.java) { access.createProjection(object : ScreenCaptureAccess.Grant {}) }
        val projection = access.createProjection(firstGrant)
        assertSame(platform.projection, projection)
        assertThrows(IllegalStateException::class.java) { access.createProjection(firstGrant) }
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.Busy)
        access.finishProjectionCreation(attempt())
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.Busy)
        access.finishProjectionCreation(first.attempt)
        access.finishProjectionCreation(first.attempt)
        assertFalse(request().result.isCompleted)
    }

    // Cases: CORE-CAP-07
    @Test
    fun platformFailureConsumesGrantAndRetainsGuardUntilExplicitFinish() {
        access.onHostResumed(host)
        val pending = request()
        completeConsent()
        val grant = grant(pending)
        val failure = SecurityException("platform")
        platform.acquire = { throw failure }
        assertSame(failure, assertThrows(SecurityException::class.java) { access.createProjection(grant) })
        assertEquals(1, platform.calls)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.Busy)
        assertThrows(IllegalStateException::class.java) { access.createProjection(grant) }
        access.finishProjectionCreation(pending.attempt)
        assertFalse(request().result.isCompleted)
    }

    // Cases: CORE-CAP-07
    @Test
    fun cancelledAttemptRetainsSuccessfulInFlightProjectionAndGuardUntilMatchingFinish() {
        access.onHostResumed(host)
        val pending = request()
        completeConsent()
        val grant = grant(pending)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        platform.acquire = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Platform call was not released" }
            platform.projection
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<MediaProjection> { access.createProjection(grant) }
            assertTrue("Acquisition did not enter", entered.await(5, TimeUnit.SECONDS))
            pending.job.cancel()
            assertTrue(pending.job.isCancelled)
            access.finishProjectionCreation(attempt())
            assertReason(request(), ScreenCaptureAccess.NoGrantReason.Busy)
            access.finishProjectionCreation(pending.attempt)
            assertReason(request(), ScreenCaptureAccess.NoGrantReason.Busy)
            release.countDown()
            assertSame(platform.projection, result.get(5, TimeUnit.SECONDS))
            assertEquals(1, platform.calls)
            access.finishProjectionCreation(attempt())
            assertReason(request(), ScreenCaptureAccess.NoGrantReason.Busy)
            access.finishProjectionCreation(pending.attempt)
            assertFalse(request().result.isCompleted)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    // Cases: CORE-CAP-09
    @Test
    @Config(sdk = [24, 33])
    fun legacyReuseConsumesSavedConsentWithoutHostAndSaveWorksOnceAfterJobCancellation() {
        access.onHostResumed(host)
        val original = request()
        completeConsent()
        val oldGrant = grant(original)
        access.createProjection(oldGrant)
        access.finishProjectionCreation(original.attempt)
        original.job.cancel()
        access.saveConsentForReuse(oldGrant)
        access.onHostInactive(host)
        val reused = request()
        assertTrue(reused.result.getCompleted() is ScreenCaptureAccess.ConsentResult.Granted)
        assertNull(access.currentRequest.value)
        access.saveConsentForReuse(oldGrant)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        access.createProjection(grant(reused))
        access.finishProjectionCreation(reused.attempt)
        assertEquals(2, platform.calls)
    }

    // Cases: CORE-CAP-09, CORE-CAP-10
    @Test
    @Config(sdk = [33, 34])
    fun unconsumedAndOlderGrantsCannotSaveConsentAndModernSaveRequiresFreshHost() {
        access.onHostResumed(host)
        val original = request()
        completeConsent()
        val oldGrant = grant(original)
        access.saveConsentForReuse(oldGrant)
        access.onHostInactive(host)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        access.onHostResumed(host)
        val newer = request()
        completeConsent()
        access.createProjection(oldGrant)
        access.finishProjectionCreation(original.attempt)
        access.saveConsentForReuse(oldGrant)
        access.onHostInactive(host)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        access.createProjection(grant(newer))
        access.finishProjectionCreation(newer.attempt)
        access.saveConsentForReuse(grant(newer))
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            assertReason(request(), ScreenCaptureAccess.NoGrantReason.HostUnavailable)
        } else {
            assertTrue(request().result.getCompleted() is ScreenCaptureAccess.ConsentResult.Granted)
        }
    }

    // Cases: CORE-CAP-08
    @Test
    @Config(sdk = [33])
    fun optedInLegacyRetryIncludes1500msBoundaryAndWaits100msOnlyOnce() {
        access.onHostResumed(host)
        val pending = request()
        completeConsent()
        ShadowSystemClock.advanceBy(Duration.ofMillis(1500))
        val before = SystemClock.elapsedRealtime()
        platform.acquire = { if (platform.calls == 1) throw SecurityException("race") else platform.projection }
        assertSame(platform.projection, access.createProjection(grant(pending), true))
        assertEquals(2, platform.calls)
        assertEquals(100L, SystemClock.elapsedRealtime() - before)
        access.finishProjectionCreation(pending.attempt)
    }

    // Cases: CORE-CAP-08
    @Test
    @Config(sdk = [33, 34])
    fun modernOrOutOfWindowOrDefaultAcquisitionDoesNotRetry() {
        access.onHostResumed(host)
        repeat(2) { index ->
            val pending = request()
            completeConsent()
            if (index == 1) ShadowSystemClock.advanceBy(Duration.ofMillis(1501))
            val before = platform.calls
            platform.acquire = { throw SecurityException("no retry") }
            assertThrows(SecurityException::class.java) { access.createProjection(grant(pending), index == 1) }
            assertEquals(before + 1, platform.calls)
            access.finishProjectionCreation(pending.attempt)
        }
    }

    // Cases: CORE-CAP-02, CORE-CAP-06
    @Test
    fun inactiveAttemptCannotOpenConsentAndCancellationAfterPromotionPreventsGrantDelivery() {
        access.onHostResumed(host)
        val inactive = Job().also { it.cancel() }
        val rejected = scope.async { access.requestConsent(attempt(), inactive) { error("Must not prepare") } }
        scheduler.runCurrent()
        assertTrue(rejected.getCompletionExceptionOrNull() is CancellationException)
        assertNull(access.currentRequest.value)
        lateinit var pending: Pending
        var promoted = false
        pending = request(prepare = {
            promoted = true
            pending.job.cancel()
            true
        })
        completeConsent()
        assertTrue(promoted)
        assertTrue(pending.result.isCancelled)
        assertNull(access.currentRequest.value)
        assertFalse(request().result.isCompleted)
    }

    // Cases: CORE-CAP-03, CORE-CAP-06
    @Test
    fun cancellingBeforeSubmissionInvalidatesLateSubmitAndAdmitsNextAttempt() {
        access.onHostResumed(host)
        val pending = request()
        val stale = key()
        pending.job.cancel()
        scheduler.runCurrent()
        assertTrue(pending.result.isCancelled)
        assertNull(access.submit(stale, host, false))
        val next = request()
        access.cancelBeforeSubmission(stale, host)
        assertFalse(next.result.isCompleted)
        val key = key()
        access.submit(key, host, false)
        access.cancelBeforeSubmission(key, host)
        access.deliver(key, success())
        scheduler.runCurrent()
        assertTrue(next.result.getCompleted() is ScreenCaptureAccess.ConsentResult.Granted)
    }

    // Cases: CORE-CAP-05
    @Test
    fun cancellationLauncherCauseRemainsCancellation() {
        access.onHostResumed(host)
        val pending = request()
        val key = key()
        access.submit(key, host, false)
        assertTrue(access.failLaunch(key, CancellationException("launcher cancelled")))
        scheduler.runCurrent()
        assertTrue(pending.result.isCancelled)
        assertNull(access.currentRequest.value)
    }

    // Cases: CORE-CAP-08
    @Test
    @Config(sdk = [34])
    fun modernOptedInAcquisitionDoesNotRetryInsideLegacyWindow() {
        access.onHostResumed(host)
        val pending = request()
        completeConsent()
        platform.acquire = { throw SecurityException("modern") }
        assertThrows(SecurityException::class.java) { access.createProjection(grant(pending), true) }
        assertEquals(1, platform.calls)
        access.finishProjectionCreation(pending.attempt)
    }

    // Cases: CORE-CAP-08
    @Test
    @Config(sdk = [33])
    fun cancellingAttemptOnFirstLegacyFailurePreventsRetry() {
        access.onHostResumed(host)
        val pending = request()
        completeConsent()
        platform.acquire = {
            pending.job.cancel()
            throw SecurityException("cancel")
        }
        assertThrows(CancellationException::class.java) { access.createProjection(grant(pending), true) }
        assertEquals(1, platform.calls)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.Busy)
        access.finishProjectionCreation(pending.attempt)
    }

    // Cases: CORE-CAP-09
    @Test
    @Config(sdk = [33])
    fun rejectedSavedForegroundStillConsumesConsentAndNewCoordinatorStartsFresh() {
        access.onHostResumed(host)
        val original = request()
        completeConsent()
        val grant = grant(original)
        access.createProjection(grant)
        access.finishProjectionCreation(original.attempt)
        access.saveConsentForReuse(grant)
        val replacement = coordinator()
        val newProcess = scope.async { replacement.requestConsent(attempt(), Job()) { true } }
        scheduler.runCurrent()
        assertEquals(ScreenCaptureAccess.ConsentResult.NotGranted(ScreenCaptureAccess.NoGrantReason.HostUnavailable), newProcess.getCompleted())
        access.onHostInactive(host)
        assertReason(request(prepare = { false }), ScreenCaptureAccess.NoGrantReason.ForegroundRejected)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.HostUnavailable)
    }

    // Cases: CORE-CAP-07
    @Test
    fun overlappingAcquisitionDoesNotConsumeSecondValidUnusedGrant() {
        access.onHostResumed(host)
        val first = request()
        completeConsent()
        val second = request()
        completeConsent()
        val firstGrant = grant(first)
        val secondGrant = grant(second)
        assertSame(platform.projection, access.createProjection(firstGrant))
        assertThrows(IllegalStateException::class.java) { access.createProjection(secondGrant) }
        assertEquals(1, platform.calls)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.Busy)
        access.finishProjectionCreation(first.attempt)
        assertSame(platform.projection, access.createProjection(secondGrant))
        assertEquals(2, platform.calls)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.Busy)
        access.finishProjectionCreation(second.attempt)
        assertFalse(request().result.isCompleted)
    }

    // Cases: CORE-CAP-08
    @Test
    @Config(sdk = [33])
    fun secondLegacyRetryFailurePropagatesAfterExactlyTwoCallsAndRetainsGuard() {
        access.onHostResumed(host)
        val pending = request()
        completeConsent()
        val first = SecurityException("first acquisition")
        val second = SecurityException("retry acquisition")
        platform.acquire = { throw if (platform.calls == 1) first else second }
        val before = SystemClock.elapsedRealtime()
        assertSame(second, assertThrows(SecurityException::class.java) {
            access.createProjection(grant(pending), true)
        })
        assertEquals(2, platform.calls)
        assertEquals(100L, SystemClock.elapsedRealtime() - before)
        assertReason(request(), ScreenCaptureAccess.NoGrantReason.Busy)
        access.finishProjectionCreation(pending.attempt)
        assertFalse(request().result.isCompleted)
    }

    private fun coordinator(): ScreenCaptureAccessImpl = ScreenCaptureAccessImpl(context, object : ConsentRequestObserver {
        override fun savedConsentTaken(attempt: StreamingModule.CaptureAttemptId) = Unit
        override fun systemConsentSubmitted(attempt: StreamingModule.CaptureAttemptId, educationAccepted: Boolean) = Unit
    })

    private fun attempt(): StreamingModule.CaptureAttemptId = StreamingModule.CaptureAttemptId(
        StreamingModule.InstanceId(StreamingModule.Id("test")),
    )

    private data class Pending(
        val attempt: StreamingModule.CaptureAttemptId,
        val job: Job,
        val result: Deferred<ScreenCaptureAccess.ConsentResult>,
    )

    private fun request(prepare: () -> Boolean = { true }): Pending {
        val attempt = attempt()
        val job = Job(scope.coroutineContext[Job]).also { attempts.add(it) }
        val result = CoroutineScope(scope.coroutineContext + job).async {
            access.requestConsent(attempt, job, prepare)
        }
        scheduler.runCurrent()
        return Pending(attempt, job, result)
    }

    private fun requestWithoutHost(): Pending {
        access.onHostInactive(host)
        return request()
    }

    private fun key(): String = checkNotNull(access.currentRequest.value).key
    private fun completeConsent() {
        val key = key()
        assertNotNull(access.submit(key, host, false))
        access.deliver(key, success())
        scheduler.runCurrent()
    }

    private fun success(): ActivityResult = ActivityResult(Activity.RESULT_OK, Intent().putExtra("consent", "value"))
    private fun assertReason(pending: Pending, reason: ScreenCaptureAccess.NoGrantReason) {
        assertEquals(ScreenCaptureAccess.ConsentResult.NotGranted(reason), pending.result.getCompleted())
    }

    private fun grant(pending: Pending): ScreenCaptureAccess.Grant =
        (pending.result.getCompleted() as ScreenCaptureAccess.ConsentResult.Granted).grant

    private inline fun <reified T : Throwable> assertFailure(pending: Pending): T {
        val failure = pending.result.getCompletionExceptionOrNull()
        assertTrue("Expected ${T::class.java.name}, got $failure", failure is T)
        return failure as T
    }

    @Implements(MediaProjectionManager::class)
    class ProjectionManagerShadow {
        val projection: MediaProjection = Shadow.newInstanceOf(MediaProjection::class.java)
        var calls = 0
        var acquire: () -> MediaProjection = { projection }

        @Implementation
        fun createScreenCaptureIntent(): Intent = Intent("test.screen.capture")

        @Implementation
        fun getMediaProjection(resultCode: Int, data: Intent): MediaProjection {
            check(resultCode == Activity.RESULT_OK)
            check(data.getStringExtra("consent") == "value")
            calls++
            return acquire()
        }
    }
}
