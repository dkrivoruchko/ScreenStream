package io.screenstream.mjpeg.device

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Test-only, nonce-bound coordination through instrumentation status and private run-as files. */
internal class MjpegLanPeerProtocol(
    private val instrumentation: Instrumentation,
    context: Context,
    arguments: Bundle,
    private val deviceAddress: String,
    private val interfaceName: String,
) : Closeable {
    private val runId = checkNotNull(arguments.getString("lanRunId"))
    private val topology = checkNotNull(arguments.getString("lanTopology"))
    private val packageName = context.packageName
    private val receiptDirectory: File

    init {
        require(runId.matches(Regex("[a-f0-9]{32}"))) { "Invalid external-peer run ID" }
        require(topology in setOf("emulator-console", "direct")) { "Unsupported external-peer topology" }
        receiptDirectory = File(context.cacheDir, "mjpeg-lan-peer-$runId")
        check(receiptDirectory.mkdir()) { "External-peer run directory already exists" }
    }

    fun exchange(phase: String, devicePort: Int, expectedJpeg: ByteArray) {
        require(phase in setOf("denied", "granted"))
        val expectedSha256 = MessageDigest.getInstance("SHA-256").digest(expectedJpeg)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val receiptFile = File(receiptDirectory, "$phase.json")
        check(!receiptFile.exists()) { "Peer phase already has a receipt" }
        val probeRequest = JSONObject()
            .put("version", 1)
            .put("runId", runId)
            .put("phase", phase)
            .put("topology", topology)
            .put("packageName", packageName)
            .put("deviceAddress", deviceAddress)
            .put("interfaceName", interfaceName)
            .put("devicePort", devicePort)
            .put("expectedSha256", expectedSha256)
            .put("expectedBytes", expectedJpeg.size)
            .put("receiptPath", "cache/${receiptDirectory.name}/$phase.json")
        instrumentation.sendStatus(0, Bundle().apply { putString("mjpegLanPeer", probeRequest.toString()) })

        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (!receiptFile.isFile && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        check(receiptFile.isFile) { "No external-peer receipt for $phase; run the LAN peer runner" }
        check(receiptFile.length() in 1L..16_384L) { "Invalid external-peer receipt size" }
        val probeReceipt = JSONObject(receiptFile.readText())
        check(probeReceipt.getInt("version") == 1 && probeReceipt.getString("runId") == runId && probeReceipt.getString("phase") == phase) {
            "Stale or mismatched external-peer receipt"
        }
        check(probeReceipt.getString("topology") == topology && probeReceipt.getString("deviceAddress") == deviceAddress && probeReceipt.getInt("devicePort") == devicePort) {
            "External-peer receipt targets another endpoint"
        }
        check(probeReceipt.getBoolean("passed")) { "External peer failed $phase: ${probeReceipt.optString("error")}" }
        check(probeReceipt.getBoolean("probePerformed")) { "Peer did not perform an HTTP probe" }
        if (phase == "denied") {
            check(probeReceipt.getString("result") == "unreachable") { "Denied LAN endpoint answered an external request" }
        } else {
            check(probeReceipt.getString("jpegSha256") == expectedSha256 && probeReceipt.getString("multipartSha256") == expectedSha256) {
                "External peer received different JPEG or multipart bytes"
            }
            check(probeReceipt.getInt("jpegBytes") == expectedJpeg.size && probeReceipt.getInt("multipartBytes") == expectedJpeg.size) {
                "External peer received incomplete JPEG or multipart bytes"
            }
        }
    }

    override fun close() {
        check(receiptDirectory.deleteRecursively()) { "Could not remove external-peer coordination files" }
    }
}
