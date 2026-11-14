package io.screenstream.core.module

import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import io.screenstream.core.CoreKoinTestRule
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.manager.StreamingModuleActionReceiver
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE, sdk = [34],
    shadows = [ServicePlatformShadow::class, ServiceNotificationShadow::class, ServiceAppOpsShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
internal class StreamingModuleServiceTest {
    @get:Rule(order = Int.MIN_VALUE)
    val koin = CoreKoinTestRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // Cases: CORE-SVC-01
    @Test
    @Config(sdk = [24, 26, 29, 34, 37])
    fun capturePromotionKeepsExitUntilConfirmationAndReturnsToWaitingForeground(): Unit = runBlocking {
        host().use { host ->
            val attempt = host.attempt()
            assertIs<StreamingModuleNotifications.ForegroundAction.Exit>(host.notifications.actions.single())
            assertEquals(417, host.platform.lastForegroundNotificationId)
            if (Build.VERSION.SDK_INT >= 29) {
                assertEquals(if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0, host.service.foregroundServiceType)
            }
            // Foreground content is required even when delivery is blocked.
            host.notifications.enabled = false
            assertTrue(host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false))
            assertEquals(417, host.platform.lastForegroundNotificationId)
            assertTrue(host.platform.isLastForegroundNotificationAttached)
            assertIs<StreamingModuleNotifications.ForegroundAction.Exit>(host.notifications.actions.last())
            if (Build.VERSION.SDK_INT >= 29) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, host.service.foregroundServiceType)

            host.service.updateCaptureNotification(attempt, active = true)
            val action = assertIs<StreamingModuleNotifications.ForegroundAction.StopStream>(host.notifications.actions.last())
            assertEquals(StreamingModuleActionReceiver.stopAction(host.service, attempt), action.pendingIntent)
            host.service.leaveCaptureForeground(attempt)
            assertIs<StreamingModuleNotifications.ForegroundAction.Exit>(host.notifications.actions.last())
            assertFalse(host.platform.isForegroundStopped)
            if (Build.VERSION.SDK_INT >= 29) {
                assertEquals(if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0, host.service.foregroundServiceType)
            }
            assertEquals(3, host.platform.promotions.size)
            assertTrue(host.platform.promotions.all { it.id == 417 })
        }
    }

    // Cases: CORE-SVC-01
    @Test
    @Config(sdk = [29, 30, 34])
    fun microphoneTypeIsAddedOnlyWhenRequiredAndSupported(): Unit = runBlocking {
        host().use { host ->
            val withMicrophone = host.attempt()
            assertTrue(host.service.enterCaptureForeground(withMicrophone, requiresMicrophoneType = true))
            val expected = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            assertEquals(expected, host.service.foregroundServiceType)
            host.service.leaveCaptureForeground(withMicrophone)
            assertTrue(host.service.enterCaptureForeground(host.attempt(), requiresMicrophoneType = false))
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, host.service.foregroundServiceType)
        }
    }

    // Cases: CORE-SVC-01
    @Test
    fun contentChangesKeepExactMaskAndSlotWithoutPromotingAndRepeatedPresentationIsInert(): Unit = runBlocking {
        host().use { host ->
            val attempt = host.attempt()
            host.service.enterCaptureForeground(attempt, requiresMicrophoneType = true)
            val promotions = host.platform.promotions.size
            val posts = host.posts.posts.size
            val builds = host.notifications.built.size
            host.service.updateCaptureNotification(attempt, active = false)
            assertEquals(builds, host.notifications.built.size)
            assertEquals(posts, host.posts.posts.size)
            host.service.updateCaptureNotification(attempt, active = true)
            val stop = host.notifications.built.last()
            assertSame(stop, host.posts.posts.last().notification)
            host.service.updateCaptureNotification(attempt, active = true)
            assertEquals(builds + 1, host.notifications.built.size)
            assertEquals(posts + 1, host.posts.posts.size)
            host.service.updateCaptureNotification(attempt, active = false)
            assertIs<StreamingModuleNotifications.ForegroundAction.Exit>(host.notifications.actions.last())
            assertSame(host.notifications.built.last(), host.posts.posts.last().notification)
            host.service.updateCaptureNotification(attempt, active = false)
            assertEquals(builds + 2, host.notifications.built.size)
            assertEquals(posts + 2, host.posts.posts.size)
            assertTrue(host.posts.posts.drop(posts).all { it.id == 417 })
            assertEquals(promotions, host.platform.promotions.size)
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                host.service.foregroundServiceType
            )
        }
    }

    // Cases: CORE-SVC-02
    @Test
    fun exactOwnerRejectsOverlapAndIgnoresOtherAttemptReleaseAndPresentation(): Unit = runBlocking {
        host().use { host ->
            val first = host.attempt()
            val other = host.attempt()
            assertTrue(host.service.enterCaptureForeground(first, requiresMicrophoneType = false))
            val builds = host.notifications.built.size
            val posts = host.posts.posts.size
            assertFailsWith<IllegalStateException> { host.service.enterCaptureForeground(other, requiresMicrophoneType = false) }
            host.service.leaveCaptureForeground(other)
            host.service.updateCaptureNotification(other, active = true)
            assertEquals(builds, host.notifications.built.size)
            assertEquals(posts, host.posts.posts.size)
            host.service.updateCaptureNotification(first, active = true)
            assertIs<StreamingModuleNotifications.ForegroundAction.StopStream>(host.notifications.actions.last())
            host.service.leaveCaptureForeground(first)
            assertTrue(host.service.enterCaptureForeground(other, requiresMicrophoneType = false))
            host.service.leaveCaptureForeground(other)
            host.service.closeForegroundAdmission()
            assertFalse(host.service.enterCaptureForeground(first, requiresMicrophoneType = false))
        }
    }

    // Cases: CORE-SVC-02
    @Test
    fun rollbackBeforePromotionAndWrongInstancePromotionHaveNoEffects(): Unit = runBlocking {
        host().use { host ->
            val wrong = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(host.controller.instanceId.moduleId))
            assertFalse(host.service.enterCaptureForeground(wrong, requiresMicrophoneType = true))
            host.service.leaveCaptureForeground(host.attempt())
            assertEquals(1, host.platform.promotions.size)
            assertEquals(1, host.notifications.built.size)
            assertIs<StreamingModuleNotifications.ForegroundAction.Exit>(host.notifications.actions.single())
            assertFalse(host.platform.isForegroundStopped)
        }
    }

    // Cases: CORE-SVC-02, CORE-SVC-03
    @Test
    fun closedAdmissionBuildsChangedContentBeforeSkippingPostAndStillPropagatesBuildFailure(): Unit = runBlocking {
        host().use { host ->
            val attempt = host.attempt()
            host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false)
            val posts = host.posts.posts.size
            val builds = host.notifications.built.size
            host.service.closeForegroundAdmission()
            assertFalse(host.service.enterCaptureForeground(host.attempt(), requiresMicrophoneType = false))
            host.service.updateCaptureNotification(attempt, active = true)
            assertEquals(builds + 1, host.notifications.built.size)
            assertEquals(posts, host.posts.posts.size)
            val failure = IllegalStateException("Closed content construction")
            host.notifications.buildFailure = failure
            assertCause(failure, assertFailsWith<IllegalStateException> { host.service.updateCaptureNotification(attempt, active = true) })
            assertFalse(host.controller.shutdown.isCompleted)
        }
    }

    // Cases: CORE-SVC-02
    @Test
    fun staleInstanceBuildsChangedContentButCannotPostPromoteOrRestoreWaiting(): Unit = runBlocking {
        host().use { host ->
            val attempt = host.attempt()
            host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false)
            val promotions = host.platform.promotions.size
            val posts = host.posts.posts.size
            val builds = host.notifications.built.size
            host.manager.exit()
            assertFalse(host.controller.handle.isCurrent())
            host.service.updateCaptureNotification(attempt, active = true)
            assertEquals(builds + 1, host.notifications.built.size)
            assertFalse(host.service.enterCaptureForeground(host.attempt(), requiresMicrophoneType = false))
            host.service.leaveCaptureForeground(attempt)
            assertEquals(promotions, host.platform.promotions.size)
            assertEquals(posts, host.posts.posts.size)
            val afterRelease = host.notifications.built.size
            host.service.leaveCaptureForeground(attempt)
            assertEquals(afterRelease, host.notifications.built.size)
        }
    }

    // Cases: CORE-SVC-03
    @Test
    fun capturePromotionFailureRetainsCauseClosesAdmissionAndRequestsOriginalControllerShutdown(): Unit = runBlocking {
        host().use { host ->
            val failure = IllegalStateException("Capture promotion")
            host.platform.setThrowInStartForeground(failure)
            val attempt = host.attempt()
            assertCause(failure, assertFailsWith<IllegalStateException> { host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false) })
            assertTrue(host.controller.shutdown.isCompleted)
            assertFalse(host.controller.handle.isCurrent())
            host.scheduler.runCurrent()
            assertIs<StreamingModuleManager.State.Failed>(host.manager.state.value)
            assertFalse(host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false))
            host.service.leaveCaptureForeground(attempt)
        }
    }

    // Cases: CORE-SVC-03
    @Test
    fun waitingRestorationFailureRetainsCauseClosesAdmissionAndClearsAttemptOwnership(): Unit = runBlocking {
        host().use { host ->
            val attempt = host.attempt()
            host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false)
            val failure = IllegalStateException("Waiting restoration")
            host.platform.setThrowInStartForeground(failure)
            assertCause(failure, assertFailsWith<IllegalStateException> { host.service.leaveCaptureForeground(attempt) })
            assertTrue(host.controller.shutdown.isCompleted)
            assertFalse(host.controller.handle.isCurrent())
            assertFalse(host.service.enterCaptureForeground(host.attempt(), requiresMicrophoneType = false))
            val builds = host.notifications.built.size
            host.service.leaveCaptureForeground(attempt)
            assertEquals(builds, host.notifications.built.size)
        }
    }

    // Cases: CORE-SVC-03
    @Test
    fun contentBuildFailureDoesNotCloseAdmissionAndSamePresentationCanBeRetried(): Unit = runBlocking {
        host().use { host ->
            val attempt = host.attempt()
            host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false)
            val posts = host.posts.posts.size
            val failure = IllegalStateException("Content construction")
            host.notifications.buildFailure = failure
            assertCause(failure, assertFailsWith<IllegalStateException> { host.service.updateCaptureNotification(attempt, active = true) })
            assertEquals(posts, host.posts.posts.size)
            assertTrue(host.controller.handle.isCurrent())
            assertFalse(host.controller.shutdown.isCompleted)
            host.notifications.buildFailure = null
            host.service.updateCaptureNotification(attempt, active = true)
            assertEquals(posts + 1, host.posts.posts.size)
            assertIs<StreamingModuleNotifications.ForegroundAction.StopStream>(host.notifications.actions.last())
        }
    }

    // Cases: CORE-SVC-03
    @Test
    fun contentPostingFailureClosesAdmissionReportsFailureAndPreservesPlatformCause(): Unit = runBlocking {
        host().use { host ->
            val attempt = host.attempt()
            host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false)
            val failure = IllegalStateException("Content posting")
            host.posts.postFailure = failure // Initial foreground promotion has already posted.
            assertCause(failure, assertFailsWith<IllegalStateException> { host.service.updateCaptureNotification(attempt, active = true) })
            assertTrue(host.controller.shutdown.isCompleted)
            assertFalse(host.controller.handle.isCurrent())
            assertFalse(host.service.enterCaptureForeground(host.attempt(), requiresMicrophoneType = false))
            val posts = host.posts.posts.size
            host.posts.postFailure = null
            host.service.updateCaptureNotification(attempt, active = true)
            assertEquals(posts, host.posts.posts.size)
        }
    }

    // Cases: CORE-SVC-04
    @Test
    @Config(sdk = [32, 33])
    fun notificationPermissionWatchIsRegisteredAndRemovedOnlyFromApi33() {
        host().use { host ->
            if (Build.VERSION.SDK_INT >= 33) {
                val watch = host.appOps.watches.single()
                assertEquals("android:post_notification", watch.op)
                assertEquals(host.service.packageName, watch.packageName)
                host.destroy()
                assertEquals(listOf(watch.listener), host.appOps.removals)
            } else {
                assertTrue(host.appOps.watches.isEmpty())
                host.destroy()
                assertTrue(host.appOps.removals.isEmpty())
            }
        }
    }

    // Cases: CORE-SVC-04
    @Test
    fun permissionCallbacksFilterPackageAndChannelAndRepostCurrentContentWithoutRebuilding(): Unit = runBlocking {
        host().use { host ->
            var posts = host.posts.posts.size
            host.appOps.emit("another.package")
            host.drainRefresh()
            assertEquals(posts, host.posts.posts.size)
            assertTrue(host.notifications.channelChecks.isEmpty())
            host.appOps.emit(host.service.packageName)
            assertEquals(posts, host.posts.posts.size) // Listener only enqueues Main work.
            host.drainRefresh()
            assertEquals(++posts, host.posts.posts.size)
            assertSame(host.notifications.built.first(), host.posts.posts.last().notification)
            val attempt = host.attempt()
            host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false)
            host.service.updateCaptureNotification(attempt, active = true)
            posts = host.posts.posts.size
            val builds = host.notifications.built.size
            host.appOps.emit(null)
            host.drainRefresh()
            assertEquals(++posts, host.posts.posts.size)
            assertSame(host.notifications.built.last(), host.posts.posts.last().notification)
            assertEquals(417, host.posts.posts.last().id)
            assertEquals(builds, host.notifications.built.size)
            host.notifications.enabled = false
            host.appOps.emit(host.service.packageName)
            host.drainRefresh()
            assertEquals(posts, host.posts.posts.size)
            assertEquals(List(3) { StreamingModuleNotifications.Channel.Streaming }, host.notifications.channelChecks)
        }
    }

    // Cases: CORE-SVC-04
    @Test
    fun permissionRefreshSkipsClosedAdmission() {
        host().use { host ->
            val posts = host.posts.posts.size
            host.service.closeForegroundAdmission()
            host.appOps.emit(host.service.packageName)
            host.drainRefresh()
            assertEquals(posts, host.posts.posts.size)
            assertTrue(host.notifications.channelChecks.isEmpty())
        }
    }

    // Cases: CORE-SVC-04
    @Test
    fun permissionRefreshSkipsStaleInstance() {
        host().use { host ->
            val posts = host.posts.posts.size
            host.manager.exit()
            assertFalse(host.controller.handle.isCurrent())
            host.appOps.emit(host.service.packageName)
            host.drainRefresh()
            assertEquals(posts, host.posts.posts.size)
            assertTrue(host.notifications.channelChecks.isEmpty())
        }
    }

    // Cases: CORE-SVC-04
    @Test
    fun destructionRemovesQueuedRefreshAndLateCallbackCannotRepost() {
        host().use { host ->
            host.drainRefresh()
            val looper = shadowOf(Looper.getMainLooper())
            val posts = host.posts.posts.size
            assertTrue(looper.isIdle)
            host.appOps.emit(host.service.packageName)
            assertFalse(looper.isIdle)
            host.destroy()
            assertTrue(looper.isIdle)
            host.appOps.emit(null) // Supplied late delivery of the original callback.
            host.drainRefresh()
            assertEquals(posts, host.posts.posts.size)
            assertTrue(host.notifications.channelChecks.isEmpty())
            assertTrue(host.controller.destroyed.isCompleted)
        }
    }

    // Cases: CORE-SVC-04
    @Test
    fun registrationFailureIsBestEffortAndDoesNotBlockForegroundOrControllerStartup() {
        ServiceAppOpsShadow.watchFailure = IllegalStateException("Watch registration")
        try {
            host().use { host ->
                assertEquals(1, host.appOps.watches.size)
                assertTrue(host.controller.started)
                assertTrue(host.controller.handle.isCurrent())
                assertFalse(host.controller.shutdown.isCompleted)
                assertEquals(1, host.platform.promotions.size)
            }
        } finally {
            ServiceAppOpsShadow.watchFailure = null
        }
    }

    // Cases: CORE-SVC-04, CORE-SVC-05
    @Test
    fun unregistrationFailureStillRemovesQueuedRefreshNotifiesControllerAndCompletesReceipt(): Unit = runBlocking {
        host().use { host ->
            host.drainRefresh()
            val receipt = async(start = CoroutineStart.UNDISPATCHED) { host.service.stopAndAwaitDestruction() }
            host.appOps.unwatchFailure = IllegalStateException("Watch removal")
            host.appOps.emit(host.service.packageName)
            assertFalse(shadowOf(Looper.getMainLooper()).isIdle)
            host.destroy()
            assertTrue(shadowOf(Looper.getMainLooper()).isIdle)
            assertTrue(host.controller.destroyed.isCompleted)
            assertTrue(withTimeout(5.seconds) { receipt.await() })
        }
    }

    // Cases: CORE-SVC-04
    @Test
    fun refreshProviderAndPostingFailuresRemainBestEffortAndLaterRefreshCanSucceed() {
        host().use { host ->
            var posts = host.posts.posts.size
            host.notifications.availabilityFailure = IllegalStateException("Channel lookup")
            host.appOps.emit(null)
            host.drainRefresh()
            assertEquals(posts, host.posts.posts.size)
            assertTrue(host.controller.handle.isCurrent())
            assertFalse(host.controller.shutdown.isCompleted)
            host.notifications.availabilityFailure = null
            host.posts.postFailure = IllegalStateException("Refresh posting")
            host.appOps.emit(null)
            host.drainRefresh()
            assertEquals(++posts, host.posts.posts.size)
            assertTrue(host.controller.handle.isCurrent())
            assertFalse(host.controller.shutdown.isCompleted)
            host.posts.postFailure = null
            host.appOps.emit(null)
            host.drainRefresh()
            assertEquals(++posts, host.posts.posts.size)
            assertSame(host.notifications.built.single(), host.posts.posts.last().notification)
        }
    }

    // Cases: CORE-SVC-05
    @Test
    fun stopRequestClosesAdmissionButReceiptWaitsForActualExpectedDestruction(): Unit = runBlocking {
        host().use { host ->
            val receipt = async(start = CoroutineStart.UNDISPATCHED) { host.service.stopAndAwaitDestruction() }
            assertTrue(host.platform.isStoppedBySelf)
            assertFalse(host.destroyed)
            assertFalse(host.controller.destroyed.isCompleted)
            assertFalse(receipt.isCompleted)
            assertFalse(host.service.enterCaptureForeground(host.attempt(), requiresMicrophoneType = false))
            assertTrue(host.platform.isLastForegroundNotificationAttached)
            host.destroy()
            assertTrue(withTimeout(5.seconds) { receipt.await() })
            assertFalse(host.platform.isLastForegroundNotificationAttached)
            assertTrue(host.controller.destroyed.isCompleted)
            assertFalse(host.controller.cleanup.isCompleted) // Separate supplied Controller-cleanup input.
        }
    }

    // Cases: CORE-SVC-05
    @Test
    fun unexpectedDestructionNotifiesOriginalControllerAndLaterStopReceiptIsFalse(): Unit = runBlocking {
        for (confirmed in listOf(false, true)) {
            host().use { host ->
                val attempt = host.attempt()
                host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false)
                if (confirmed) host.service.updateCaptureNotification(attempt, active = true)
                val builds = host.notifications.actions.size
                val posts = host.posts.posts.size
                val promotions = host.platform.promotions.size
                host.destroy()
                assertTrue(host.controller.destroyed.isCompleted)
                assertFalse(host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false))
                host.service.updateCaptureNotification(attempt, active = false)
                host.service.updateCaptureNotification(attempt, active = true)
                host.service.leaveCaptureForeground(attempt)
                host.service.leaveCaptureForeground(attempt)
                assertEquals(builds, host.notifications.actions.size)
                assertEquals(posts, host.posts.posts.size)
                assertEquals(promotions, host.platform.promotions.size)
                assertFalse(withTimeout(5.seconds) { host.service.stopAndAwaitDestruction() })
                assertTrue(host.platform.events.isEmpty()) // No stop request can precede an already completed destruction.
                assertFalse(host.controller.shutdown.isCompleted) // The callback's policy belongs to the module.
                assertFalse(host.controller.cleanup.isCompleted)
            }
        }
    }

    // Cases: CORE-SVC-05
    @Test
    fun destructionRetiresCapturePresentationBeforeControllerCallbackAndCompletesReceiptAfterward(): Unit = runBlocking {
        for (confirmed in listOf(false, true)) {
            host().use { host ->
                val attempt = host.attempt()
                host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false)
                if (confirmed) host.service.updateCaptureNotification(attempt, active = true)
                val builds = host.notifications.actions.size
                val posts = host.posts.posts.size
                val promotions = host.platform.promotions.size
                val receipt = async(start = CoroutineStart.UNDISPATCHED) { host.service.stopAndAwaitDestruction() }
                assertFalse(receipt.isCompleted)
                host.controller.onDestroyed = {
                    assertFalse(receipt.isCompleted)
                    val reentrant = async(start = CoroutineStart.UNDISPATCHED) {
                        host.service.updateCaptureNotification(attempt, active = false)
                        host.service.updateCaptureNotification(attempt, active = true)
                        host.service.leaveCaptureForeground(attempt)
                        host.service.leaveCaptureForeground(attempt)
                    }
                    assertTrue(reentrant.isCompleted)
                    assertFalse(reentrant.isCancelled)
                    assertFalse(host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false))
                    assertEquals(builds, host.notifications.actions.size)
                    assertEquals(posts, host.posts.posts.size)
                    assertEquals(promotions, host.platform.promotions.size)
                    assertFalse(receipt.isCompleted)
                    assertFalse(host.controller.cleanup.isCompleted)
                }
                host.destroy()
                assertTrue(withTimeout(5.seconds) { receipt.await() })
                assertTrue(host.controller.destroyed.isCompleted)
                assertFalse(host.controller.cleanup.isCompleted)
            }
        }
    }

    // Cases: CORE-SVC-05
    @Test
    fun failedStopRequestStillWaitsForDestructionAndReturnsFalse(): Unit = runBlocking {
        host().use { host ->
            host.platform.stopFailure = IllegalStateException("Stop request")
            val receipt = async(start = CoroutineStart.UNDISPATCHED) { host.service.stopAndAwaitDestruction() }
            assertEquals(listOf("stop"), host.platform.events)
            assertFalse(host.platform.isStoppedBySelf)
            assertFalse(receipt.isCompleted)
            host.destroy()
            assertFalse(withTimeout(5.seconds) { receipt.await() })
        }
    }

    // Cases: CORE-SVC-05
    @Test
    fun cancellingTheOnlyStopCallerEndsItsWaitWithoutPretendingServiceWasDestroyed(): Unit = runBlocking {
        host().use { host ->
            val receipt = async(start = CoroutineStart.UNDISPATCHED) { host.service.stopAndAwaitDestruction() }
            assertTrue(host.platform.isStoppedBySelf)
            assertFalse(receipt.isCompleted)
            receipt.cancelAndJoin()
            assertTrue(receipt.isCancelled)
            assertFalse(host.controller.destroyed.isCompleted)
            assertFalse(host.service.enterCaptureForeground(host.attempt(), requiresMicrophoneType = false))
            host.destroy()
            assertTrue(host.controller.destroyed.isCompleted)
        }
    }

    // Cases: CORE-SVC-05
    // Defensive callback fault; a conforming controller closes admission promptly.
    @Test
    fun throwingControllerDestructionCallbackStillCompletesServiceReceipt(): Unit = runBlocking {
        host().use { host ->
            val failure = IllegalStateException("Controller destruction callback")
            host.controller.onDestroyed = { throw failure }
            val receipt = async(start = CoroutineStart.UNDISPATCHED) { host.service.stopAndAwaitDestruction() }
            assertCause(failure, assertFailsWith<IllegalStateException> { host.destroy() })
            assertTrue(withTimeout(5.seconds) { receipt.await() })
            assertTrue(host.controller.destroyed.isCompleted)
            assertFalse(host.service.enterCaptureForeground(host.attempt(), requiresMicrophoneType = false))
        }
    }

    // Cases: CORE-SVC-06
    // Supplied callbacks qualify defensive response, not Android's natural timeout enforcement.
    @Test
    @Config(sdk = [34, 35])
    fun timeoutRequestsPlatformStopBeforeControllerShutdownAndMakesReceiptFalse() = timeoutHistory(stopFails = false)

    // Cases: CORE-SVC-06
    @Test
    @Config(sdk = [34, 35])
    fun throwingTimeoutStopStillRequestsOriginalControllerShutdownAndMakesReceiptFalse() = timeoutHistory(stopFails = true)

    private fun timeoutHistory(stopFails: Boolean) = runBlocking {
        host().use { host ->
            host.controller.onShutdown = { host.platform.events.add("shutdown") }
            if (stopFails) host.platform.stopFailure = IllegalStateException("Timeout stop request")
            if (Build.VERSION.SDK_INT >= 35) host.service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else host.service.onTimeout(1)
            assertEquals("stop", host.platform.events.first())
            assertTrue(host.platform.events.indexOf("shutdown") > 0)
            assertTrue(host.controller.shutdown.isCompleted)
            assertFalse(host.controller.handle.isCurrent())
            assertFalse(host.service.enterCaptureForeground(host.attempt(), requiresMicrophoneType = false))
            val receipt = async(start = CoroutineStart.UNDISPATCHED) { host.service.stopAndAwaitDestruction() }
            assertFalse(receipt.isCompleted)
            host.destroy()
            assertFalse(withTimeout(5.seconds) { receipt.await() })
        }
    }

    private fun host(): ServiceTestHost = ServiceTestHost(temporaryFolder.newFolder())

    private fun assertCause(expected: Throwable, actual: Throwable?) {
        assertNotNull(actual)
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
    }
}
