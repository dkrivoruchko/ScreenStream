package io.screenstream.mjpeg.device

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.ResultsReporter
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.MjpegStreamingModuleService
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import io.screenstream.mjpeg.settings.ImageSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

/** Small test-only fixture around the production module graph and real Android capture host. */
internal class MjpegDeviceTestSupport(val compose: ComposeTestRule) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private var consentObservation = 0
    private val evidenceRunId = UUID.randomUUID().toString()
    private val evidenceDirectory by lazy {
        File(context.cacheDir, "mjpeg-device-evidence/$evidenceRunId").apply { check(mkdirs()) }
    }
    private val resultsReporter by lazy { ResultsReporter("MJPEG $evidenceRunId") }

    fun openHost(
        networkFilter: NetworkAddressFilter = MjpegCaptureTestHost.loopbackFilter(),
        imageSettings: ImageSettings = ImageSettings(),
    ): OpenHost {
        assertEquals("The fixture must exercise modern target-SDK behavior", 37, context.applicationInfo.targetSdkVersion)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val host = MjpegCaptureTestHost(context, port, networkFilter, imageSettings)
        var scenario: ActivityScenario<MjpegCaptureTestActivity>? = null
        try {
            host.start()
            scenario = ActivityScenario.launch(MjpegCaptureTestActivity::class.java)
            scenario.onActivity { host.manager.selectModule(StreamingModule.Id("MJPEG")) }
            await(host, "module startup") { running(host) != null }
            await(host, "inactive HTTP listener") { canConnect(port) }
            assertTrue("The production Service must be running", serviceIsRunning())
            return OpenHost(host, scenario)
        } catch (failure: Throwable) {
            try {
                closeHost(host, scenario)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    inner class OpenHost(val host: MjpegCaptureTestHost, val scenario: ActivityScenario<MjpegCaptureTestActivity>) : Closeable {
        override fun close() {
            // Containment only. Tests establish their Exit/FD verdict before entering this method.
            closeHost(host, scenario)
        }
    }

    private fun closeHost(host: MjpegCaptureTestHost, scenario: ActivityScenario<MjpegCaptureTestActivity>?) {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (cleanupFailure: Throwable) {
                val previous = failure
                if (previous == null) failure = cleanupFailure else previous.addSuppressed(cleanupFailure)
            }
        }
        if (MjpegCaptureTestHost.current === host) {
            attempt { compose.runOnUiThread { host.manager.exit() } }
            attempt { await(host, "failure containment") { !serviceIsRunning() && !canConnect(host.port) } }
        }
        attempt { scenario?.close() }
        if (MjpegCaptureTestHost.current === host) attempt { host.closeGraph() }
        failure?.let { throw it }
    }

    data class ConsentAdmission(val attempt: StreamingModule.CaptureAttemptId, val usesSystemConsent: Boolean)

    fun submitCapture(host: MjpegCaptureTestHost): ConsentAdmission {
        val submissionsBefore = host.submittedConsents.size
        val reusesBefore = host.reusedConsents.size
        compose.onNodeWithText(context.getString(R.string.mjpeg_stream_start_capture)).assertIsEnabled().performClick()
        val continueText = context.getString(info.dvkr.screenstream.common.R.string.common_continue)
        await(host, "capture education or consent admission") {
            host.submittedConsents.size > submissionsBefore || host.reusedConsents.size > reusesBefore ||
                    compose.onAllNodesWithText(continueText).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        if (host.submittedConsents.size == submissionsBefore && host.reusedConsents.size == reusesBefore &&
            compose.onAllNodesWithText(continueText).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        ) {
            compose.onNodeWithText(continueText).performClick()
        }
        await(host, "consent source") {
            host.submittedConsents.size > submissionsBefore || host.reusedConsents.size > reusesBefore
        }
        val submitted = host.submittedConsents.size > submissionsBefore
        val attempt = if (submitted) host.submittedConsents.last() else host.reusedConsents.last()
        return ConsentAdmission(attempt, submitted)
    }

    fun startCapture(host: MjpegCaptureTestHost): StreamingModule.CaptureAttemptId {
        val admission = submitCapture(host)
        if (admission.usesSystemConsent) approveSystemConsent()
        await(host, "real capture activation") {
            running(host)?.status?.let { it.isStreaming && it.captureAttempt == admission.attempt } == true
        }
        return admission.attempt
    }

    fun consentDialogVisible(): Boolean = device.hasObject(By.res("android:id/button1").pkg("com.android.systemui"))

    fun awaitConsentDialog() {
        checkNotNull(device.wait(Until.findObject(By.res("android:id/button1").pkg("com.android.systemui")), 10_000)) {
            "Real MediaProjection dialog is unavailable; qualify the OS/OEM consent selectors"
        }
    }

    fun approveSystemConsent() {
        awaitConsentDialog()
        val observation = "consent-${++consentObservation}"
        saveUiEvidence("$observation-before-mode")
        val positive = checkNotNull(device.findObject(By.res("android:id/button1").pkg("com.android.systemui")))
        val legacySpinner = device.findObject(By.res("com.android.systemui:id/screen_share_mode_spinner"))
        val spinner = legacySpinner ?: device.findObject(By.res("com.android.systemui:id/screen_share_mode_options"))
        if (spinner != null) {
            // SystemUI's legacy/current delegates use different view and string resource names.
            val systemResources = context.packageManager.getResourcesForApplication("com.android.systemui")
            val entireScreenName = if (legacySpinner != null) {
                "screen_share_permission_dialog_option_entire_screen"
            } else {
                "media_projection_entry_app_permission_dialog_option_text_entire_screen"
            }
            val spinnerResource = if (legacySpinner != null) {
                "com.android.systemui:id/screen_share_mode_spinner"
            } else {
                "com.android.systemui:id/screen_share_mode_options"
            }
            val entireScreenId = systemResources.getIdentifier(
                entireScreenName, "string", "com.android.systemui",
            )
            check(entireScreenId != 0) { "SystemUI does not expose the whole-display consent option" }
            spinner.click()
            saveUiEvidence("$observation-expanded-mode")
            val entireScreenText = systemResources.getString(entireScreenId)
            checkNotNull(device.wait(Until.findObject(By.text(entireScreenText).pkg("com.android.systemui")), 5_000)).click()
            check(device.wait(Until.hasObject(By.res(spinnerResource).hasDescendant(By.text(entireScreenText))), 5_000)) {
                "SystemUI did not confirm the selected whole-display mode"
            }
            saveUiEvidence("$observation-selected-whole-display")
            checkNotNull(device.wait(Until.findObject(By.res("android:id/button1").pkg("com.android.systemui")), 5_000)).click()
        } else {
            check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                "Modern SystemUI has no qualified whole-display mode control; see $observation-before-mode.xml"
            }
            positive.click()
        }
    }

    fun declineSystemConsent() {
        awaitConsentDialog()
        checkNotNull(device.findObject(By.res("android:id/button2").pkg("com.android.systemui"))) {
            "SystemUI consent cancellation button unavailable"
        }.click()
    }

    fun assertExitAndReleased(
        host: MjpegCaptureTestHost,
        stream: MultipartViewer? = null,
        expectedInstanceId: StreamingModule.InstanceId? = null,
    ) {
        compose.runOnUiThread { host.manager.exit(expectedInstanceId) }
        await(host, "production Service destruction") { !serviceIsRunning() }
        stream?.assertClosedByServer()
        await(host, "listener closure") { !canConnect(host.port) }
        assertPortReusable(host.port)
        assertTrue(host.manager.state.value is StreamingModuleManager.State.Exiting)
        assertTrue("Unexpected module errors: ${host.notificationErrors}", host.notificationErrors.isEmpty())
    }

    fun assertPortReusable(port: Int) {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            assertEquals(port, socket.localPort)
        }
    }

    fun connection(port: Int, path: String): HttpURLConnection = Companion.connection(port, path)

    fun saveJpegEvidence(name: String, bytes: ByteArray): File = writeEvidence(name, "jpg") { it.writeBytes(bytes) }

    fun saveTextEvidence(name: String, text: String): File = writeEvidence(name, "txt") { it.writeText(text) }

    fun saveUiEvidence(name: String) {
        writeEvidence(name, "xml") { device.dumpWindowHierarchy(it) }
        writeEvidence(name, "png") {
            check(device.takeScreenshot(it)) { "Could not save native UI evidence: $name" }
        }
    }

    private fun writeEvidence(name: String, extension: String, write: (File) -> Unit): File {
        require(name.matches(Regex("[a-z0-9-]+")))
        val file = File(evidenceDirectory, "$name.$extension")
        write(file)
        Log.i("MjpegDeviceTest", "Evidence: ${file.absolutePath}")
        val reported = resultsReporter.addNewFile("$evidenceRunId-$name-$extension.$extension", name)
        check(reported.parentFile!!.mkdirs() || reported.parentFile!!.isDirectory)
        file.copyTo(reported, overwrite = true)
        // Publish each completed artifact while this test is still running, including failure
        // evidence. Studio can pull it before its runner uninstalls the library test APK.
        resultsReporter.reportToInstrumentation()
        return file
    }

    fun running(host: MjpegCaptureTestHost): StreamingModuleManager.State.Running? =
        host.manager.state.value as? StreamingModuleManager.State.Running

    fun await(host: MjpegCaptureTestHost, description: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(15_000, condition)
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $description; manager=${host.manager.state.value}; errors=${host.notificationErrors}", failure)
        }
    }

    @Suppress("DEPRECATION")
    fun serviceIsRunning(context: Context = this.context): Boolean =
        checkNotNull(context.getSystemService(ActivityManager::class.java)).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == MjpegStreamingModuleService::class.java.name }

    fun canConnect(port: Int): Boolean = try {
        java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
        true
    } catch (_: java.io.IOException) {
        false
    }

    fun requestJpeg(port: Int, path: String = "/jpeg"): ByteArray = connection(port, path).let { connection ->
        try {
            assertEquals(200, connection.responseCode)
            assertEquals("image/jpeg", connection.contentType.substringBefore(';').trim())
            val length = connection.contentLength
            connection.inputStream.use { input -> readJpegPayload(input, length) }
        } finally {
            connection.disconnect()
        }
    }

    fun awaitJpegTarget(port: Int, green: Boolean): ByteArray = awaitJpeg(
        port = port,
        evidenceName = if (green) "target-green" else "target-red",
        description = "expectedGreen=$green",
        matches = { matchesTarget(it, green) },
    )

    fun awaitJpeg(
        port: Int,
        evidenceName: String,
        description: String,
        details: () -> String = { "" },
        matches: (ByteArray) -> Boolean,
    ): ByteArray {
        var result: ByteArray? = null
        var latest: ByteArray? = null
        try {
            compose.waitUntil(15_000) {
                requestJpeg(port).let { bytes ->
                    latest = bytes
                    matches(bytes).also { if (it) result = bytes }
                }
            }
        } catch (failure: Throwable) {
            // Preserve the actual received image and current native window before containment can
            // hide a rendering/foreground mismatch. Diagnostics never change the original verdict.
            fun record(action: () -> Unit) {
                try {
                    action()
                } catch (diagnosticFailure: Throwable) {
                    failure.addSuppressed(diagnosticFailure)
                }
            }

            val name = "$evidenceName-failure"
            record { latest?.let { saveJpegEvidence("$name-latest", it) } }
            record {
                val host = MjpegCaptureTestHost.current
                val report = saveTextEvidence(name, buildString {
                    appendLine(description)
                    appendLine("jpeg=${describeJpeg(latest)}")
                    appendLine("redTarget=${latest?.let { matchesTarget(it, green = false) }}")
                    appendLine("greenTarget=${latest?.let { matchesTarget(it, green = true) }}")
                    appendLine("manager=${host?.manager?.state?.value}")
                    appendLine("submittedConsents=${host?.submittedConsents?.size}; reusedConsents=${host?.reusedConsents?.size}")
                    appendLine("resultReceipts=${host?.consentResults}")
                    appendLine("notificationErrors=${host?.notificationErrors}")
                    appendLine(details())
                })
                failure.addSuppressed(AssertionError("JPEG failure evidence: ${report.absolutePath}"))
            }
            record { saveUiEvidence("$name-window") }
            throw failure
        }
        return checkNotNull(result)
    }

    private fun describeJpeg(bytes: ByteArray?): String {
        if (bytes == null) return "no completed JPEG response received"
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return "${bytes.size} bytes; decode failed"
        return try {
            val y = bitmap.height * 3 / 4
            val points = listOf(bitmap.width / 8, bitmap.width / 2, bitmap.width * 7 / 8)
            "${bytes.size} bytes; ${bitmap.width}x${bitmap.height}; predicate row y=$y; " +
                    points.joinToString { x -> "($x,$y)=0x${Integer.toHexString(bitmap.getPixel(x, y))}" }
        } finally {
            bitmap.recycle()
        }
    }

    class MultipartViewer(port: Int) : Closeable {
        private val connection = connection(port, "/mjpeg")
        private val input: InputStream
        private val reader: MjpegMultipartReader

        init {
            try {
                assertEquals(200, connection.responseCode)
                val type = checkNotNull(connection.contentType)
                assertEquals("multipart/x-mixed-replace", type.substringBefore(';').trim())
                check("boundary=" in type) { "Missing multipart boundary: $type" }
                input = BufferedInputStream(connection.inputStream)
                reader = MjpegMultipartReader(input, type.substringAfter("boundary=").trim().trim('"'))
            } catch (failure: Throwable) {
                connection.disconnect()
                throw failure
            }
        }

        fun nextFrame(): ByteArray = reader.nextFrame()

        fun awaitTarget(green: Boolean) = awaitFrame(if (green) "green captured target" else "red captured target") {
            matchesTarget(it, green)
        }

        fun awaitFrame(description: String, matches: (ByteArray) -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            do {
                if (matches(nextFrame())) return
            } while (android.os.SystemClock.elapsedRealtime() < deadline)
            throw AssertionError("Multipart did not deliver $description")
        }

        fun assertClosedByServer() {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            val remaining = ByteArray(8192)
            try {
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (input.read(remaining) < 0) return
                }
            } catch (failure: SocketTimeoutException) {
                throw AssertionError("Exit left the multipart connection open", failure)
            } catch (_: EOFException) {
                // Android's HTTP chunk decoder reports a socket EOF as an exception when Exit
                // cancels the open-ended response before its terminating HTTP chunk.
                return
            } catch (failure: ProtocolException) {
                // HttpURLConnection uses this exact error for socket EOF inside a chunk body.
                // Other protocol errors, including malformed chunk headers, remain failures.
                if (failure.message == "unexpected end of stream") return
                throw failure
            } catch (_: SocketException) {
                return // A reset also establishes that the production server closed this response.
            }
            throw AssertionError("Exit kept delivering multipart data")
        }

        override fun close() {
            try {
                input.close()
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        fun connection(port: Int, path: String): HttpURLConnection =
            (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2_000
                readTimeout = 5_000
                useCaches = false
            }

        fun matchesTarget(bytes: ByteArray, green: Boolean): Boolean {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            try {
                if (bitmap.width < 8 || bitmap.height < 8) return false
                val y = bitmap.height * 3 / 4
                val left = bitmap.getPixel(bitmap.width / 8, y)
                val center = bitmap.getPixel(bitmap.width / 2, y)
                val right = bitmap.getPixel(bitmap.width * 7 / 8, y)
                val blueBlock = Color.blue(left) > 180 && Color.red(left) < 80 && Color.green(left) < 80
                val whiteBlock = Color.red(right) > 180 && Color.green(right) > 180 && Color.blue(right) > 180
                val middle = if (green) {
                    Color.green(center) > 180 && Color.red(center) < 80 && Color.blue(center) < 80
                } else {
                    Color.red(center) > 180 && Color.green(center) < 80 && Color.blue(center) < 80
                }
                return blueBlock && whiteBlock && middle
            } finally {
                bitmap.recycle()
            }
        }
    }
}
