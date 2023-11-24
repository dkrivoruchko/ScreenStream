package info.dvkr.screenstream.webrtc.internal

import android.content.Context
import info.dvkr.screenstream.webrtc.BuildConfig
import info.dvkr.screenstream.webrtc.WebRtcKoinScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koin.core.annotation.Scope

@Scope(WebRtcKoinScope::class)
internal class WebRtcEnvironment(context: Context) {
    internal val packageName: String = context.applicationContext.packageName
    internal val cloudProjectNumber: Long = BuildConfig.CLOUD_PROJECT_NUMBER.toLong()
    internal val signalingServerUrl: String = BuildConfig.SIGNALING_SERVER
    internal val noncePath: String = "/app/nonce"
    internal val socketPath: String = "/app/socket"
    internal val signalingServerHost: String = signalingServerUrl.toHttpUrl().host
    internal val signalingServerNonceUrl: HttpUrl = (signalingServerUrl + noncePath).toHttpUrl()
}