package info.dvkr.screenstream.webrtc.internal

import android.os.Build
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.internal.SocketSignaling.Payload.ERROR_TOKEN_VERIFICATION_FAILED
import io.socket.client.Ack
import io.socket.client.AckWithTimeout
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection.IceServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class SocketSignaling(
    private val environment: WebRtcEnvironment,
    private val okHttpClient: OkHttpClient,
    private val eventListener: EventListener,
    private val passwordVerifier: PasswordVerifier
) {

    internal fun interface PasswordVerifier {
        fun isValid(clientId: ClientId, passwordHash: String): Boolean
    }

    internal sealed class Error(internal val retry: Boolean, internal val log: Boolean) : Exception() {
        internal class SocketAuthError(override val message: String, retry: Boolean = false) : Error(retry, false) {
            internal companion object {
                internal fun fromMessage(message: String): SocketAuthError? {
                    val error = message.removePrefix("$ERROR_TOKEN_VERIFICATION_FAILED:")
                    if (message == error) return null // Not this error

                    return when { // Must be identical to server values
                        error.startsWith("NO_TOKEN_FOUND") -> SocketAuthError(message)
                        error.startsWith("EMPTY_PAYLOAD") -> SocketAuthError(message)
                        error.startsWith("EMPTY_REQUEST_DETAILS") -> SocketAuthError(message)
                        error.startsWith("REQUEST_DETAILS_WRONG_PACKAGE_NAME") -> SocketAuthError(message)
                        error.startsWith("INVALID_NONCE") -> SocketAuthError(message, true)
                        error.startsWith("TOKEN_EXPIRED") -> SocketAuthError(message, true)
                        error.startsWith("EMPTY_APP_INTEGRITY") -> SocketAuthError(message)
                        error.startsWith("WRONG_APP_VERDICT") -> SocketAuthError(message)
                        error.startsWith("APP_INTEGRITY_WRONG_PACKAGE_NAME") -> SocketAuthError(message)
                        error.startsWith("APP_INTEGRITY_WRONG_DIGEST") -> SocketAuthError(message)
                        error.startsWith("EMPTY_DEVICE_INTEGRITY") -> SocketAuthError(message)
                        error.startsWith("FAIL_DEVICE_INTEGRITY") -> SocketAuthError(message)
                        else -> SocketAuthError(message, true)
                    }
                }
            }
        }

        internal class SocketCheckError(override val message: String) : Error(false, true)
        internal class SocketConnectError(override val message: String) : Error(true, false)
        internal class StreamCreateError(override val message: String, override val cause: Throwable) : Error(true, true)
        internal class StreamStartError(override val message: String, override val cause: Throwable) : Error(true, true)
    }

    internal interface EventListener {
        fun onSocketConnected()
        fun onTokenExpired()
        fun onSocketDisconnected(reason: String)
        fun onStreamCreated(streamId: StreamId)
        fun onStreamRemoved()
        fun onClientJoin(clientId: ClientId, iceServers: List<IceServer>)
        fun onClientLeave(clientId: ClientId)
        fun onClientNotFound(clientId: ClientId, reason: String)
        fun onClientAnswer(clientId: ClientId, answer: Answer)
        fun onClientCandidate(clientId: ClientId, candidate: IceCandidate)
        fun onHostOfferConfirmed(clientId: ClientId)
        fun onError(cause: Error)
    }

    // Must be identical to server values
    private object Event {
        const val SOCKET_ERROR = "SOCKET:ERROR"
        const val STREAM_CREATE = "STREAM:CREATE"
        const val STREAM_REMOVE = "STREAM:REMOVE"
        const val STREAM_START = "STREAM:START"
        const val STREAM_STOP = "STREAM:STOP"
        const val HOST_OFFER = "HOST:OFFER"
        const val HOST_CANDIDATE = "HOST:CANDIDATE"
        const val STREAM_JOIN = "STREAM:JOIN"
        const val STREAM_LEAVE = "STREAM:LEAVE"
        const val CLIENT_ANSWER = "CLIENT:ANSWER"
        const val CLIENT_CANDIDATE = "CLIENT:CANDIDATE"
        const val REMOVE_CLIENT = "REMOVE:CLIENT"
    }

    // Must be identical to server values
    private object Payload {
        const val WEB_SOCKET_AUTH_TOKEN = "hostToken"

        const val MESSAGE = "message"
        const val STATUS = "status"
        const val OK = "OK"
        const val STREAM_ID = "streamId"
        const val ICE_SERVERS = "iceServers"
        const val PASSWORD_HASH = "passwordHash"
        const val CLIENT_ID = "clientId"
        const val OFFER = "offer"
        const val ANSWER = "answer"

        const val CANDIDATES = "candidates"
        const val CANDIDATE = "candidate"
        const val SPD_INDEX = "sdpMLineIndex"
        const val SPD_MID = "sdpMid"

        const val ERROR_EMPTY_OR_BAD_DATA = "ERROR:EMPTY_OR_BAD_DATA"
        const val ERROR_NO_CLIENT_FOUND = "ERROR:NO_CLIENT_FOUND"
        const val ERROR_WRONG_STREAM_PASSWORD = "ERROR:WRONG_STREAM_PASSWORD"

        const val ERROR_TOKEN_VERIFICATION_FAILED = "ERROR:TOKEN_VERIFICATION_FAILED"
    }

    @Volatile
    private var socket: Socket? = null

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    internal inline fun socketId(): String? = socket?.id()

    init {
        XLog.d(getLog("init"))
    }

    @Throws(IllegalArgumentException::class)
    internal fun openSocket(token: PlayIntegrityToken, gmsVersionName: String) {
        XLog.d(getLog("openSocket"))

        require(socket == null)

        val device = "${Build.MANUFACTURER}:${Build.MODEL}:API${Build.VERSION.SDK_INT}:$gmsVersionName"
        val options = IO.Options.builder()
            .setReconnection(false) //On Socket.EVENT_DISCONNECT or Socket.EVENT_CONNECT_ERROR or Event.SOCKET_ERROR. Auto or User reconnect
            .setPath(environment.socketPath).setTransports(arrayOf(WebSocket.NAME))
            .setAuth(mapOf(Payload.WEB_SOCKET_AUTH_TOKEN to token.value, "device" to device)).build()
            .apply { callFactory = okHttpClient; webSocketFactory = okHttpClient }

        socket = IO.socket(environment.signalingServerUrl, options).apply {
            on(Socket.EVENT_CONNECT) {
                XLog.d(this@SocketSignaling.getLog(Socket.EVENT_CONNECT + "[${socketId()}]", id()))
                eventListener.onSocketConnected()
            }
            on(Socket.EVENT_DISCONNECT) { args -> // Auto reconnect
                XLog.d(this@SocketSignaling.getLog(Socket.EVENT_DISCONNECT, args.contentToString()))
                eventListener.onSocketDisconnected(args.contentToString())
            }
            on(Socket.EVENT_CONNECT_ERROR) { args -> // Auto or User reconnect
                XLog.e(this@SocketSignaling.getLog(Socket.EVENT_CONNECT_ERROR, args.contentToString()))
                val message = (args?.firstOrNull() as? JSONObject)?.optString(Payload.MESSAGE) ?: ""
                if (message.startsWith("$ERROR_TOKEN_VERIFICATION_FAILED:TOKEN_EXPIRED")) {
                    XLog.d(this@SocketSignaling.getLog("openSocket", "TOKEN_EXPIRED"))
                    eventListener.onTokenExpired()
                } else if (message.startsWith("$ERROR_TOKEN_VERIFICATION_FAILED:WRONG_APP_VERDICT")) {
                    XLog.d(this@SocketSignaling.getLog("openSocket", "WRONG_APP_VERDICT"), IllegalStateException("WRONG_APP_VERDICT"))
                    eventListener.onError(Error.SocketAuthError.fromMessage(message) ?: Error.SocketConnectError(message))
                } else
                    eventListener.onError(Error.SocketAuthError.fromMessage(message) ?: Error.SocketConnectError(message))
            }
            on(Event.SOCKET_ERROR) { args -> // Server always disconnects socket on this event. User reconnect
                XLog.e(this@SocketSignaling.getLog(Event.SOCKET_ERROR + "[${socketId()}]", args.contentToString()))
                val message = (args?.firstOrNull() as? JSONObject)?.optString(Payload.STATUS) ?: ""
                eventListener.onError(Error.SocketCheckError(message))
                SocketPayload.fromPayload(args).sendOkAck()
            }
            open()
        }
    }

    internal fun sendStreamCreate(streamId: StreamId) {
        XLog.d(getLog("sendStreamCreate[${socketId()}]", "StreamId: $streamId"))

        val currentSocket = socket ?: return
        currentSocket.connected() || return

        val data = runCatching { JSONObject().put("jwt", JWTHelper.createJWT(environment, streamId.value)) }
            .recoverCatching {
                JWTHelper.removeKey()
                JWTHelper.createKey()
                JSONObject().put("jwt", JWTHelper.createJWT(environment, streamId.value))
            }
            .onFailure { eventListener.onError(Error.StreamCreateError("createJWT error: ${it.message}", it)) }
            .getOrNull() ?: return

        currentSocket.emit(Event.STREAM_CREATE, arrayOf(data), object : AckWithTimeout(10_000) {
            override fun onSuccess(args: Array<Any?>?) { // Callback may never be called
                val msgV = "[${Event.STREAM_CREATE}] Response: ${args.contentToString()}"
                XLog.v(this@SocketSignaling.getLog("sendStreamCreate[${socketId()}]", msgV))
                val ack = SocketAck.fromAck(args)
                when {
                    ack.status != Payload.OK -> {
                        val msg = "[${Event.STREAM_CREATE}] => ${ack.status}"
                        XLog.e(this@SocketSignaling.getLog("sendStreamCreate[${socketId()}]", "$msg Data: $data"))
                        eventListener.onError(Error.StreamCreateError(msg, IllegalStateException(msg)))
                    }

                    ack.streamId.isEmpty() -> {
                        val msg = "[${Event.STREAM_CREATE}] => StreamId is empty"
                        XLog.e(this@SocketSignaling.getLog("sendStreamCreate[${socketId()}]", "$msg Data: $data"))
                        eventListener.onError(Error.StreamCreateError(msg, IllegalStateException(msg)))
                    }

                    else -> {
                        onStreamCreated(currentSocket)
                        eventListener.onStreamCreated(ack.streamId)
                    }
                }
            }

            override fun onTimeout() {
                val msg = "[${Event.STREAM_CREATE}] => Timeout"
                XLog.w(this@SocketSignaling.getLog("sendStreamCreate[${socketId()}]", msg))
                eventListener.onError(Error.StreamCreateError(msg, IllegalStateException(msg)))
            }
        })
    }

    private fun onStreamCreated(currentSocket: Socket) {
        XLog.d(getLog("onStreamCreated[${socketId()}]"))

        currentSocket.on(Event.STREAM_JOIN) { args ->
            XLog.v(getLog("onStreamCreated[${socketId()}]", "[${Event.STREAM_JOIN}] Payload: ${args.contentToString()}"))
            val payload = SocketPayload.fromPayload(args)
            if (payload.clientId.isEmpty()) {
                val msg = "[${Event.STREAM_JOIN}] ClientId is empty"
                XLog.e(getLog("onStreamCreated", msg), IllegalArgumentException("onStreamCreated: $msg"))
                payload.sendErrorAck(Payload.ERROR_EMPTY_OR_BAD_DATA)
            } else if (passwordVerifier.isValid(payload.clientId, payload.passwordHash)) {
                payload.sendOkAck()
                eventListener.onClientJoin(payload.clientId, payload.iceServers)
            } else {
                XLog.w(getLog("onStreamCreated", "[${Event.STREAM_JOIN}] Wrong stream password"))
                payload.sendErrorAck(Payload.ERROR_WRONG_STREAM_PASSWORD)
            }
        }

        currentSocket.on(Event.CLIENT_ANSWER) { args ->
            XLog.v(getLog("onStreamCreated[${socketId()}]", "[${Event.CLIENT_ANSWER}]"))
            val payload = SocketPayload.fromPayload(args)
            if (payload.clientId.isEmpty() || payload.answer.isEmpty()) {
                val msg = "[${Event.CLIENT_ANSWER}] ClientId or Answer is empty"
                XLog.e(getLog("onStreamCreated", msg), IllegalArgumentException("onStreamCreated: $msg"))
                payload.sendErrorAck(Payload.ERROR_EMPTY_OR_BAD_DATA)
            } else {
                payload.sendOkAck()
                eventListener.onClientAnswer(payload.clientId, payload.answer)
            }
        }

        currentSocket.on(Event.CLIENT_CANDIDATE) { args ->
            XLog.v(getLog("onStreamCreated[${socketId()}]", "[${Event.CLIENT_CANDIDATE}]"))
            val payload = SocketPayload.fromPayload(args)
            if (payload.clientId.isEmpty() || payload.candidate == null) {
                val msg = "[${Event.CLIENT_CANDIDATE}] ClientId or Candidate is empty"
                XLog.e(getLog("onStreamCreated", msg), IllegalArgumentException("onStreamCreated: $msg"))
                payload.sendErrorAck(Payload.ERROR_EMPTY_OR_BAD_DATA)
            } else {
                payload.sendOkAck()
                eventListener.onClientCandidate(payload.clientId, payload.candidate!!)
            }
        }

        currentSocket.on(Event.STREAM_LEAVE) { args ->
            XLog.v(getLog("onStreamCreated[${socketId()}]", "[${Event.STREAM_LEAVE}] Payload: ${args.contentToString()}"))
            val payload = SocketPayload.fromPayload(args)
            if (payload.clientId.isEmpty()) {
                val msg = "[${Event.STREAM_LEAVE}] ClientId is empty"
                XLog.e(getLog("onStreamCreated", msg), IllegalArgumentException("onStreamCreated: $msg"))
                payload.sendErrorAck(Payload.ERROR_EMPTY_OR_BAD_DATA)
            } else {
                payload.sendOkAck()
                eventListener.onClientLeave(payload.clientId)
            }
        }
    }

    internal fun sendStreamRemove(currentStreamId: StreamId) {
        XLog.d(getLog("sendStreamRemove[${socketId()}]", "currentStreamId: $currentStreamId"))

        val currentSocket = socket ?: return
        currentSocket.connected() || return

        currentSocket.off(Event.STREAM_JOIN).off(Event.CLIENT_ANSWER).off(Event.CLIENT_CANDIDATE).off(Event.STREAM_LEAVE)

        currentSocket.emit(Event.STREAM_REMOVE, arrayOf(), object : AckWithTimeout(10_000) {
            override fun onSuccess(args: Array<Any?>?) { // Callback may never be called
                val msg = "[${Event.STREAM_REMOVE}] Response: ${args.contentToString()}"
                XLog.v(this@SocketSignaling.getLog("sendStreamRemove[${socketId()}]", msg))
                when (val status = SocketAck.fromAck(args).status) {
                    Payload.OK -> XLog.d(this@SocketSignaling.getLog("sendStreamRemove[${socketId()}]", "OK"))
                    else -> XLog.w(this@SocketSignaling.getLog("sendStreamRemove[${socketId()}]", "[${Event.STREAM_REMOVE}] => $status"))
                }
                eventListener.onStreamRemoved()
            }

            override fun onTimeout() {
                XLog.w(this@SocketSignaling.getLog("sendStreamRemove[${socketId()}]", "[${Event.STREAM_REMOVE}] => Timeout"))
                eventListener.onStreamRemoved()
            }
        })
    }

    internal fun sendStreamStart(clientId: ClientId? = null) {
        XLog.d(getLog("sendStreamStart[${socketId()}]", "ClientId: ${clientId ?: "ALL"}"))

        val currentSocket = socket ?: return
        currentSocket.connected() || return

        val data = JSONObject().put(Payload.CLIENT_ID, clientId?.value ?: "ALL")

        currentSocket.emit(Event.STREAM_START, arrayOf(data), object : AckWithTimeout(10_000) {
            override fun onSuccess(args: Array<Any?>?) { // Callback may never be called
                XLog.v(this@SocketSignaling.getLog("sendStreamStart[${socketId()}]", "Response: ${args.contentToString()}"))
                when (val status = SocketAck.fromAck(args).status) {
                    Payload.OK -> XLog.d(this@SocketSignaling.getLog("sendStreamStart[${socketId()}]", "OK"))

                    Payload.ERROR_NO_CLIENT_FOUND -> {
                        XLog.d(this@SocketSignaling.getLog("sendStreamStart[${socketId()}]", "Client: $clientId => $status"))
                        eventListener.onClientNotFound(clientId!!, "[${Event.STREAM_START}]")
                    }

                    else -> throw IllegalArgumentException("sendStreamStart => $status")
                }
            }

            override fun onTimeout() {
                val msg = "[${Event.STREAM_START}] => Timeout"
                XLog.w(this@SocketSignaling.getLog("sendStreamStart[${socketId()}]", msg))
                eventListener.onError(Error.StreamStartError(msg, IllegalStateException(msg)))
            }
        })
    }

    internal fun sendStreamStop() {
        XLog.d(getLog("sendStreamStop[${socketId()}]"))

        val currentSocket = socket ?: return
        currentSocket.connected() || return

        val latch = CountDownLatch(1)

        currentSocket.emit(Event.STREAM_STOP, arrayOf()) { args -> // Callback may never be called
            XLog.v(getLog("sendStreamStop[${socketId()}]", "Response: ${args.contentToString()}"))
            when (val status = SocketAck.fromAck(args).status) {
                Payload.OK -> XLog.d(getLog("sendStreamStop[${socketId()}]", "OK"))
                else -> XLog.e(getLog("sendStreamStop", status), IllegalArgumentException("sendStreamStop => $status"))
            }
            latch.countDown()
        }

        runCatching {
            val releasedBeforeTimeout = latch.await(500, TimeUnit.MILLISECONDS)
            if (releasedBeforeTimeout.not()) XLog.w(getLog("sendStreamStop[${socketId()}]", "Server response timeout"))
        }
    }

    internal fun sendHostOffer(clientId: ClientId, offer: Offer) {
        XLog.d(getLog("sendHostOffer[${socketId()}]", "Client: $clientId"))

        val currentSocket = socket ?: return
        currentSocket.connected() || return

        val data = JSONObject().put(Payload.CLIENT_ID, clientId.value).put(Payload.OFFER, offer.description)

        currentSocket.emit(Event.HOST_OFFER, arrayOf(data), object : AckWithTimeout(10_000) {
            override fun onSuccess(args: Array<Any?>?) { // Callback may never be called
                XLog.v(this@SocketSignaling.getLog("sendHostOffer[${socketId()}]", "Response: ${args.contentToString()}"))
                when (val status = SocketAck.fromAck(args).status) {
                    Payload.OK -> {
                        XLog.d(this@SocketSignaling.getLog("sendHostOffer[${socketId()}]", "Client: $clientId => OK"))
                        eventListener.onHostOfferConfirmed(clientId)
                    }

                    Payload.ERROR_NO_CLIENT_FOUND -> {
                        XLog.d(this@SocketSignaling.getLog("sendHostOffer[${socketId()}]", "Client: $clientId => $status"))
                        eventListener.onClientNotFound(clientId, "[${Event.HOST_OFFER}]")
                    }

                    else -> throw IllegalArgumentException("sendHostOffer => $status")
                }
            }

            override fun onTimeout() {
                XLog.w(this@SocketSignaling.getLog("sendHostOffer[${socketId()}]", "[${Event.HOST_OFFER}] => Timeout"))
                eventListener.onClientNotFound(clientId, "[${Event.HOST_OFFER}] => Timeout")
            }
        })
    }

    internal fun sendHostCandidates(clientId: ClientId, candidates: List<IceCandidate>) {
        XLog.d(getLog("sendHostCandidates[${socketId()}]", "Client: $clientId"))

        val currentSocket = socket ?: return
        currentSocket.connected() || return

        fun IceCandidate.toJsonObject(): JSONObject =
            JSONObject().put(Payload.CANDIDATE, sdp).put(Payload.SPD_INDEX, sdpMLineIndex).put(Payload.SPD_MID, sdpMid)

        val data = JSONObject()
            .put(Payload.CLIENT_ID, clientId.value)
            .put(Payload.CANDIDATES, JSONArray(candidates.map { it.toJsonObject() }.toTypedArray()))

        currentSocket.emit(Event.HOST_CANDIDATE, arrayOf(data), object : AckWithTimeout(10_000) {
            override fun onSuccess(args: Array<Any?>?) { // Callback may never be called
                XLog.v(this@SocketSignaling.getLog("sendHostCandidates[${socketId()}]", "Response: ${args.contentToString()}"))
                when (val status = SocketAck.fromAck(args).status) {
                    Payload.OK -> XLog.d(this@SocketSignaling.getLog("sendHostCandidates[${socketId()}]", "Client: $clientId => OK"))

                    Payload.ERROR_NO_CLIENT_FOUND -> {
                        XLog.d(this@SocketSignaling.getLog("sendHostCandidates[${socketId()}]", "Client: $clientId => $status"))
                        eventListener.onClientNotFound(clientId, "[${Event.HOST_CANDIDATE}]")
                    }

                    else -> throw IllegalArgumentException("sendHostCandidates => $status")
                }
            }

            override fun onTimeout() {
                XLog.w(this@SocketSignaling.getLog("sendHostCandidates[${socketId()}]", "[${Event.HOST_CANDIDATE}] => Timeout"))
                eventListener.onClientNotFound(clientId, "[${Event.HOST_CANDIDATE}] => Timeout")
            }
        })
    }

    internal fun sendRemoveClients(clientIds: List<ClientId>, reason: String) {
        XLog.d(getLog("sendRemoveClients[${socketId()}]", "Clients: ${clientIds.size}"))

        val currentSocket = socket ?: return
        currentSocket.connected() || return

        if (clientIds.isEmpty()) {
            XLog.d(getLog("sendRemoveClients[${socketId()}]", "Empty list. Ignoring"))
            return
        }

        val data = JSONObject()
            .put(Payload.CLIENT_ID, JSONArray(clientIds.map { it.value }))
            .put("reason", reason)

        currentSocket.emit(Event.REMOVE_CLIENT, arrayOf(data), object : AckWithTimeout(10_000) {
            override fun onSuccess(args: Array<Any?>?) { // Callback may never be called
                XLog.v(this@SocketSignaling.getLog("sendRemoveClients[${socketId()}]", "Response: ${args.contentToString()}"))
                when (val status = SocketAck.fromAck(args).status) {
                    Payload.OK -> XLog.d(this@SocketSignaling.getLog("sendRemoveClients[${socketId()}]", "OK"))
                    else -> throw IllegalArgumentException("sendRemoveClients => $status")
                }
            }

            override fun onTimeout() {
                XLog.w(this@SocketSignaling.getLog("sendRemoveClients[${socketId()}]", "[${Event.REMOVE_CLIENT}] => Timeout"))
            }
        })
    }

    internal fun destroy() {
        XLog.d(getLog("destroy[${socketId()}]"))

        socket?.off()
        socket?.close()
        socket = null
    }

    private class SocketAck private constructor(json: JSONObject?) {
        companion object {
            internal fun fromAck(response: Array<Any?>?): SocketAck = SocketAck(response?.firstOrNull() as? JSONObject)
        }

        val status: String = json?.optString(Payload.STATUS) ?: ""

        val streamId: StreamId = json?.optString(Payload.STREAM_ID)?.let { StreamId(it) } ?: StreamId.EMPTY
    }

    private class SocketPayload(private val json: JSONObject?, private val ack: Ack?) {
        companion object {
            internal fun fromPayload(payload: Array<Any?>?): SocketPayload =
                SocketPayload(payload?.firstOrNull() as? JSONObject, payload?.lastOrNull() as? Ack)
        }

        val clientId: ClientId by lazy(LazyThreadSafetyMode.NONE) {
            json?.optString(Payload.CLIENT_ID)?.let { ClientId(it) } ?: ClientId("")
        }

        val passwordHash: String by lazy(LazyThreadSafetyMode.NONE) {
            json?.optString(Payload.PASSWORD_HASH) ?: ""
        }

        val iceServers: List<IceServer> by lazy(LazyThreadSafetyMode.NONE) {
            json?.optJSONArray(Payload.ICE_SERVERS)?.let { iceServersArray ->
                (0 until iceServersArray.length()).mapNotNull { i ->
                    val iceServerJson = iceServersArray.optJSONObject(i) ?: return@mapNotNull null
                    val urls = iceServerJson.optString("urls").ifBlank { null } ?: return@mapNotNull null
                    val username = iceServerJson.optString("username").ifBlank { null }
                    val credential = iceServerJson.optString("credential").ifBlank { null }

                    IceServer.builder(urls).apply {
                        if (username != null && credential != null) {
                            setUsername(username)
                            setPassword(credential)
                        }
                    }.createIceServer()
                }
            } ?: emptyList()
        }

        val answer: Answer by lazy(LazyThreadSafetyMode.NONE) {
            json?.optString(Payload.ANSWER)?.let { Answer(it) } ?: Answer("")
        }

        val candidate: IceCandidate? by lazy(LazyThreadSafetyMode.NONE) {
            runCatching { json?.getJSONObject(Payload.CANDIDATE)?.toIceCandidate() }
                .onFailure { XLog.e(getLog("SocketPayload", "[${Event.CLIENT_CANDIDATE}] Json error: ${it.message}"), it) }
                .getOrDefault(null)
        }

        fun sendOkAck() = ack?.call(JSONObject().put(Payload.STATUS, Payload.OK))

        fun sendErrorAck(message: String) = ack?.call(JSONObject().put(Payload.STATUS, message))

        @Throws(JSONException::class)
        private fun JSONObject.toIceCandidate(): IceCandidate =
            IceCandidate(getString(Payload.SPD_MID), getInt(Payload.SPD_INDEX), getString(Payload.CANDIDATE))
    }
}