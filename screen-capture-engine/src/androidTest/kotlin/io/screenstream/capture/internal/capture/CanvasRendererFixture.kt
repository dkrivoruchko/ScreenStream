package io.screenstream.capture.internal.capture

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import org.junit.Assert.assertEquals
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns the real Canvas-to-GLES path and exact thread-affine graphics retirement for raw-pixel tests. */
internal class CanvasRendererFixture : TargetOwner.SourceSink, CaptureCallbackBoundary {
    private val captureThread = HandlerThread("sce-img01-renderer").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val pendingTicket = AtomicReference<ReadTicket?>()
    private val closed = AtomicBoolean(false)

    fun render(
        plan: CapturePlan,
        targetImage: RawPixelOracle.TargetImage,
        caseName: String,
    ): Frame {
        check(!closed.get())
        val resources = runOnCaptureThread("$caseName setup") { openResources(plan) }
        var primaryFailure: Throwable? = null
        try {
            reportPrecision(caseName, resources.precision, selectedOutcome = "selected/attempted")
            val carrier = ByteBuffer.allocateDirect(plan.rgbaCarrierByteCount)
            val ticket = ReadTicket(caseName, resources.renderer, carrier)
            check(pendingTicket.compareAndSet(null, ticket)) { "$caseName: another frame ticket is pending" }
            postTargetImage(resources.target.producerSurface, targetImage, ticket)
            ticket.await()
            reportPrecision(caseName, resources.precision, selectedOutcome = "exercised")
            return Frame(carrier, resources.precision)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                closeResources(resources, caseName)
            } catch (cleanupFailure: Throwable) {
                val primary = primaryFailure
                if (primary != null) {
                    primary.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    override fun onSourceAvailable(availability: SourceAvailability) {
        val ticket = pendingTicket.getAndSet(null) ?: return
        val accepted = try {
            captureHandler.post { ticket.read() }
        } catch (failure: Throwable) {
            ticket.fail(failure)
            return
        }
        if (!accepted) ticket.fail(AssertionError("${ticket.caseName}: readback post was rejected"))
    }

    override fun onCallbackException(identity: CaptureCallbackIdentity, failure: Exception) {
        pendingTicket.getAndSet(null)?.fail(failure)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingTicket.getAndSet(null)?.fail(AssertionError("renderer harness closed with a pending frame ticket"))
        val quitAccepted = captureThread.quitSafely()
        captureThread.join(TIMEOUT_MILLIS)
        check(quitAccepted) { "Capture HandlerThread rejected quitSafely" }
        check(!captureThread.isAlive) { "Capture HandlerThread did not terminate within $TIMEOUT_MILLIS ms" }
    }

    private fun openResources(plan: CapturePlan): OpenedResources {
        val eglOwner = EglOwner()
        var targetOwner: TargetOwner? = null
        var renderer: GLRenderer? = null
        try {
            val precision = eglOwner.open()
            val openedTarget = TargetOwner(
                captureHandler = captureHandler,
                eglOwner = eglOwner,
                sourceSink = this,
                callbackBoundary = this,
                platformSdkInt = Build.VERSION.SDK_INT,
            )
            targetOwner = openedTarget
            openedTarget.open(plan)
            openedTarget.installListener()
            val openedRenderer = GLRenderer(
                eglOwner = eglOwner,
                targetOwner = openedTarget,
                precision = precision,
                clock = { 0L },
                platformSdkInt = Build.VERSION.SDK_INT,
            )
            renderer = openedRenderer
            openedRenderer.open(plan)
            return OpenedResources(eglOwner, openedTarget, openedRenderer, precision)
        } catch (failure: Throwable) {
            val cleanup = retireOnCaptureThread(eglOwner, targetOwner, renderer)
            cleanup.addTo(failure)
            throw failure
        }
    }

    private fun postTargetImage(
        surface: Surface,
        targetImage: RawPixelOracle.TargetImage,
        ticket: ReadTicket,
    ) {
        val bitmap = Bitmap.createBitmap(targetImage.widthPx, targetImage.heightPx, Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        bitmap.setPixels(
            targetImage.copyTopDownArgbPixels(),
            0,
            targetImage.widthPx,
            0,
            0,
            targetImage.widthPx,
            targetImage.heightPx,
        )
        var primaryFailure: Throwable? = null
        var canvasLocked = false
        var unlockEntered = false
        var canvas: android.graphics.Canvas? = null
        try {
            canvas = surface.lockCanvas(null)
            canvasLocked = true
            assertEquals("${ticket.caseName}: Canvas width", targetImage.widthPx, canvas.width)
            assertEquals("${ticket.caseName}: Canvas height", targetImage.heightPx, canvas.height)
            val paint = Paint().apply {
                isAntiAlias = false
                isDither = false
                isFilterBitmap = false
            }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            unlockEntered = true
            surface.unlockCanvasAndPost(canvas)
            canvasLocked = false
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (canvasLocked && !unlockEntered) {
                try {
                    surface.unlockCanvasAndPost(checkNotNull(canvas))
                } catch (unlockFailure: Throwable) {
                    val primary = primaryFailure
                    if (primary != null) primary.addSuppressed(unlockFailure) else throw unlockFailure
                }
            }
            bitmap.recycle()
            if (primaryFailure != null) {
                pendingTicket.compareAndSet(ticket, null)
                ticket.fail(primaryFailure)
            }
        }
    }

    private fun closeResources(resources: OpenedResources, caseName: String) {
        val completion = BlockingCommand<Unit>("$caseName cleanup")
        val fenceAccepted = captureHandler.post {
            val listenerRemoval = try {
                resources.target.fenceAndRemoveListener()
            } catch (failure: Throwable) {
                TargetOwner.ListenerRemovalOutcome(proof = null, failure = failure)
            }
            pendingTicket.getAndSet(null)?.fail(AssertionError("$caseName: source did not settle before listener fence"))
            val disposeAccepted = try {
                captureHandler.post {
                    val report = retireOnCaptureThread(
                        eglOwner = resources.eglOwner,
                        targetOwner = resources.target,
                        renderer = resources.renderer,
                        listenerRemoval = listenerRemoval,
                    )
                    try {
                        report.throwIfAny("$caseName: owner cleanup was not exact")
                        completion.succeed(Unit)
                    } catch (failure: Throwable) {
                        completion.fail(failure)
                    }
                }
            } catch (failure: Throwable) {
                completion.fail(failure)
                false
            }
            if (!disposeAccepted) completion.fail(AssertionError("$caseName: cleanup tail post was rejected"))
        }
        if (!fenceAccepted) completion.fail(AssertionError("$caseName: listener-fence post was rejected"))
        completion.await()
    }

    private fun retireOnCaptureThread(
        eglOwner: EglOwner,
        targetOwner: TargetOwner?,
        renderer: GLRenderer?,
        listenerRemoval: TargetOwner.ListenerRemovalOutcome? = null,
    ): FailureReport {
        val failures = FailureReport()
        val removal = listenerRemoval ?: targetOwner?.let { target ->
            try {
                target.fenceAndRemoveListener()
            } catch (failure: Throwable) {
                failures.add(failure)
                null
            }
        }
        failures.add(removal?.failure)

        val rendererOutcome = renderer?.let { owner ->
            try {
                owner.close()
            } catch (failure: Throwable) {
                failures.add(failure)
                null
            }
        }
        failures.add(rendererOutcome?.cleanupFailure)

        val targetOutcome = targetOwner?.let { owner ->
            try {
                owner.releaseKnownUnattached(removal?.proof)
            } catch (failure: Throwable) {
                failures.add(failure)
                null
            }
        }
        failures.add(targetOutcome?.cleanupFailure)
        if (targetOwner?.blocksEglInitializationRelease == true) {
            failures.add(CapturePhysicalException("Target still blocks EGL teardown"))
        }

        val rendererRetired = (renderer == null) || ((rendererOutcome != null) && (rendererOutcome.residue == null))
        val healthyPrerequisites = (targetOwner?.blocksEglInitializationRelease != true) && rendererRetired
        val eglOutcome = if (healthyPrerequisites || !eglOwner.isHealthy) {
            try {
                eglOwner.close(
                    allowInitializationRelease = targetOwner?.blocksEglInitializationRelease != true,
                )
            } catch (failure: Throwable) {
                failures.add(failure)
                null
            }
        } else {
            failures.add(CapturePhysicalException("Healthy EGL teardown prerequisites remain unproved"))
            null
        }
        failures.add(eglOutcome?.cleanupFailure)
        failures.add(eglOutcome?.residue)

        val namespaceProof = eglOutcome?.namespaceDestroyedProof
        val rendererResidueRetired = (namespaceProof != null) && (renderer != null) && renderer.retireGLNamesAfterContextDestroyed(namespaceProof)
        val targetResidueRetired = (namespaceProof != null) && (targetOwner != null) && targetOwner.retireOesTextureNameAfterContextDestroyed(namespaceProof)
        if (!rendererResidueRetired) failures.add(rendererOutcome?.residue)
        if (!targetResidueRetired) failures.add(targetOutcome?.residue)
        return failures
    }

    private fun <T> runOnCaptureThread(name: String, action: () -> T): T {
        val command = BlockingCommand(name, action)
        val accepted = captureHandler.post(command)
        if (!accepted) command.fail(AssertionError("$name post was rejected"))
        return command.await()
    }

    private fun reportPrecision(
        caseName: String,
        precision: EglOwner.FragmentPrecision,
        selectedOutcome: String,
    ) {
        val selectedBranch: String
        val unselectedBranch: String
        when (precision) {
            EglOwner.FragmentPrecision.High -> {
                selectedBranch = "highp"
                unselectedBranch = "mediump"
            }

            EglOwner.FragmentPrecision.Medium -> {
                selectedBranch = "mediump"
                unselectedBranch = "highp"
            }
        }
        Log.i(
            PRECISION_LOG_TAG,
            "case=$caseName, naturalFragmentPrecision=$selectedBranch, " +
                    "$selectedBranch=$selectedOutcome, $unselectedBranch=unexercised",
        )
    }


    private class ReadTicket(
        val caseName: String,
        private val renderer: GLRenderer,
        private val carrier: ByteBuffer,
    ) {
        private val completion = BlockingCommand<Unit>("$caseName readback")

        fun read() {
            try {
                renderer.readFrame(carrier)
                completion.succeed(Unit)
            } catch (failure: Throwable) {
                completion.fail(failure)
            }
        }

        fun fail(failure: Throwable) {
            completion.fail(failure)
        }

        fun await() {
            completion.await()
        }
    }

    private class BlockingCommand<T>(
        private val name: String,
        private val action: (() -> T)? = null,
    ) : Runnable {
        private sealed interface Outcome<out T> {
            class Value<T>(val value: T) : Outcome<T>
            class Failed(val failure: Throwable) : Outcome<Nothing>
        }

        private val completion = CountDownLatch(1)
        private val outcome = AtomicReference<Outcome<T>?>()

        override fun run() {
            val command = checkNotNull(action)
            try {
                succeed(command())
            } catch (failure: Throwable) {
                fail(failure)
            }
        }

        fun succeed(value: T) {
            if (outcome.compareAndSet(null, Outcome.Value(value))) completion.countDown()
        }

        fun fail(failure: Throwable) {
            if (outcome.compareAndSet(null, Outcome.Failed(failure))) completion.countDown()
        }

        fun await(): T {
            if (!completion.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw AssertionError("$name did not settle within $TIMEOUT_MILLIS ms")
            }
            return when (val settled = checkNotNull(outcome.get())) {
                is Outcome.Value -> settled.value
                is Outcome.Failed -> throw settled.failure
            }
        }
    }

    private class FailureReport {
        private val failures = mutableListOf<Throwable>()

        fun add(failure: Throwable?) {
            if ((failure != null) && failures.none { it === failure }) failures += failure
        }

        fun addTo(primary: Throwable) {
            for (failure in failures) primary.addSuppressed(failure)
        }

        fun throwIfAny(message: String) {
            if (failures.isEmpty()) return
            val assertion = AssertionError(message)
            for (failure in failures) assertion.addSuppressed(failure)
            throw assertion
        }
    }

    private class OpenedResources(
        val eglOwner: EglOwner,
        val target: TargetOwner,
        val renderer: GLRenderer,
        val precision: EglOwner.FragmentPrecision,
    )

    internal class Frame(
        val carrier: ByteBuffer,
        val precision: EglOwner.FragmentPrecision,
    )

    private companion object {
        private const val TIMEOUT_MILLIS = 10_000L
        private const val PRECISION_LOG_TAG = "GLRendererRawPixel"
    }
}
