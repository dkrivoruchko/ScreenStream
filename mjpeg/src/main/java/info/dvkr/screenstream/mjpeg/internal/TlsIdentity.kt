package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.randomString
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * Self-signed TLS identity for the local HTTPS server.
 *
 * There is no way to get a publicly trusted certificate for a LAN IP, so viewers accept this certificate once
 * (browser warning) and can verify it against the SHA-256 fingerprint shown in the app. HTTPS still gives
 * encryption (PIN and stream are not sniffable) and a secure context (required for WebCodecs).
 *
 * Stored in noBackupFilesDir so the private key never leaves the device via backups.
 * Regenerated when it nears expiry or no longer covers the server's IP addresses.
 */
internal class TlsIdentity(context: Context) {

    internal class Identity(val keyStore: KeyStore, val password: String, val certificate: X509Certificate) {
        val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }
    }

    internal companion object {
        internal const val ALIAS: String = "screenstream"
        private const val VALID_DAYS = 397L
        private const val RENEW_BEFORE_MILLIS = 30L * 24 * 60 * 60 * 1000
    }

    private val keyStoreFile = File(context.noBackupFilesDir, "mjpeg_tls.keystore")
    private val passwordFile = File(context.noBackupFilesDir, "mjpeg_tls.pw")

    @Synchronized
    internal fun get(addresses: List<InetAddress>): Identity {
        val wanted = addresses.map { it.normalized() }.toSet()
        load()?.let { identity ->
            val notExpiring = identity.certificate.notAfter.time - System.currentTimeMillis() > RENEW_BEFORE_MILLIS
            if (notExpiring && identity.certificate.ipSans().containsAll(wanted)) return identity
        }
        return generate(addresses)
    }

    private fun load(): Identity? = runCatching {
        if (!keyStoreFile.exists() || !passwordFile.exists()) return null
        val password = passwordFile.readText()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            keyStoreFile.inputStream().use { load(it, password.toCharArray()) }
        }
        val cert = keyStore.getCertificate(ALIAS) as? X509Certificate ?: return null
        Identity(keyStore, password, cert)
    }.onFailure { XLog.w(getLog("load", it.toString())) }.getOrNull()

    private fun generate(addresses: List<InetAddress>): Identity {
        XLog.i(getLog("generate", "New self-signed certificate for ${addresses.size} address(es)"))
        val password = randomString(32, true)
        val keyStore = buildKeyStore {
            certificate(ALIAS) {
                this.password = password
                hash = HashAlgorithm.SHA256
                sign = SignatureAlgorithm.ECDSA
                keySizeInBits = 256
                daysValid = VALID_DAYS
                subject = X500Principal("CN=ScreenStream, O=ScreenStream local device")
                domains = listOf("localhost")
                ipAddresses = (addresses + InetAddress.getByName("127.0.0.1")).distinctBy { it.normalized() }
            }
        }
        val tmp = File(keyStoreFile.parentFile, keyStoreFile.name + ".tmp")
        tmp.outputStream().use { keyStore.store(it, password.toCharArray()) }
        passwordFile.writeText(password)
        tmp.renameTo(keyStoreFile)
        return Identity(keyStore, password, keyStore.getCertificate(ALIAS) as X509Certificate)
    }

    private fun InetAddress.normalized(): String = InetAddress.getByAddress(address).hostAddress!!

    private fun X509Certificate.ipSans(): Set<String> = (subjectAlternativeNames ?: emptyList())
        .filter { it.size >= 2 && it[0] == 7 }
        .mapNotNull { runCatching { InetAddress.getByName(it[1] as String).normalized() }.getOrNull() }
        .toSet()
}
