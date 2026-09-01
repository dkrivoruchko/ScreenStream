package io.screenstream.capture.internal.metrics

import android.app.Application
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import io.screenstream.capture.CaptureMetrics
import io.screenstream.capture.CaptureMetricsSource
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.DispatchAttemptKind
import io.screenstream.capture.testutil.DispatchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class BuiltInCaptureMetricsPlatformDisplayTest {
    // Verification: MET-02
    @Test
    @Config(
        manifest = Config.NONE,
        sdk = [
            Build.VERSION_CODES.N,
            Build.VERSION_CODES.R,
            Build.VERSION_CODES.S,
        ],
    )
    fun apiRouteUsesSelectedDisplayAndFreshDensityContext() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val fixture = Fixture(dispatcher)
            val observer = RecordingObserver()
            fixture.platform.registrationAssertion = { handler ->
                assertSame(Looper.getMainLooper(), handler.looper)
                assertTrue(dispatcher.submissions().isEmpty())
            }

            val handle = fixture.source.subscribe(observer)

            assertEquals(1, fixture.platform.mainHandlerCount.get())
            assertEquals(1, fixture.platform.registerCount.get())
            assertSame(fixture.platform.returnedMainHandler, fixture.platform.registeredHandler)
            assertEquals(1, dispatcher.pendingCount())
            enterOne(dispatcher)
            assertEquals(1, observer.changes().size)
            assertNotNull(observer.changes().single())

            fixture.platform.triggerChanged(fixture.displayId)
            assertEquals(1, dispatcher.pendingCount())
            enterOne(dispatcher)
            assertEquals(2, observer.changes().size)
            assertNotNull(observer.changes().last())

            val routeEvents = fixture.platform.routeEvents()
            val expectedRouteCounts = when (Build.VERSION.SDK_INT) {
                in Build.VERSION_CODES.N until Build.VERSION_CODES.R -> mapOf(
                    ROUTE_REAL_SIZE to 2,
                    ROUTE_DISPLAY_CONTEXT to 2,
                )

                Build.VERSION_CODES.R -> mapOf(
                    ROUTE_DISPLAY_CONTEXT to 3,
                    ROUTE_API_30_WINDOW_CONTEXT to 1,
                    ROUTE_WINDOW_MANAGER to 1,
                    ROUTE_MAXIMUM_BOUNDS to 2,
                )

                else -> mapOf(
                    ROUTE_API_31_WINDOW_CONTEXT to 1,
                    ROUTE_WINDOW_MANAGER to 1,
                    ROUTE_MAXIMUM_BOUNDS to 2,
                    ROUTE_DISPLAY_CONTEXT to 2,
                )
            }
            assertEquals(expectedRouteCounts, routeEvents.groupingBy { it }.eachCount())
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                assertTrue(
                    routeEvents.indexOf(ROUTE_DISPLAY_CONTEXT) < routeEvents.indexOf(ROUTE_API_30_WINDOW_CONTEXT),
                )
                assertTrue(routeEvents.indexOf(ROUTE_API_30_WINDOW_CONTEXT) < routeEvents.indexOf(ROUTE_WINDOW_MANAGER))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertTrue(routeEvents.indexOf(ROUTE_API_31_WINDOW_CONTEXT) < routeEvents.indexOf(ROUTE_WINDOW_MANAGER))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowManagerIndex = routeEvents.indexOf(ROUTE_WINDOW_MANAGER)
                assertTrue(
                    routeEvents.withIndex().filter { it.value == ROUTE_MAXIMUM_BOUNDS }
                        .all { it.index > windowManagerIndex },
                )
            }
            assertTrue(fixture.platform.routeDisplays().all { it === fixture.display })

            val displayContexts = fixture.platform.displayContexts()
            val expectedDisplayContextCount = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) 3 else 2
            assertEquals(expectedDisplayContextCount, displayContexts.size)
            displayContexts.indices.forEach { left ->
                ((left + 1) until displayContexts.size).forEach { right ->
                    assertNotSame(displayContexts[left], displayContexts[right])
                }
            }
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                assertSame(displayContexts.first(), fixture.platform.api30WindowContextInputs().single())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertEquals(listOf(fixture.application), fixture.platform.api31ApplicationContexts())
            }

            assertNull(observer.failure())
            handle.close()
            assertEquals(1, fixture.platform.unregisterCount.get())
            assertSame(fixture.platform.registeredListener, fixture.platform.unregisteredListener)
        }
    }

    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.BAKLAVA])
    fun registrationFailureIsContainedButInitialDispatchFailureEscapes() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val fixture = Fixture(dispatcher)
            val observer = RecordingObserver()
            val registrationFailure = IllegalStateException("registration failed")
            fixture.platform.registrationFailure = registrationFailure

            val handle = fixture.source.subscribe(observer)

            assertEquals(listOf(registrationFailure), observer.failures())
            assertTrue(observer.changes().isEmpty())
            assertTrue(fixture.platform.routeEvents().isEmpty())
            assertTrue(dispatcher.submissions().isEmpty())
            assertEquals(1, fixture.platform.unregisterCount.get())
            assertSame(fixture.platform.registeredListener, fixture.platform.unregisteredListener)
            fixture.platform.triggerChanged(fixture.displayId)
            assertTrue(dispatcher.submissions().isEmpty())
            handle.close()
            assertEquals(1, fixture.platform.unregisterCount.get())
        }

        assertInitialDispatchFailure(DispatchOutcome.Reject, expectedFailure = null)
        assertInitialDispatchFailure(
            DispatchOutcome.Throw(IllegalArgumentException("initial dispatch failed")),
            expectedFailure = IllegalArgumentException::class.java,
        )
    }

    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.BAKLAVA])
    fun refreshDispatchFailureClosesAndFencesListener() {
        assertRefreshSubmissionFailure(DispatchOutcome.Reject, expectedFailure = null)
        val dispatchFailure = IllegalArgumentException("refresh dispatch failed")
        assertRefreshSubmissionFailure(DispatchOutcome.Throw(dispatchFailure), expectedFailure = dispatchFailure)
    }

    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.BAKLAVA])
    fun refreshReadFailureClosesAndFencesListener() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val fixture = Fixture(dispatcher)
            val observer = RecordingObserver()
            val handle = fixture.source.subscribe(observer)
            enterOne(dispatcher)
            val routeBeforeFailure = fixture.platform.routeEvents()
            val readFailure = IllegalStateException("maximum bounds failed")
            fixture.platform.maximumBoundsFailure = readFailure

            fixture.platform.triggerChanged(fixture.displayId)
            enterOne(dispatcher)

            assertEquals(listOf(readFailure), observer.failures())
            assertEquals(1, observer.changes().size)
            assertEquals(routeBeforeFailure + ROUTE_MAXIMUM_BOUNDS, fixture.platform.routeEvents())
            assertEquals(1, fixture.platform.unregisterCount.get())
            assertSame(fixture.platform.registeredListener, fixture.platform.unregisteredListener)
            assertEquals(2, dispatcher.submissions().size)
            fixture.platform.triggerChanged(fixture.displayId)
            assertEquals(2, dispatcher.submissions().size)
            assertEquals(0, dispatcher.pendingCount())
            handle.close()
            assertEquals(1, fixture.platform.unregisterCount.get())
        }
    }

    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.BAKLAVA])
    fun invalidationDuringRefreshCreatesSuccessorEpoch() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val fixture = Fixture(dispatcher)
            val observer = RecordingObserver()
            val readEntered = CountDownLatch(1)
            val allowReadReturn = CountDownLatch(1)
            fixture.platform.maximumBoundsHook = {
                fixture.platform.maximumBoundsHook = null
                readEntered.countDown()
                check(allowReadReturn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "entered Metrics read was not released"
                }
            }
            val handle = fixture.source.subscribe(observer)
            val initialTask = dispatcher.enterNext() ?: error("initial Metrics task was not retained")
            val entered = readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            try {
                assertTrue(entered)
                ShadowDisplayManager.removeDisplay(fixture.displayId)
                fixture.platform.triggerRemoved(fixture.displayId)
            } finally {
                allowReadReturn.countDown()
            }
            initialTask.awaitSuccessfulCompletion()

            assertTrue(observer.changes().isEmpty())
            assertNull(observer.failure())
            assertEquals(2, dispatcher.submissions().size)
            assertEquals(1, dispatcher.pendingCount())
            assertEquals(1, fixture.platform.api31WindowContextCount())

            enterOne(dispatcher)

            assertEquals(1, observer.changes().size)
            assertNull(observer.changes().single())
            assertEquals(3, dispatcher.submissions().size)
            assertEquals(1, dispatcher.pendingCount())
            assertEquals(1, fixture.platform.api31WindowContextCount())

            enterOne(dispatcher)

            assertEquals(listOf(null), observer.changes())
            assertEquals(3, dispatcher.submissions().size)
            assertEquals(0, dispatcher.pendingCount())
            assertEquals(1, fixture.platform.api31WindowContextCount())
            assertEquals(0, fixture.platform.unregisterCount.get())
            handle.close()
            assertEquals(1, fixture.platform.unregisterCount.get())
        }
    }

    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.N])
    fun fixedDisplayRecoveryRequiresMatchingAddAndRetainsReadTarget() {
        val application: Application = RuntimeEnvironment.getApplication()
        val displayManager = checkNotNull(application.getSystemService(DisplayManager::class.java))
        val retainedDisplayId = ShadowDisplayManager.addDisplay(DISPLAY_QUALIFIERS)
        val retainedDisplay = checkNotNull(displayManager.getDisplay(retainedDisplayId))
        val replacementDisplayId = ShadowDisplayManager.addDisplay(REPLACEMENT_DISPLAY_QUALIFIERS)
        val replacementDisplay = checkNotNull(displayManager.getDisplay(replacementDisplayId))
        assertNotSame(retainedDisplay, replacementDisplay)

        val retainedValid = AtomicBoolean(true)
        val associatedDisplay = AtomicReference(retainedDisplay)
        val platform = RecordingPlatform().apply {
            displayIdOverride = { display ->
                if (display === replacementDisplay) retainedDisplayId else AndroidBuiltInCaptureMetricsPlatform.displayId(display)
            }
            isValidOverride = { display ->
                if (display === retainedDisplay) retainedValid.get() else AndroidBuiltInCaptureMetricsPlatform.isValid(display)
            }
            getDisplayOverride = { _, displayId ->
                if (displayId == retainedDisplayId) associatedDisplay.get() else null
            }
        }

        ControlledNonInlineDispatcher().use { dispatcher ->
            val observer = RecordingObserver()
            val source = BuiltInCaptureMetricsSource.forFixedDisplay(
                context = application,
                display = retainedDisplay,
                workerDispatcher = dispatcher,
                platform = platform,
            )
            val handle = source.subscribe(observer)

            enterOne(dispatcher)
            assertEquals(listOf(CaptureMetrics(640, 480, 160)), observer.changes())

            retainedValid.set(false)
            platform.triggerRemoved(retainedDisplayId)
            enterOne(dispatcher)
            enterOne(dispatcher)
            assertEquals(listOf(CaptureMetrics(640, 480, 160), null), observer.changes())

            retainedValid.set(true)
            associatedDisplay.set(replacementDisplay)
            val submissionsBeforeAdds = dispatcher.submissions().size
            platform.triggerAdded(replacementDisplayId)
            assertEquals(submissionsBeforeAdds, dispatcher.submissions().size)
            assertEquals(listOf(CaptureMetrics(640, 480, 160), null), observer.changes())

            platform.triggerAdded(retainedDisplayId)
            enterOne(dispatcher)
            enterOne(dispatcher)

            assertEquals(
                listOf(CaptureMetrics(640, 480, 160), null, CaptureMetrics(640, 480, 160)),
                observer.changes(),
            )
            assertTrue(platform.routeDisplays().all { it === retainedDisplay })
            assertTrue(observer.failures().isEmpty())
            handle.close()
        }
    }

    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.BAKLAVA])
    fun concurrentCloseSharesUnregisterFailure() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val fixture = Fixture(dispatcher)
            val observer = RecordingObserver()
            val handle = fixture.source.subscribe(observer)
            enterOne(dispatcher)
            val readFailure = IllegalStateException("refresh read failed")
            val unregisterFailure = IllegalArgumentException("unregister failed")
            val unregisterEntered = CountDownLatch(1)
            val allowUnregisterReturn = CountDownLatch(1)
            fixture.platform.maximumBoundsFailure = readFailure
            fixture.platform.unregisterFailure = unregisterFailure
            fixture.platform.unregisterHook = {
                unregisterEntered.countDown()
                check(allowUnregisterReturn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "unregister was not released"
                }
            }

            fixture.platform.triggerChanged(fixture.displayId)
            val refreshTask = dispatcher.enterNext() ?: error("refresh task was not retained")
            assertTrue(unregisterEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val closeStarted = CountDownLatch(2)
            val closeCompleted = CountDownLatch(2)
            val firstCloseFailure = AtomicReference<Throwable?>()
            val secondCloseFailure = AtomicReference<Throwable?>()
            val firstClose = startCloseThread(
                name = "Metrics-Caller-Close-1",
                handle = handle,
                started = closeStarted,
                completed = closeCompleted,
                failure = firstCloseFailure,
            )
            val secondClose = startCloseThread(
                name = "Metrics-Caller-Close-2",
                handle = handle,
                started = closeStarted,
                completed = closeCompleted,
                failure = secondCloseFailure,
            )
            assertTrue(closeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            fixture.platform.triggerChanged(fixture.displayId)
            assertEquals(2, dispatcher.submissions().size)
            allowUnregisterReturn.countDown()

            refreshTask.awaitSuccessfulCompletion()
            assertTrue(closeCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            joinBounded(firstClose)
            joinBounded(secondClose)
            assertSame(unregisterFailure, firstCloseFailure.get())
            assertSame(unregisterFailure, secondCloseFailure.get())
            assertSame(unregisterFailure, assertThrows(unregisterFailure.javaClass) { handle.close() })
            assertEquals(listOf(readFailure), observer.failures())
            assertEquals(1, fixture.platform.unregisterCount.get())
            assertSame(fixture.platform.registeredListener, fixture.platform.unregisteredListener)
            fixture.platform.triggerChanged(fixture.displayId)
            assertEquals(2, dispatcher.submissions().size)
            assertEquals(0, dispatcher.pendingCount())
        }
    }

    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.BAKLAVA])
    fun callerCloseDoesNotWaitForObserver() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val fixture = Fixture(dispatcher)
            val callbackEntered = CountDownLatch(1)
            val allowCallbackReturn = CountDownLatch(1)
            val observer = RecordingObserver().apply {
                metricsHook = {
                    callbackEntered.countDown()
                    check(allowCallbackReturn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "observer callback was not released"
                    }
                }
            }
            val handle = fixture.source.subscribe(observer)
            val initialTask = dispatcher.enterNext() ?: error("initial task was not retained")
            assertTrue(callbackEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val closeCompleted = CountDownLatch(1)
            val closeFailure = AtomicReference<Throwable?>()
            val closeThread = startCloseThread(
                name = "Metrics-Nonwaiting-Close",
                handle = handle,
                started = null,
                completed = closeCompleted,
                failure = closeFailure,
            )

            val closeReturnedBeforeCallback = closeCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            fixture.platform.triggerChanged(fixture.displayId)
            allowCallbackReturn.countDown()

            assertTrue(closeReturnedBeforeCallback)
            joinBounded(closeThread)
            initialTask.awaitSuccessfulCompletion()
            assertNull(closeFailure.get())
            assertEquals(1, observer.changes().size)
            assertTrue(observer.failures().isEmpty())
            assertEquals(1, fixture.platform.unregisterCount.get())
            assertEquals(1, dispatcher.submissions().size)
            assertEquals(0, dispatcher.pendingCount())
            handle.close()
            assertEquals(1, fixture.platform.unregisterCount.get())
        }
    }

    // Verification: MET-01
    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.BAKLAVA])
    fun defaultConfigCreatesFreshObservationPerOwner() {
        val application: Application = RuntimeEnvironment.getApplication()
        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, DISPLAY_QUALIFIERS)
        val platform = RecordingPlatform()
        var previousListener: DisplayManager.DisplayListener? = null

        ControlledNonInlineDispatcher(DispatchOutcome.Reject).use { publicDispatcher ->
            val source = BuiltInCaptureMetricsSource.forDefaultDisplay(
                context = application,
                workerDispatcher = publicDispatcher,
                platform = platform,
            )

            repeat(2) { ownerIndex ->
                ControlledNonInlineDispatcher(threadName = "Metrics-Owner-$ownerIndex").use { ownerDispatcher ->
                    val controlRequests = AtomicInteger()
                    val owner = SessionMetricsOwner(
                        workerDispatcher = ownerDispatcher,
                        sourceSelection = SessionMetricsSourceSelection.AbsentConfigDefault(source),
                        requestControlTurn = controlRequests::incrementAndGet,
                    )
                    val initial = owner.readSnapshot()

                    try {
                        owner.attach()
                        assertEquals(1, ownerDispatcher.pendingCount())
                        assertEquals(ownerIndex, platform.registerCount.get())
                        enterOne(ownerDispatcher)

                        val adopted = owner.readSnapshot()
                        assertNotSame(initial, adopted)
                        assertNotNull(adopted.metrics)
                        assertEquals(MetricsAttachmentLifecycle.Live, adopted.lifecycle)
                        assertTrue(adopted.handleAdopted)
                        assertTrue(adopted.isReady(requireCompletionCloseSettlement = true))
                        assertEquals(ownerIndex + 1, platform.registerCount.get())
                        assertEquals(ownerIndex, platform.unregisterCount.get())
                        assertTrue(publicDispatcher.submissions().isEmpty())
                        val listener = checkNotNull(platform.registeredListener)
                        previousListener?.let { assertNotSame(it, listener) }
                        previousListener = listener
                        assertEquals(1, ownerDispatcher.pendingCount())

                        owner.retire()

                        val retired = owner.readSnapshot()
                        assertEquals(MetricsAttachmentLifecycle.Retired, retired.lifecycle)
                        assertTrue(retired.handleAdopted)
                        assertFalse(retired.isReady(requireCompletionCloseSettlement = false))
                        assertEquals(ownerIndex, platform.unregisterCount.get())
                        assertEquals(1, ownerDispatcher.pendingCount())
                        val closeTask = ownerDispatcher.enterNext() ?: error("owner close task was not retained")
                        closeTask.awaitSuccessfulCompletion()

                        assertEquals(ownerIndex + 1, platform.unregisterCount.get())
                        assertSame(listener, platform.unregisteredListener)
                        assertSame(closeTask.enteredThread, platform.unregisterThread)
                        assertEquals(0, controlRequests.get())
                        assertEquals(2, ownerDispatcher.submissions().size)
                        assertEquals(0, ownerDispatcher.pendingCount())

                        owner.retire()
                        platform.triggerChanged(Display.DEFAULT_DISPLAY)
                        assertEquals(ownerIndex + 1, platform.unregisterCount.get())
                        assertEquals(2, ownerDispatcher.submissions().size)
                        assertEquals(0, ownerDispatcher.pendingCount())
                    } finally {
                        owner.retire()
                        while (ownerDispatcher.pendingCount() > 0) enterOne(ownerDispatcher)
                    }
                }
            }

            assertEquals(2, platform.registerCount.get())
            assertEquals(2, platform.unregisterCount.get())
            assertTrue(publicDispatcher.submissions().isEmpty())
        }
    }

    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.BAKLAVA])
    fun acceptedInitialDispatchCloseFencesLateEntry() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val fixture = Fixture(dispatcher)
            val observer = RecordingObserver()
            val handle = fixture.source.subscribe(observer)

            try {
                assertEquals(listOf(DispatchAttemptKind.Accepted), dispatcher.submissions().map { it.kind })
                assertEquals(1, dispatcher.pendingCount())
                assertEquals(1, fixture.platform.registerCount.get())
                assertEquals(0, fixture.platform.unregisterCount.get())
                assertTrue(fixture.platform.routeEvents().isEmpty())
                assertTrue(observer.changes().isEmpty())
                assertTrue(observer.failures().isEmpty())

                val closeStarted = CountDownLatch(1)
                val closeCompleted = CountDownLatch(1)
                val closeFailure = AtomicReference<Throwable?>()
                val closeThread = startCloseThread(
                    name = "Metrics-Accepted-Initial-Caller-Close",
                    handle = handle,
                    started = closeStarted,
                    completed = closeCompleted,
                    failure = closeFailure,
                )
                assertTrue(closeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertTrue(closeCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                joinBounded(closeThread)

                assertNull(closeFailure.get())
                assertSame(closeThread, fixture.platform.unregisterThread)
                assertEquals(1, fixture.platform.unregisterCount.get())
                assertSame(fixture.platform.registeredListener, fixture.platform.unregisteredListener)
                assertEquals(1, dispatcher.pendingCount())
                assertEquals(1, dispatcher.submissions().size)
                assertTrue(fixture.platform.routeEvents().isEmpty())
                assertTrue(observer.changes().isEmpty())
                assertTrue(observer.failures().isEmpty())

                fixture.platform.triggerChanged(fixture.displayId)
                handle.close()
                assertEquals(1, fixture.platform.unregisterCount.get())
                assertEquals(1, dispatcher.pendingCount())
                assertEquals(1, dispatcher.submissions().size)

                val initialTask = dispatcher.enterNext() ?: error("accepted initial task was not retained")
                initialTask.awaitSuccessfulCompletion()

                assertEquals(0, dispatcher.pendingCount())
                assertEquals(1, dispatcher.submissions().size)
                assertEquals(1, fixture.platform.unregisterCount.get())
                assertTrue(fixture.platform.routeEvents().isEmpty())
                assertTrue(observer.changes().isEmpty())
                assertTrue(observer.failures().isEmpty())
                handle.close()
                assertEquals(1, fixture.platform.unregisterCount.get())
            } finally {
                handle.close()
                while (dispatcher.pendingCount() > 0) enterOne(dispatcher)
            }
        }
    }

    private fun assertInitialDispatchFailure(
        outcome: DispatchOutcome,
        expectedFailure: Class<out Exception>?,
    ) {
        ControlledNonInlineDispatcher(outcome).use { dispatcher ->
            val fixture = Fixture(dispatcher)
            val observer = RecordingObserver()

            val actual = assertThrows(Exception::class.java) { fixture.source.subscribe(observer) }

            if (expectedFailure == null) {
                assertTrue(actual is IllegalStateException)
            } else {
                assertTrue(expectedFailure.isInstance(actual))
                val exact = (outcome as DispatchOutcome.Throw).failure
                assertSame(exact, actual)
            }
            assertTrue(observer.changes().isEmpty())
            assertTrue(observer.failures().isEmpty())
            assertTrue(fixture.platform.routeEvents().isEmpty())
            assertEquals(1, fixture.platform.unregisterCount.get())
            assertSame(fixture.platform.registeredListener, fixture.platform.unregisteredListener)
            assertEquals(1, dispatcher.submissions().size)
            fixture.platform.triggerChanged(fixture.displayId)
            assertEquals(1, dispatcher.submissions().size)
            assertEquals(0, dispatcher.pendingCount())
        }
    }

    private fun assertRefreshSubmissionFailure(
        outcome: DispatchOutcome,
        expectedFailure: Throwable?,
    ) {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val fixture = Fixture(dispatcher)
            val observer = RecordingObserver()
            val handle = fixture.source.subscribe(observer)
            enterOne(dispatcher)
            val routeBeforeFailure = fixture.platform.routeEvents()
            dispatcher.enqueue(outcome)

            fixture.platform.triggerChanged(fixture.displayId)

            val failures = observer.failures()
            assertEquals(1, failures.size)
            if (expectedFailure == null) {
                assertTrue(failures.single() is IllegalStateException)
            } else {
                assertSame(expectedFailure, failures.single())
            }
            assertEquals(1, observer.changes().size)
            assertEquals(routeBeforeFailure, fixture.platform.routeEvents())
            assertEquals(1, fixture.platform.unregisterCount.get())
            assertSame(fixture.platform.registeredListener, fixture.platform.unregisteredListener)
            val expectedKind = if (outcome === DispatchOutcome.Reject) {
                DispatchAttemptKind.Rejected
            } else {
                DispatchAttemptKind.Thrown
            }
            assertEquals(
                listOf(DispatchAttemptKind.Accepted, expectedKind),
                dispatcher.submissions().map { it.kind },
            )
            fixture.platform.triggerChanged(fixture.displayId)
            assertEquals(2, dispatcher.submissions().size)
            assertEquals(0, dispatcher.pendingCount())
            handle.close()
            assertEquals(1, fixture.platform.unregisterCount.get())
        }
    }

    private fun startCloseThread(
        name: String,
        handle: AutoCloseable,
        started: CountDownLatch?,
        completed: CountDownLatch,
        failure: AtomicReference<Throwable?>,
    ): Thread = Thread(
        {
            started?.countDown()
            try {
                handle.close()
            } catch (cause: Throwable) {
                failure.set(cause)
            } finally {
                completed.countDown()
            }
        },
        name,
    ).apply {
        isDaemon = true
        start()
    }

    private fun joinBounded(thread: Thread) {
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        assertFalse(thread.isAlive)
    }

    private fun enterOne(dispatcher: ControlledNonInlineDispatcher) {
        val task = dispatcher.enterNext() ?: error("expected an accepted built-in Metrics task")
        task.awaitSuccessfulCompletion()
    }

    private class Fixture(dispatcher: ControlledNonInlineDispatcher) {
        val application: Application = RuntimeEnvironment.getApplication()
        val displayManager: DisplayManager =
            checkNotNull(application.getSystemService(DisplayManager::class.java))
        val displayId: Int = ShadowDisplayManager.addDisplay(DISPLAY_QUALIFIERS)
        val display: Display = checkNotNull(displayManager.getDisplay(displayId))
        val platform = RecordingPlatform()
        val source: BuiltInCaptureMetricsSource = BuiltInCaptureMetricsSource.forFixedDisplay(
            context = application,
            display = display,
            workerDispatcher = dispatcher,
            platform = platform,
        )
    }

    private class RecordingObserver : CaptureMetricsSource.Observer {
        private val gate = Any()
        private val recordedChanges = ArrayList<CaptureMetrics?>()
        private val recordedFailures = ArrayList<Throwable>()

        @Volatile
        var metricsHook: ((CaptureMetrics?) -> Unit)? = null

        override fun onMetricsChanged(metrics: CaptureMetrics?) {
            metricsHook?.invoke(metrics)
            synchronized(gate) {
                recordedChanges += metrics
            }
        }

        override fun onComplete() = Unit

        override fun onFailure(cause: Throwable) {
            synchronized(gate) {
                recordedFailures += cause
            }
        }

        fun changes(): List<CaptureMetrics?> = synchronized(gate) { recordedChanges.toList() }

        fun failures(): List<Throwable> = synchronized(gate) { recordedFailures.toList() }

        fun failure(): Throwable? = synchronized(gate) { recordedFailures.singleOrNull() }
    }

    private class RecordingPlatform : BuiltInCaptureMetricsPlatform {
        private val delegate = AndroidBuiltInCaptureMetricsPlatform
        private val routeGate = Any()
        private val recordedRouteEvents = ArrayList<String>()
        private val recordedRouteDisplays = ArrayList<Display>()
        private val recordedDisplayContexts = ArrayList<Context>()
        private val recordedApi30WindowContextInputs = ArrayList<Context>()
        private val recordedApi31ApplicationContexts = ArrayList<Context>()
        private val recordedApi31WindowContexts = ArrayList<Context>()

        val returnedMainHandler: Handler = Handler(Looper.getMainLooper())
        val mainHandlerCount = AtomicInteger()
        val registerCount = AtomicInteger()
        val unregisterCount = AtomicInteger()

        @Volatile
        var registeredHandler: Handler? = null

        @Volatile
        var registeredListener: DisplayManager.DisplayListener? = null

        @Volatile
        var unregisteredListener: DisplayManager.DisplayListener? = null

        @Volatile
        var unregisterThread: Thread? = null

        @Volatile
        var registrationFailure: Exception? = null

        @Volatile
        var unregisterFailure: Exception? = null

        @Volatile
        var maximumBoundsFailure: Exception? = null

        @Volatile
        var registrationAssertion: ((Handler) -> Unit)? = null

        @Volatile
        var unregisterHook: (() -> Unit)? = null

        @Volatile
        var maximumBoundsHook: (() -> Unit)? = null

        @Volatile
        var displayIdOverride: ((Display) -> Int)? = null

        @Volatile
        var isValidOverride: ((Display) -> Boolean)? = null

        @Volatile
        var getDisplayOverride: ((DisplayManager, Int) -> Display?)? = null

        override val sdkInt: Int
            get() = Build.VERSION.SDK_INT

        override fun mainHandler(): Handler {
            mainHandlerCount.incrementAndGet()
            return returnedMainHandler
        }

        override fun displayId(display: Display): Int =
            displayIdOverride?.invoke(display) ?: delegate.displayId(display)

        override fun isValid(display: Display): Boolean =
            isValidOverride?.invoke(display) ?: delegate.isValid(display)

        override fun getDisplay(displayManager: DisplayManager, displayId: Int): Display? =
            getDisplayOverride?.invoke(displayManager, displayId) ?: delegate.getDisplay(displayManager, displayId)

        override fun registerDisplayListener(
            displayManager: DisplayManager,
            listener: DisplayManager.DisplayListener,
            handler: Handler,
        ) {
            registerCount.incrementAndGet()
            registeredListener = listener
            registeredHandler = handler
            registrationAssertion?.invoke(handler)
            registrationFailure?.let { throw it }
        }

        override fun unregisterDisplayListener(
            displayManager: DisplayManager,
            listener: DisplayManager.DisplayListener,
        ) {
            unregisterCount.incrementAndGet()
            unregisteredListener = listener
            unregisterThread = Thread.currentThread()
            unregisterHook?.invoke()
            unregisterFailure?.let { throw it }
        }

        override fun createDisplayContext(applicationContext: Context, display: Display): Context {
            recordRoute(ROUTE_DISPLAY_CONTEXT, display)
            return delegate.createDisplayContext(applicationContext, display).also { context ->
                synchronized(routeGate) {
                    recordedDisplayContexts += context
                }
            }
        }

        override fun createApi30WindowContext(displayContext: Context): Context {
            recordRoute(ROUTE_API_30_WINDOW_CONTEXT)
            synchronized(routeGate) {
                recordedApi30WindowContextInputs += displayContext
            }
            return delegate.createApi30WindowContext(displayContext)
        }

        override fun createApi31WindowContext(applicationContext: Context, display: Display): Context {
            recordRoute(ROUTE_API_31_WINDOW_CONTEXT, display)
            synchronized(routeGate) {
                recordedApi31ApplicationContexts += applicationContext
            }
            return delegate.createApi31WindowContext(applicationContext, display).also { context ->
                synchronized(routeGate) {
                    recordedApi31WindowContexts += context
                }
            }
        }

        override fun windowManager(windowContext: Context): WindowManager {
            recordRoute(ROUTE_WINDOW_MANAGER)
            return delegate.windowManager(windowContext)
        }

        override fun maximumWindowBounds(windowManager: WindowManager): Rect {
            recordRoute(ROUTE_MAXIMUM_BOUNDS)
            maximumBoundsHook?.invoke()
            maximumBoundsFailure?.let { throw it }
            return delegate.maximumWindowBounds(windowManager)
        }

        override fun getRealSize(display: Display, point: Point) {
            recordRoute(ROUTE_REAL_SIZE, display)
            delegate.getRealSize(display, point)
        }

        fun triggerChanged(displayId: Int) {
            checkNotNull(registeredListener).onDisplayChanged(displayId)
        }

        fun triggerAdded(displayId: Int) {
            checkNotNull(registeredListener).onDisplayAdded(displayId)
        }

        fun triggerRemoved(displayId: Int) {
            checkNotNull(registeredListener).onDisplayRemoved(displayId)
        }

        fun routeEvents(): List<String> = synchronized(routeGate) { recordedRouteEvents.toList() }

        fun routeDisplays(): List<Display> = synchronized(routeGate) { recordedRouteDisplays.toList() }

        fun displayContexts(): List<Context> = synchronized(routeGate) { recordedDisplayContexts.toList() }

        fun api30WindowContextInputs(): List<Context> =
            synchronized(routeGate) { recordedApi30WindowContextInputs.toList() }

        fun api31ApplicationContexts(): List<Context> =
            synchronized(routeGate) { recordedApi31ApplicationContexts.toList() }

        fun api31WindowContextCount(): Int = synchronized(routeGate) { recordedApi31WindowContexts.size }

        private fun recordRoute(name: String, display: Display? = null) {
            synchronized(routeGate) {
                recordedRouteEvents += name
                display?.let { recordedRouteDisplays += it }
            }
        }
    }

    private companion object {
        const val DISPLAY_QUALIFIERS = "w640dp-h480dp-mdpi"
        const val REPLACEMENT_DISPLAY_QUALIFIERS = "w320dp-h240dp-hdpi"
        const val ROUTE_REAL_SIZE = "getRealSize"
        const val ROUTE_DISPLAY_CONTEXT = "createDisplayContext"
        const val ROUTE_API_30_WINDOW_CONTEXT = "createApi30WindowContext"
        const val ROUTE_API_31_WINDOW_CONTEXT = "createApi31WindowContext"
        const val ROUTE_WINDOW_MANAGER = "windowManager"
        const val ROUTE_MAXIMUM_BOUNDS = "maximumWindowBounds"
        const val TIMEOUT_SECONDS = 5L
    }
}
