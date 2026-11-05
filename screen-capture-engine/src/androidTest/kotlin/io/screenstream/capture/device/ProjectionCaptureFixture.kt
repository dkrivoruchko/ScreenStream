package io.screenstream.capture.device

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.screenstream.capture.FrameConsumerRegistration
import io.screenstream.capture.OutputSize
import io.screenstream.capture.ScreenCaptureConfig
import io.screenstream.capture.ScreenCaptureEngine
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.ScreenCaptureSession
import io.screenstream.capture.ScreenCaptureState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/** Real external work is bounded for the test; a timeout is never a physical settlement receipt. */
internal class ProjectionCaptureFixture private constructor(
    private val history: String,
    private val planned: List<String>,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val run = ProjectionTestRun()
    private val frames = Channel<OwnedProjectionFrame>(4, BufferOverflow.DROP_OLDEST)
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observations = ArrayDeque<String>()
    private val evidence = ProjectionEvidence(context, device, run.id)
    private val imageFailures = ArrayList<Throwable>()
    private val diagnosticFailures = ArrayList<Throwable>()
    private var geometryDiagnosticsCaptured = false
    private val reached = ArrayList<String>()
    private val stageResults = linkedMapOf<String, String>()
    private var scenario: ActivityScenario<ProjectionTestActivity>? = null
    private var session: ScreenCaptureSession? = null
    private var registration: FrameConsumerRegistration? = null
    private var originalRequestedOrientation: Int? = null
    private val originalDisplay = readProjectionDisplay(context)
    private val rotationControl = ProjectionRotationControl(context, instrumentation.uiAutomation, device)
    private val originalPolicy: Pair<Int, Int> get() = rotationControl.originalPolicy
    private var sourceResizeLab: ProjectionSourceResizeLab? = null
    private var captureInitialDisplay: ProjectionDisplay? = null
    private var expectedDisplay: ProjectionDisplay? = null
    private var epoch = 0
    private var previousSequence = 0L
    private var registryInstalled = false
    private var lastExpected: ProjectionExpectation? = null

    val display: ProjectionDisplay get() = checkNotNull(run.drawn).display

    private suspend fun open() {
        check(context.applicationInfo.targetSdkVersion == 37) { "Test APK must target SDK37: ${context.applicationInfo.targetSdkVersion}" }
        check(device.isScreenOn && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            "Projection fixture requires an already awake, unlocked device"
        }
        ProjectionTestRuns.add(run)
        registryInstalled = true
        scenario = ActivityScenario.launch(
            Intent(context, ProjectionTestActivity::class.java)
                .putExtra(ProjectionTestRuns.RUN_ID, run.id)
        )
        await("initial measured Activity scene") { run.activity?.hasWindowFocus() == true && run.drawn != null }
        originalRequestedOrientation = main { checkNotNull(run.activity).requestedOrientation }
        evidence.text(
            "environment", "history=$history\nrun=${run.id}\nAPI=${Build.VERSION.SDK_INT}\nbuild=${Build.FINGERPRINT}\n" +
                    "ABIs=${Build.SUPPORTED_ABIS.contentToString()}\ntarget=${context.applicationInfo.targetSdkVersion}\n" +
                    "originalDisplay=$originalDisplay\noriginalPolicy=$originalPolicy\nplanned=$planned\n"
        )
    }

    suspend fun start(parameters: ScreenCaptureParameters = ScreenCaptureParameters()) {
        check(session == null)
        main { checkNotNull(run.activity).requestConsent() }
        approveConsent()
        await("real foreground service promotion") { run.service != null && foregroundServiceIsRunning() }
        val service = checkNotNull(run.service)
        val consent = run.takeConsent()
        val manager = checkNotNull(service.getSystemService(MediaProjectionManager::class.java))
        val projection = checkNotNull(manager.getMediaProjection(consent.first, consent.second))
        val created = try {
            ScreenCaptureEngine.createSession(service.applicationContext, projection, ScreenCaptureConfig())
        } catch (failure: Throwable) {
            try {
                projection.stop()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        session = created
        run.session = created
        observerScope.launch {
            created.state.collect { state -> record("state=$state"); run.signal() }
        }
        observerScope.launch {
            created.diagnosticEvents.collect { event -> record("diagnostic=$event") }
        }
        registration = created.registerFrameConsumer { frame ->
            // All borrowed access and the ownership copy occur on the receiving callback thread.
            val owned = OwnedProjectionFrame(
                run.id, frame.toByteArray(), frame.sequence,
                frame.outputTimestampElapsedRealtimeNanos, frame.outputInfo
            )
            frames.trySend(owned)
            run.signal()
        }
        evidence.text("start-input", "parameters=$parameters\nconfig=ScreenCaptureConfig()\n")
        bounded("completed public start") { created.start(parameters) }
        run.receipt("public-start-returned")
    }

    suspend fun checkpoint(name: String, parameters: ScreenCaptureParameters): Unit = checkImage(name, parameters, update = false)

    suspend fun updateAndCheckpoint(name: String, parameters: ScreenCaptureParameters): Unit = checkImage(name, parameters, update = true)

    private suspend fun checkImage(name: String, parameters: ScreenCaptureParameters, update: Boolean): Unit = stage(name) {
        healthy()
        if (update) checkNotNull(session).updateParameters(parameters)
        val scene = drawScene()
        if (captureInitialDisplay == null) captureInitialDisplay = scene.display
        val requiredDisplay = expectedDisplay
        check(requiredDisplay == null || scene.display == requiredDisplay) { "Unrequested source geometry change: expected=$requiredDisplay actual=${scene.display}" }
        expectedDisplay = scene.display
        val expected = ProjectionExpectation(scene, parameters)
        lastExpected = expected
        evidence.text("$name-input", "expected=$expected\nscene=$scene\n")
        qualifyNative(name, scene)
        stageResults[name] = "input-qualified"
        await("$name applied state") {
            (session?.state?.value as? ScreenCaptureState.Active)?.let { expected.matches(it.outputInfo) } == true
        }
        stageResults[name] = "input-and-applied-state-qualified"
        val lowerSequence = previousSequence
        var latest: OwnedProjectionFrame? = null
        var matched: OwnedProjectionFrame? = null
        var firstMatched: OwnedProjectionFrame? = null
        var verdict: ProjectionImageVerdict? = null
        var passed = false
        var awaitFailure: Throwable? = null
        try {
            passed = withTimeoutOrNull(CHECKPOINT_MS.milliseconds) {
                while (true) {
                    healthy()
                    val candidate = frames.receive()
                    latest = candidate
                    if (candidate.runId != run.id || candidate.sequence <= lowerSequence || candidate.timestamp < 0) continue
                    if (!expected.matches(candidate.outputInfo)) continue
                    matched = candidate
                    if (firstMatched == null) firstMatched = candidate
                    val result = ProjectionImageOracle.inspect(candidate, expected)
                    verdict = result
                    if (result.passed) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } == true
            if (!passed && matched != null) {
                imageFailures += AssertionError("$name image/scene failure: ${verdict?.errors}; evidence=${evidence.directory.absolutePath}")
            }
        } catch (failure: Throwable) {
            awaitFailure = failure
            throw failure
        } finally {
            var evidenceFailure: Throwable? = null
            fun preserve(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    val previous = evidenceFailure
                    if (previous == null) evidenceFailure = failure else previous.addSuppressed(failure)
                }
            }
            // Persist available snapshots even when the owner/oracle fails while awaiting its next frame.
            preserve { firstMatched?.let { evidence.frame("$name-first-matched", it) } }
            preserve { latest?.let { evidence.frame("$name-latest", it) } }
            preserve { matched?.let { evidence.frame("$name-matched", it) } }
            preserve { evidence.text("$name-frame-await", "passed=$passed\nverdict=$verdict\nfailure=${awaitFailure?.stackTraceToString()}\n") }
            evidenceFailure?.let { failure ->
                if (awaitFailure != null) awaitFailure.addSuppressed(failure) else throw failure
            }
        }
        healthy()
        val current = session?.state?.value
        check(current is ScreenCaptureState.Active && expected.matches(current.outputInfo)) {
            "$name lost its healthy applied configuration: $current"
        }
        evidence.text(
            "$name-verdict", "passed=$passed\nexpected=$expected\nlastVerdict=$verdict\n" +
                    "reached=$reached\nstate=$current\n${observations()}\n${run.receipts.joinToString("\n")}\n"
        )
        val finalMatch = checkNotNull(matched) {
            "$name received no fresh frame with independent expected metadata; dependent stages are unreached"
        }
        stageResults[name] = if (passed) "exercised-image-pass" else "exercised-image-fail"
        previousSequence = maxOf(previousSequence, finalMatch.sequence)
        if (!passed && !geometryDiagnosticsCaptured &&
            InstrumentationRegistry.getArguments().getString("engine.captureGeometryDiagnostics") == "true"
        ) {
            // This checkpoint already failed its image oracle after independent source qualification.
            geometryDiagnosticsCaptured = true
            try {
                diagnosticFailures += evidence.captureGeometryDiagnostics(instrumentation.uiAutomation, history, name, finalMatch)
            } catch (failure: Throwable) {
                diagnosticFailures += failure
            }
        }
        // A qualified healthy owner may continue after an image failure; this is not a passing checkpoint.
    }

    suspend fun invalidCropAndRecover(name: String, invalid: ScreenCaptureParameters, valid: ScreenCaptureParameters) {
        stage(name) {
            healthy()
            val before = checkNotNull(lastExpected)
            checkNotNull(session).updateParameters(invalid)
            val suspended = bounded("$name InvalidRequest suspension") {
                checkNotNull(session).state.first { state ->
                    healthy()
                    state is ScreenCaptureState.Suspended && state.requestedParameters == invalid
                } as ScreenCaptureState.Suspended
            }
            check(suspended.problem == ScreenCaptureProblem.InvalidRequest) { "Unexpected suspended problem: $suspended" }
            check(before.matches(suspended.lastOutputInfo)) { "Suspension lost historical applied metadata: $suspended" }
            evidence.text("$name-suspended", "request=$invalid\nstate=$suspended\n${observations()}\n")
            stageResults[name] = "exercised-expected-suspension-pass"
        }
        updateAndCheckpoint("recovered", valid)
    }

    suspend fun rotateDisplayAndCheckpoint(name: String, parameters: ScreenCaptureParameters): Unit =
        changeDisplayRotation(name, parameters, returnToOriginal = false)

    suspend fun restoreDisplayRotationAndCheckpoint(name: String, parameters: ScreenCaptureParameters): Unit =
        changeDisplayRotation(name, parameters, returnToOriginal = true)

    private suspend fun changeDisplayRotation(name: String, parameters: ScreenCaptureParameters, returnToOriginal: Boolean) {
        stage("$name-platform") {
            healthy()
            check(main { checkNotNull(run.activity).resources.configuration.smallestScreenWidthDp < 600 }) {
                "Real rotation is qualified only for phone windows below600dp"
            }
            val initial = checkNotNull(captureInitialDisplay) { "A real initial capture checkpoint must precede rotation" }
            check(initial.rotation in 0..3 && originalPolicy.first in 0..1 && originalPolicy.second in 0..3)
            val naturalPortrait = if (initial.rotation % 2 == 0) initial.width < initial.height else initial.height < initial.width
            check(naturalPortrait) { "This fixture qualifies natural-portrait phones; initial=$initial" }
            if (!rotationControl.touched) {
                check(rotationControl.policy() == originalPolicy) { "Device rotation policy changed before test control" }
                check(originalPolicy.first != 0 || originalPolicy.second == initial.rotation) {
                    "Locked initial policy does not describe the measured initial rotation"
                }
                check(rotationControl.controllerMatches(initial.width, initial.height, initial.rotation)) {
                    "Initial source differs from the actual controller display: ${rotationFacts()}"
                }
                rotationControl.rememberBaseline(initial)
            }
            val targetWidth = if (returnToOriginal) initial.width else initial.height
            val targetHeight = if (returnToOriginal) initial.height else initial.width
            val targetRotation = if (returnToOriginal) initial.rotation else (initial.rotation + 1) % 4
            val oldGeneration = run.drawGeneration.get()
            val target = "display=${targetWidth}x$targetHeight rotation=$targetRotation lockedPolicy=${0 to targetRotation} generation>$oldGeneration"
            evidence.text("$name-platform-before", "target=$target\n${rotationFacts()}\n")
            var primary: Throwable? = null
            try {
                // Activity orientation can be ignored/letterboxed. The test controls the real display instead.
                rotationControl.rotateTo(targetRotation)
                observedCondition("$name actual platform rotation/layout") {
                    healthy()
                    val scene = run.drawn
                    rotationControl.controllerMatches(targetWidth, targetHeight, targetRotation) && rotationControl.policy() == (0 to targetRotation) &&
                            scene != null && scene.generation > oldGeneration && scene.display.width == targetWidth &&
                            scene.display.height == targetHeight && scene.display.rotation == targetRotation &&
                            scene.activityIdentity == System.identityHashCode(run.activity)
                }
                expectedDisplay = checkNotNull(run.drawn).display
                check(main { run.activity?.requestedOrientation == originalRequestedOrientation }) {
                    "Test Activity orientation request changed during display control"
                }
                stageResults["$name-platform"] = "qualified-actual-display-change"
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                try {
                    evidence.text("$name-platform-observed", "target=$target\n${rotationFacts()}\n")
                } catch (evidenceFailure: Throwable) {
                    if (primary != null) primary.addSuppressed(evidenceFailure) else throw evidenceFailure
                }
            }
        }
        checkpoint(name, parameters)
    }

    suspend fun sourceAspectResizeLab() {
        stage("lab-eligibility") {
            check(InstrumentationRegistry.getArguments().getString("engine.sourceResizeLab") == "true") {
                "The source-resize lab requires explicit engine.sourceResizeLab=true"
            }
            check(
                Build.VERSION.SDK_INT == 37 && Build.PRODUCT.startsWith("sdk_gphone") &&
                        Build.DEVICE.startsWith("emu") && Build.HARDWARE in setOf("ranchu", "goldfish")
            ) {
                "This source-resize lab is qualified only for the API37 Android emulator"
            }
            check(display == ProjectionDisplay(1080, 2400, 420, 0) && rotationControl.controllerMatches(1080, 2400, 0)) {
                "The lab requires an initial logical1080x2400/420 portrait source: ${rotationFacts()}"
            }
            val owner = ProjectionSourceResizeLab(context, instrumentation.uiAutomation)
            check(
                owner.original.size.effective == LabSize(1080, 2400) && owner.original.density.effective == 420 &&
                        owner.original.rotationPolicy == originalPolicy
            ) { "Inconsistent initial lab environment: ${owner.original}" }
            check(sourceLayoutMatches(1080, 2400, 420, -1)) { "Initial full-window source is unqualified: ${rotationFacts()}" }
            sourceResizeLab = owner
            evidence.text("source-resize-original", "saved=${owner.original}\n${rotationFacts()}\n${owner.receipts.joinToString("\n")}\n")
            stageResults["lab-eligibility"] = "qualified-emulator-and-saved-environment"
        }
        start()
        checkpoint("initial", ScreenCaptureParameters())
        resizeSource("shrunk", LabSize(480, 960), restore = false)
        resizeSource("restored", LabSize(1080, 2400), restore = true)
    }

    private suspend fun resizeSource(name: String, target: LabSize, restore: Boolean) {
        val owner = checkNotNull(sourceResizeLab)
        stage("$name-platform") {
            healthy()
            val oldGeneration = run.drawGeneration.get()
            val shrunk = LabSize(480, 960)
            val shrunkState = LabSizeState(owner.original.size.physical, shrunk.takeUnless { it == owner.original.size.physical })
            val expectedSize = if (restore) owner.original.size else shrunkState
            evidence.text(
                "$name-size-before", "targetLogical=$target expectedWm=$expectedSize\n" +
                        "current=${owner.readEnvironment()}\n${rotationFacts()}\n"
            )
            var primary: Throwable? = null
            try {
                check(owner.readEnvironment() == if (restore) owner.original.copy(size = shrunkState) else owner.original) {
                    "Lab environment changed outside the expected size history"
                }
                if (restore) owner.restoreExact() else owner.resize(target)
                check(owner.readEnvironment() == owner.original.copy(size = expectedSize)) {
                    "wm did not establish the exact logical override while preserving physical/density/policy/night state"
                }
                observedCondition("$name actual source display/window/layout") {
                    healthy()
                    rotationControl.controllerMatches(target.width, target.height, 0) && rotationControl.policy() == originalPolicy &&
                            sourceLayoutMatches(target.width, target.height, owner.original.density.effective, oldGeneration)
                }
                expectedDisplay = ProjectionDisplay(target.width, target.height, owner.original.density.effective, 0)
                stageResults["$name-platform"] = "qualified-actual-logical-source-change"
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                try {
                    evidence.text(
                        "$name-size-observed", "targetLogical=$target expectedWm=$expectedSize\n" +
                                "current=${owner.readEnvironment()}\n${rotationFacts()}\n${owner.receipts.joinToString("\n")}\n"
                    )
                } catch (evidenceFailure: Throwable) {
                    if (primary != null) primary.addSuppressed(evidenceFailure) else throw evidenceFailure
                }
            }
        }
        // Applied frame geometry must independently corroborate the real projection source, not just wm state.
        checkpoint(name, ScreenCaptureParameters())
    }

    private fun sourceLayoutMatches(width: Int, height: Int, density: Int, afterGeneration: Long): Boolean = main {
        val activity = run.activity ?: return@main false
        val scene = run.drawn ?: return@main false
        if (Build.VERSION.SDK_INT < 30) return@main false
        val bounds = activity.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        scene.generation > afterGeneration && scene.activityIdentity == System.identityHashCode(activity) &&
                scene.display == ProjectionDisplay(width, height, density, 0) && scene.view == PixelRect(0, 0, width, height) &&
                bounds.left == 0 && bounds.top == 0 && bounds.width() == width && bounds.height() == height &&
                activity.sceneView.width == width && activity.sceneView.height == height
    }

    private fun observedCondition(description: String, predicate: () -> Boolean) {
        // The documented condition wait also observes asynchronous Settings changes without requiring a View redraw.
        check(device.wait(Condition<UiDevice, Boolean> { predicate() }, CHECKPOINT_MS)) {
            "Timed out: $description; ${rotationFacts()}"
        }
    }

    private fun rotationFacts(): String {
        val controller = rotationControl.controllerFacts()
        val host = main {
            val activity = run.activity
            if (activity == null) {
                "activity=absent"
            } else {
                val configuration = activity.resources.configuration
                val manager = activity.getSystemService(WindowManager::class.java)
                val metrics = if (Build.VERSION.SDK_INT >= 30) {
                    "currentWindow=${manager.currentWindowMetrics.bounds} maximumWindow=${manager.maximumWindowMetrics.bounds}"
                } else {
                    "windowMetrics=unavailable-below30"
                }
                val view = activity.sceneView
                val position = IntArray(2)
                view.getLocationOnScreen(position)
                "activity=${System.identityHashCode(activity)} requestedOrientation=${activity.requestedOrientation} " +
                        "configurationOrientation=${configuration.orientation} screenDp=${configuration.screenWidthDp}x${configuration.screenHeightDp} " +
                        "smallestDp=${configuration.smallestScreenWidthDp} density=${configuration.densityDpi} $metrics " +
                        "view=${position.contentToString()} ${view.width}x${view.height} viewDisplayRotation=${view.display?.rotation} " +
                        "contextDisplay=${readProjectionDisplay(activity)}"
            }
        }
        return "$controller\npolicy=${rotationControl.policy()} originalPolicy=$originalPolicy\n$host\n" +
                "drawGeneration=${run.drawGeneration.get()} lastMeasuredScene=${run.drawn}"
    }

    private suspend fun drawScene(): ProjectionScene {
        val before = run.drawGeneration.get()
        epoch++
        check(epoch <= 8)
        main {
            run.scene = epoch
            checkNotNull(run.activity).sceneView.invalidate()
        }
        await("scene$epoch measured draw") {
            run.drawn?.let {
                it.epoch == epoch && it.generation > before &&
                        it.activityIdentity == System.identityHashCode(run.activity)
            } == true
        }
        return checkNotNull(run.drawn).also { check(it.runId == run.id && it.epoch == epoch) }
    }

    private suspend fun qualifyNative(name: String, scene: ProjectionScene) {
        var latest: Bitmap? = null
        var verdict: ProjectionImageVerdict? = null
        var observations = 0
        var primary: Throwable? = null
        try {
            // onDraw records geometry before presentation. Await observed native input, never JPEG correctness.
            val qualified = withTimeoutOrNull(CHECKPOINT_MS.milliseconds) {
                while (true) {
                    healthy()
                    val captured = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Native input screenshot unavailable" }
                    latest?.recycle()
                    latest = captured
                    observations++
                    val nativeVerdict = ProjectionImageOracle.qualifyNative(captured, scene)
                    verdict = nativeVerdict
                    if (nativeVerdict.passed) return@withTimeoutOrNull true
                    val nextFrame = CompletableDeferred<Unit>()
                    main { checkNotNull(run.activity).sceneView.postOnAnimation { nextFrame.complete(Unit) } }
                    nextFrame.await()
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } == true
            evidence.text("$name-native-oracle", "qualified=$qualified observations=$observations\n$verdict\n")
            check(qualified) { "Native scene is unqualified: ${verdict?.errors}" }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            latest?.let { screenshot ->
                try {
                    evidence.write("$name-native", "png") { target ->
                        target.outputStream().buffered().use { stream -> check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                    }
                } catch (evidenceFailure: Throwable) {
                    if (primary != null) primary.addSuppressed(evidenceFailure) else throw evidenceFailure
                } finally {
                    screenshot.recycle()
                }
            }
        }
    }

    private suspend fun stage(name: String, action: suspend () -> Unit) {
        reached += name
        stageResults[name] = "attempted"
        var primary: Throwable? = null
        try {
            action()
        } catch (failure: Throwable) {
            primary = failure
            stageResults[name] = "structural-failure-from-${stageResults[name]}: ${failure.message}"
            throw failure
        } finally {
            try {
                evidence.text(
                    "$name-stage", "stage=$name\nstatus=${stageResults[name]}\nstate=${session?.state?.value}\n" +
                            "failure=${primary?.stackTraceToString()}\n${observations()}\n"
                )
            } catch (evidenceFailure: Throwable) {
                if (primary != null) primary.addSuppressed(evidenceFailure) else throw evidenceFailure
            }
        }
    }

    private fun approveConsent() {
        checkNotNull(device.wait(Until.findObject(By.res("android:id/button1").pkg("com.android.systemui")), CONSENT_MS)) {
            "Real MediaProjection consent unavailable; qualify SystemUI selectors"
        }
        evidence.ui("consent-before")
        val legacy = device.findObject(By.res("com.android.systemui:id/screen_share_mode_spinner"))
        val modern = device.findObject(By.res("com.android.systemui:id/screen_share_mode_options"))
        val spinner = legacy ?: modern
        if (spinner != null) {
            val resources = context.packageManager.getResourcesForApplication("com.android.systemui")
            val resourceName = if (legacy != null) "screen_share_permission_dialog_option_entire_screen"
            else "media_projection_entry_app_permission_dialog_option_text_entire_screen"
            val stringId = resources.getIdentifier(resourceName, "string", "com.android.systemui")
            check(stringId != 0) { "SystemUI whole-display consent option is unqualified" }
            val wholeDisplay = resources.getString(stringId)
            spinner.click()
            evidence.ui("consent-options")
            checkNotNull(device.wait(Until.findObject(By.text(wholeDisplay).pkg("com.android.systemui")), CONSENT_MS)).click()
            val spinnerId = if (legacy != null) "com.android.systemui:id/screen_share_mode_spinner"
            else "com.android.systemui:id/screen_share_mode_options"
            check(device.wait(Until.hasObject(By.res(spinnerId).hasDescendant(By.text(wholeDisplay))), CONSENT_MS)) {
                "SystemUI did not confirm whole-display consent selection"
            }
        } else {
            check(Build.VERSION.SDK_INT < 34) { "Modern consent has no qualified whole-display selector" }
        }
        evidence.ui("consent-whole-display")
        checkNotNull(device.findObject(By.res("android:id/button1").pkg("com.android.systemui"))).click()
    }

    private suspend fun await(description: String, predicate: () -> Boolean) {
        bounded(description) {
            while (!predicate()) {
                healthy(); run.changes.receive()
            }
            healthy()
        }
    }

    private suspend fun <T> bounded(description: String, action: suspend () -> T): T = try {
        withTimeout(CHECKPOINT_MS.milliseconds) { action() }
    } catch (failure: TimeoutCancellationException) {
        throw AssertionError("Timed out: $description; state=${session?.state?.value}; run=${run.id}", failure)
    }

    private fun healthy() {
        run.hostFailure.get()?.let { throw AssertionError("Projection host failed", it) }
        check(session?.state?.value !is ScreenCaptureState.Failed && session?.state?.value !is ScreenCaptureState.Stopped) {
            "Session ended; dependent stages are unreached: ${session?.state?.value}"
        }
    }

    @Suppress("DEPRECATION")
    private fun foregroundServiceIsRunning(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == ProjectionTestService::class.java.name && it.foreground }

    private fun record(value: String) {
        synchronized(observations) {
            if (observations.size == 64) observations.removeFirst()
            observations.addLast(value)
        }
    }

    private fun observations(): String = synchronized(observations) { observations.joinToString("\n") }

    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }

    private suspend fun cleanup(): List<Throwable> {
        val failures = ArrayList<Throwable>()
        suspend fun attempt(description: String, action: suspend () -> Unit) {
            try {
                bounded(description, action)
            } catch (failure: Throwable) {
                failures += AssertionError(description, failure)
            }
        }
        run.closing = true
        attempt("session stop containment") { session?.stop() }
        var consumerReturned = registration == null
        attempt("registration completion") { registration?.unregister(); consumerReturned = true }
        if (rotationControl.touched) {
            val baseline = checkNotNull(rotationControl.baseline)
            attempt("restore saved user rotation locked checkpoint") {
                rotationControl.restoreSavedUserRotationLocked()
                observedCondition("saved user rotation locked checkpoint") { rotationControl.savedUserRotationMatches() }
            }
            if (originalPolicy.first != 0) {
                attempt("restore automatic rotation policy") {
                    rotationControl.restoreAutomaticPolicy()
                    observedCondition("restored automatic rotation policy") { rotationControl.policy().first == originalPolicy.first }
                }
            }
            attempt("restore exact saved user rotation value") { rotationControl.restoreSavedUserRotationValue() }
            attempt("verify original rotation policy and actual geometry together") {
                observedCondition("original rotation policy and actual geometry") {
                    rotationControl.originalStateMatches() &&
                            run.drawn?.display == baseline && main { run.activity?.requestedOrientation == originalRequestedOrientation }
                }
            }
            attempt("save rotation restoration receipt") {
                evidence.text(
                    "rotation-restoration", "baseline=$baseline originalActivityRequest=$originalRequestedOrientation\n" +
                            "${rotationFacts()}\nrestorationFailures=${failures.map { it.message }}\n"
                )
            }
        }
        sourceResizeLab?.let { owner ->
            attempt("restore exact original display-size override") { owner.restoreExact() }
            attempt("verify original logical controller/window/source geometry") {
                val originalSize = owner.original.size.effective
                observedCondition("original logical source geometry after size restoration") {
                    rotationControl.controllerMatches(originalSize.width, originalSize.height, 0) &&
                            sourceLayoutMatches(originalSize.width, originalSize.height, owner.original.density.effective, -1)
                }
            }
            attempt("verify original physical/override/density/rotation/night state") {
                check(owner.readEnvironment() == owner.original) { "Original lab environment was not restored exactly" }
            }
            attempt("save display-size restoration receipt") {
                evidence.text(
                    "source-resize-restoration", "saved=${owner.original}\ncurrent=${owner.readEnvironment()}\n" +
                            "${rotationFacts()}\nrestorationFailures=${failures.map { it.message }}\n${owner.receipts.joinToString("\n")}\n"
                )
            }
        }
        attempt("foreground service shutdown") {
            main {
                run.service?.let { service -> service.stopForeground(Service.STOP_FOREGROUND_REMOVE); service.stopSelf() }
            }
            if (run.service != null) while (!run.serviceDestroyed) run.changes.receive()
        }
        attempt("Activity closure") { scenario?.close() }
        attempt("saved device policy verification") { check(rotationControl.policy() == originalPolicy) }
        attempt("test notification channel removal") {
            if (Build.VERSION.SDK_INT >= 26 && run.service == null) {
                context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(ProjectionTestService.CHANNEL)
            }
        }
        observerScope.cancel()
        // Retain a failed run if a callback/service remains unresolved; containment is not physical cleanup proof.
        if (registryInstalled && consumerReturned && run.service == null && run.activity == null) ProjectionTestRuns.remove(run)
        return failures
    }

    private fun reportOutcome(failure: Throwable?) {
        evidence.text(
            "history-outcome", "history=$history\nrun=${run.id}\nplanned=$planned\nreached=$reached\n" +
                    "unreached=${planned.filterNot { it in reached }}\nstageResults=$stageResults\nimageFailures=${imageFailures.map { it.message }}\n" +
                    "failure=${failure?.stackTraceToString()}\nstate=${session?.state?.value}\n" +
                    "policy=${rotationControl.policy()} original=$originalPolicy\n${observations()}\n${run.receipts.joinToString("\n")}\n"
        )
    }

    internal companion object {
        private const val CHECKPOINT_MS: Long = 15_000
        private const val CONSENT_MS: Long = 10_000

        fun history(name: String, planned: List<String>, action: suspend ProjectionCaptureFixture.() -> Unit) {
            runBlocking {
                val fixture = ProjectionCaptureFixture(name, planned)
                var primary: Throwable? = null
                try {
                    fixture.open()
                    fixture.action()
                } catch (failure: Throwable) {
                    primary = fixture.imageFailures.firstOrNull() ?: failure
                    if (primary !== failure) primary.addSuppressed(failure)
                }
                for (failure in fixture.imageFailures) {
                    val previous = primary
                    if (previous == null) primary = failure else if (previous !== failure) previous.addSuppressed(failure)
                }
                for (failure in fixture.diagnosticFailures) {
                    val previous = primary
                    if (previous == null) primary = failure else previous.addSuppressed(failure)
                }
                for (failure in fixture.cleanup()) {
                    val previous = primary
                    if (previous == null) primary = failure else previous.addSuppressed(failure)
                }
                try {
                    fixture.reportOutcome(primary)
                } catch (failure: Throwable) {
                    val previous = primary
                    if (previous == null) primary = failure else previous.addSuppressed(failure)
                }
                primary?.let { throw it }
            }
        }
    }
}

internal fun ProjectionCaptureFixture.explicitHalfSizeStretchParameters(): ScreenCaptureParameters = ScreenCaptureParameters(
    outputSize = OutputSize.TargetSize((display.width + 1) / 2, (display.height + 1) / 2, OutputSize.ContentMode.Stretch),
)
