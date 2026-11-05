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
    private val address: String,
    private val interfaceName: String,
) : Closeable {
    private val runId = checkNotNull(arguments.getString("lanRunId"))
    private val topology = checkNotNull(arguments.getString("lanTopology"))
    private val packageName = context.packageName
    private val directory: File

    init {
        require(runId.matches(Regex("[a-f0-9]{32}"))) { "Invalid external-peer run ID" }
        require(topology in setOf("emulator-console", "direct")) { "Unsupported external-peer topology" }
        directory = File(context.cacheDir, "mjpeg-lan-peer-$runId")
        check(directory.mkdir()) { "External-peer run directory already exists" }
    }

    fun exchange(phase: String, port: Int, expectedJpeg: ByteArray) {
        require(phase in setOf("denied", "granted"))
        val expectedSha256 = MessageDigest.getInstance("SHA-256").digest(expectedJpeg)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val receipt = File(directory, "$phase.json")
        check(!receipt.exists()) { "Peer phase already has a receipt" }
        val request = JSONObject()
            .put("version", 1)
            .put("runId", runId)
            .put("phase", phase)
            .put("topology", topology)
            .put("packageName", packageName)
            .put("deviceAddress", address)
            .put("interfaceName", interfaceName)
            .put("devicePort", port)
            .put("expectedSha256", expectedSha256)
            .put("expectedBytes", expectedJpeg.size)
            .put("receiptPath", "cache/${directory.name}/$phase.json")
        instrumentation.sendStatus(0, Bundle().apply { putString("mjpegLanPeer", request.toString()) })

        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (!receipt.isFile && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        check(receipt.isFile) { "No external-peer receipt for $phase; run the LAN peer runner" }
        check(receipt.length() in 1L..16_384L) { "Invalid external-peer receipt size" }
        val result = JSONObject(receipt.readText())
        check(result.getInt("version") == 1 && result.getString("runId") == runId && result.getString("phase") == phase) {
            "Stale or mismatched external-peer receipt"
        }
        check(result.getString("topology") == topology && result.getString("deviceAddress") == address && result.getInt("devicePort") == port) {
            "External-peer receipt targets another endpoint"
        }
        check(result.getBoolean("passed")) { "External peer failed $phase: ${result.optString("error")}" }
        check(result.getBoolean("probePerformed")) { "Peer did not perform an HTTP probe" }
        if (phase == "denied") {
            check(result.getString("result") == "unreachable") { "Denied LAN endpoint answered an external request" }
        } else {
            check(result.getString("jpegSha256") == expectedSha256 && result.getString("multipartSha256") == expectedSha256) {
                "External peer received different JPEG or multipart bytes"
            }
            check(result.getInt("jpegBytes") == expectedJpeg.size && result.getInt("multipartBytes") == expectedJpeg.size) {
                "External peer received incomplete JPEG or multipart bytes"
            }
        }
    }

    override fun close() {
        check(directory.deleteRecursively()) { "Could not remove external-peer coordination files" }
    }
}
