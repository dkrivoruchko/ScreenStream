package info.dvkr.screenstream.webrtc.internal

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.WebRtcEnvironment
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.ZonedDateTime
import java.util.Date
import javax.security.auth.x500.X500Principal

internal object JWTHelper {

    private const val KEY_ALIAS = "ScreenStreamWebRTC"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val BEGIN_PUBLIC_KEY = "-----BEGIN PUBLIC KEY-----"
    private const val END_PUBLIC_KEY = "-----END PUBLIC KEY-----"

    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    init {
        XLog.d(getLog("init", "Key alias: $KEY_ALIAS"))
        if (keyStore.containsAlias(KEY_ALIAS).not()) createKey()
    }

    @Throws
    internal fun createJWT(environment: WebRtcEnvironment, streamId: String): String {
        val headerString = JSONObject()
            .put("typ", "JWT")
            .put("alg", "ES256")
            .toString()
        val base64Header = headerString.toByteArray().toBase64UrlSafeNoPadding

        val payloadString = JSONObject()
            .put("aud", environment.signalingServerHost)
            .put("iss", environment.packageName)
            .apply { if (streamId.isNotEmpty()) put("streamId", streamId) }
            .put("pubKey", getPublicKeyAsString())
            .toString()

        val base64Payload = payloadString.toByteArray().toBase64UrlSafeNoPadding

        val headerAndPayload = "$base64Header.$base64Payload"

        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(getPrivateKey(), SecureRandom())
            update(headerAndPayload.toByteArray())
            sign()
        }

        return "$headerAndPayload.${derSignatureToConcat(signature).toBase64UrlSafeNoPadding}"
    }

    @Throws
    internal fun createKey() {
        runCatching {
            XLog.d(getLog("createKey", "Key alias: $KEY_ALIAS"))

            val parameterSpec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setCertificateSubject(X500Principal("CN=$KEY_ALIAS"))
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateNotBefore(Date.from(ZonedDateTime.now().minusDays(1).toInstant()))
                .setCertificateNotAfter(Date.from(ZonedDateTime.now().plusYears(10).toInstant()))
                .build()

            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
                initialize(parameterSpec)
                generateKeyPair()
            }
        }
            .onFailure { XLog.e(getLog("createKey", "Key alias: $KEY_ALIAS"), it) }
            .getOrThrow()
    }

    @Throws
    internal fun removeKey() {
        XLog.d(getLog("remove", "Key alias: $KEY_ALIAS"))
        keyStore.deleteEntry(KEY_ALIAS)
    }

    private val ByteArray.toBase64UrlSafeNoPadding
        inline get() : String = Base64.encodeToString(this, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    private fun derSignatureToConcat(derSignature: ByteArray, outputLength: Int = 64): ByteArray {
        if (derSignature.size < 8 || derSignature[0].toInt() != 48) throw IllegalArgumentException("Invalid ECDSA signature format")

        val offset: Int = when {
            derSignature[1] > 0 -> 2
            derSignature[1] == 0x81.toByte() -> 3
            else -> throw IllegalArgumentException("Invalid ECDSA signature format")
        }

        val rLength = derSignature[offset + 1]
        var i: Int = rLength.toInt()
        while (i > 0 && derSignature[offset + 2 + rLength - i].toInt() == 0) i--

        val sLength = derSignature[offset + 2 + rLength + 1]
        var j: Int = sLength.toInt()
        while (j > 0 && derSignature[offset + 2 + rLength + 2 + sLength - j].toInt() == 0) j--

        var rawLen = Math.max(i, j)
        rawLen = Math.max(rawLen, outputLength / 2)

        if (derSignature[offset - 1].toInt() and 0xff != derSignature.size - offset || derSignature[offset - 1].toInt() and 0xff != 2 + rLength + 2 + sLength || derSignature[offset].toInt() != 2 || derSignature[offset + 2 + rLength].toInt() != 2) {
            throw IllegalArgumentException("Invalid ECDSA signature format")
        }

        val concatSignature = ByteArray(2 * rawLen)
        System.arraycopy(derSignature, offset + 2 + rLength - i, concatSignature, rawLen - i, i)
        System.arraycopy(derSignature, offset + 2 + rLength + 2 + sLength - j, concatSignature, 2 * rawLen - j, j)

        return concatSignature
    }

    private fun getPublicKeyAsString(): String {
        XLog.d(getLog("getPublicKeyAsString", "Alias: $KEY_ALIAS"))

        keyStore.containsAlias(KEY_ALIAS) || throw IllegalStateException("Key not found, alias: $KEY_ALIAS")
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey!!

        // https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec#known-issues
        val publicKeyFixed = if (Build.VERSION.SDK_INT != Build.VERSION_CODES.M) publicKey
        else (KeyFactory.getInstance(publicKey.algorithm).generatePublic(X509EncodedKeySpec(publicKey.encoded)) as ECPublicKey)

        return BEGIN_PUBLIC_KEY + "\n" + Base64.encodeToString(publicKeyFixed.encoded, Base64.NO_WRAP) + "\n" + END_PUBLIC_KEY
    }

    private fun getPrivateKey(): PrivateKey {
        XLog.d(getLog("getPrivateKey", KEY_ALIAS))

        keyStore.containsAlias(KEY_ALIAS) || throw IllegalStateException("Key not found, alias: $KEY_ALIAS")
        val key = keyStore.getKey(KEY_ALIAS, null)!!
        return key as? PrivateKey ?: throw IllegalStateException("Key is not PrivateKey, alias: $KEY_ALIAS")
    }
}