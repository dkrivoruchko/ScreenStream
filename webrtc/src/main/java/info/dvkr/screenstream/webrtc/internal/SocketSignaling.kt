package info.dvkr.screenstream.webrtc.internal

import android.os.Build
import android.os.SystemClock
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.internal.SocketSignaling.Payload.ERROR_TOKEN_VERIFICATION_FAILED
import io.socket.client.Ack
import io.socket.client.AckWithTimeout
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection.IceServer

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
        internal class StreamCreateError(override val message: String, override val cause: Throwable, retry: Boolean = false) : Error(retry, true)
        internal class StreamRemoveError(override val message: String, override val cause: Throwable, retry: Boolean = false) : Error(retry, true)
        internal class HostRelayError(override val message: String, override val cause: Throwable, retry: Boolean = false) : Error(retry, true)
    }

    internal interface EventListener {
        fun onSocketConnected()
        fun onTokenExpired()
        fun onSocketDisconnected(reason: String)
        fun onStreamCreated(streamId: StreamId)
        fun onStreamRemoved()
        fun onClientJoin(clientId: ClientId, joinAttemptId: AttemptId, iceServers: List<IceServer>)
        fun onClientLeave(clientId: ClientId, joinAttemptId: AttemptId)
        fun onClientNotFound(key: NegotiationKey, reason: String)
        fun onClientStartNotFound(key: ClientSessionKey, reason: String)
        fun onClientAnswer(clientId: ClientId, negotiationAttemptId: AttemptId, answer: Answer)
        fun onClientCandidate(clientId: ClientId, negotiationAttemptId: AttemptId, candidate: IceCandidate)
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
        const val PROTOCOL_VERSION = "protocolVersion"
        const val HOST_CREATE_ATTEMPT_ID = "hostCreateAttemptId"
        const val JOIN_ATTEMPT_ID = "joinAttemptId"
        const val NEGOTIATION_ATTEMPT_ID = "negotiationAttemptId"
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
        const val ERROR_TIMEOUT_OR_NO_RESPONSE = "ERROR:TIMEOUT_OR_NO_RESPONSE"
        const val ERROR_WRONG_STREAM_PASSWORD = "ERROR:WRONG_STREAM_PASSWORD"

        const val ERROR_TOKEN_VERIFICATION_FAILED = "ERROR:TOKEN_VERIFICATION_FAILED"
    }

    private fun isRetryableAckStatus(status: String): Boolean =
        status == Payload.ERROR_TIMEOUT_OR_NO_RESPONSE

    @Volatile
    private var socket: Socket? = null

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    internal inline fun socketId(): String? = socket?.id()

    private fun isStaleSocketCallback(currentSocket: Socket, tag: String, requireConnected: Boolean = true): Boolean {
        if (socket === currentSocket && (!requireConnected || currentSocket.connected())) return false

        XLog.i(
            getLog(
                tag,
                "Stale socket callback. Ignoring. requireConnected=$requireConnected, activeSocketId=${socketId()}, " +
                        "callbackSocketId=${currentSocket.id()}, callbackConnected=${currentSocket.connected()}"
            )
        )
        return true
    }

    init {
        XLog.d(getLog("init"))
    }

    private fun emitHostRelayWithAck(
        currentSocket: Socket,
        socketEvent: String,
        payload: JSONObject,
        tag: String,
        key: NegotiationKey
    ) {
        val startedAt = SystemClock.elapsedRealtime()

        currentSocket.emit(socketEvent, arrayOf(payload), object : AckWithTimeout(10_000) {
            override fun onSuccess(args: Array<Any?>?) {
                val logTag = "$tag[${socketId()}]"
                if (isStaleSocketCallback(currentSocket, logTag)) return

                val elapsed = SystemClock.elapsedRealtime() - startedAt
                XLog.v(this@SocketSignaling.getLog(logTag, "Response: ${args.contentToString()}"))
                when (val status = SocketAck.fromAck(args).status) {
                    Payload.OK -> XLog.d(
                        this@SocketSignaling.getLog(
                            logTag,
                            "Client: ${key.clientId} => OK, negotiationAttemptId=${key.attemptId}, elapsed_ms=$elapsed"
                        )
                    )

                    Payload.ERROR_NO_CLIENT_FOUND -> {
                        XLog.d(
                            this@SocketSignaling.getLog(
                                logTag,
                                "Client: ${key.clientId} => $status, negotiationAttemptId=${key.attemptId}, elapsed_ms=$elapsed"
                            )
                        )
                        eventListener.onClientNotFound(key, "[$socketEvent]")
                    }

                    Payload.ERROR_TIMEOUT_OR_NO_RESPONSE -> XLog.w(
                        this@SocketSignaling.getLog(
                            logTag,
                            "[$socketEvent] => Timeout, negotiationAttemptId=${key.attemptId}, elapsed_ms=$elapsed. Keeping client; ICE state owns recovery."
                        )
                    )

                    else -> {
                        val msg = "[$socketEvent] => $status"
                        val cause = IllegalStateException("$tag => $status")
                        XLog.e(
                            this@SocketSignaling.getLog(
                                logTag,
                                "Client: ${key.clientId} => unexpected ACK status: $status, negotiationAttemptId=${key.attemptId}, elapsed_ms=$elapsed"
                            ), cause
                        )
                        eventListener.onError(Error.HostRelayError(msg, cause, retry = isRetryableAckStatus(status)))
                    }
                }
            }

            override fun onTimeout() {
                val logTag = "$tag[${socketId()}]"
                if (isStaleSocketCallback(currentSocket, logTag)) return
                XLog.w(
                    this@SocketSignaling.getLog(
                        logTag,
                        "[$socketEvent] => Timeout, negotiationAttemptId=${key.attemptId}, elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}. Keeping client; ICE state owns recovery."
                    )
                )
            }
        })
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
            val currentSocket = this
            on(Socket.EVENT_CONNECT) {
                if (isStaleSocketCallback(currentSocket, "openSocket.${Socket.EVENT_CONNECT}[${currentSocket.id()}]", false)) return@on
                XLog.d(this@SocketSignaling.getLog(Socket.EVENT_CONNECT + "[${socketId()}]", id()))
                eventListener.onSocketConnected()
            }
            on(Socket.EVENT_DISCONNECT) { args -> // Auto reconnect
                if (isStaleSocketCallback(currentSocket, "openSocket.${Socket.EVENT_DISCONNECT}[${currentSocket.id()}]", false)) return@on
                XLog.d(this@SocketSignaling.getLog(Socket.EVENT_DISCONNECT, args.contentToString()))
                eventListener.onSocketDisconnected(args.contentToString())
            }
            on(Socket.EVENT_CONNECT_ERROR) { args -> // Auto or User reconnect
                if (isStaleSocketCallback(currentSocket, "openSocket.${Socket.EVENT_CONNECT_ERROR}[${currentSocket.id()}]", false)) return@on
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
                if (isStaleSocketCallback(currentSocket, "openSocket.${Event.SOCKET_ERROR}[${currentSocket.id()}]", false)) return@on
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

        val hostCreateAttemptId = AttemptId.random()
        val data = runCatching {
            JSONObject()
                .put("jwt", JWTHelper.createJWT(environment, streamId.value))
                .put(Payload.PROTOCOL_VERSION, WEBRTC_PROTOCOL_VERSION)
                .put(Payload.HOST_CREATE_ATTEMPT_ID, hostCreateAttemptId.value)
        }
            .onFailure { eventListener.onError(Error.StreamCreateError("createJWT error: ${it.message}", it)) }
            .getOrNull() ?: return

        val startedAt = SystemClock.elapsedRealtime()
        XLog.d(getLog("sendStreamCreate[${socketId()}]", "hostCreateAttemptId=$hostCreateAttemptId"))

        currentSocket.emit(Event.STREAM_CREATE, arrayOf(data), object : AckWithTimeout(10_000) {
            override fun onSuccess(args: Array<Any?>?) { // Callback may never be called
                val logTag = "sendStreamCreate[${socketId()}]"
                if (isStaleSocketCallback(currentSocket, logTag)) return

                val msgV = "[${Event.STREAM_CREATE}] Response: ${args.contentToString()}"
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                XLog.v(this@SocketSignaling.getLog(logTag, msgV))
                val ack = SocketAck.fromAck(args)
                when {
                    ack.status != Payload.OK -> {
                        val msg = "[${Event.STREAM_CREATE}] => ${ack.status}"
                        XLog.e(this@SocketSignaling.getLog(logTag, "$msg, hostCreateAttemptId=$hostCreateAttemptId, elapsed_ms=$elapsed"))
                        eventListener.onError(Error.StreamCreateError(msg, IllegalStateException(msg), retry = isRetryableAckStatus(ack.status)))
                    }

                    ack.streamId.isEmpty() -> {
                        val msg = "[${Event.STREAM_CREATE}] => StreamId is empty"
                        XLog.e(this@SocketSignaling.getLog(logTag, "$msg, hostCreateAttemptId=$hostCreateAttemptId, elapsed_ms=$elapsed"))
                        eventListener.onError(Error.StreamCreateError(msg, IllegalStateException(msg)))
                    }

                    else -> {
                        XLog.d(this@SocketSignaling.getLog(logTag, "OK, hostCreateAttemptId=$hostCreateAttemptId, elapsed_ms=$elapsed"))
                        onStreamCreated(currentSocket)
                        eventListener.onStreamCreated(ack.streamId)
                    }
                }
            }

            override fun onTimeout() {
                val logTag = "sendStreamCreate[${socketId()}]"
                if (isStaleSocketCallback(currentSocket, logTag)) return

                val msg =
                    "[${Event.STREAM_CREATE}] => Timeout, classification=create_ack_timeout, hostCreateAttemptId=$hostCreateAttemptId, elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
                XLog.w(this@SocketSignaling.getLog(logTag, msg))
                currentSocket.off()
                currentSocket.close()
                if (socket === currentSocket) socket = null
                eventListener.onSocketDisconnected(msg)
            }
        })
    }

    private fun onStreamCreated(currentSocket: Socket) {
        XLog.d(getLog("onStreamCreated[${socketId()}]"))

        currentSocket.on(Event.STREAM_JOIN) { args ->
            if (isStaleSocketCallback(currentSocket, "onStreamCreated.${Event.STREAM_JOIN}[${currentSocket.id()}]")) {
                SocketPayload.fromPayload(args).sendOkAck()
                return@on
            }
            XLog.v(getLog("onStreamCreated[${socketId()}]", "[${Event.STREAM_JOIN}]"))
            val payload = SocketPayload.fromPayload(args)
            if (payload.clientId.isEmpty() || payload.joinAttemptId.isEmpty()) {
                val msg = "[${Event.STREAM_JOIN}] ClientId or JoinAttemptId is empty"
                XLog.e(getLog("onStreamCreated", msg), IllegalArgumentException("onStreamCreated: $msg"))
                payload.sendErrorAck(Payload.ERROR_EMPTY_OR_BAD_DATA)
            } else if (passwordVerifier.isValid(payload.clientId, payload.passwordHash)) {
                payload.sendOkAck()
                XLog.d(getLog("onStreamCreated", "[${Event.STREAM_JOIN}] OK"))
                eventListener.onClientJoin(payload.clientId, payload.joinAttemptId, payload.iceServers)
            } else {
                XLog.w(getLog("onStreamCreated", "[${Event.STREAM_JOIN}] Wrong stream password"))
                payload.sendErrorAck(Payload.ERROR_WRONG_STREAM_PASSWORD)
            }
        }

        currentSocket.on(Event.CLIENT_ANSWER) { args ->
            if (isStaleSocketCallback(currentSocket, "onStreamCreated.${Event.CLIENT_ANSWER}[${currentSocket.id()}]")) {
                SocketPayload.fromPayload(args).sendOkAck()
                return@on
            }
            XLog.v(getLog("onStreamCreated[${socketId()}]", "[${Event.CLIENT_ANSWER}]"))
            val payload = SocketPayload.fromPayload(args)
            if (payload.clientId.isEmpty() || payload.negotiationAttemptId.isEmpty() || payload.answer.isEmpty()) {
                val msg = "[${Event.CLIENT_ANSWER}] ClientId, NegotiationAttemptId or Answer is empty"
                XLog.e(getLog("onStreamCreated", msg), IllegalArgumentException("onStreamCreated: $msg"))
                payload.sendErrorAck(Payload.ERROR_EMPTY_OR_BAD_DATA)
            } else {
                payload.sendOkAck()
                eventListener.onClientAnswer(payload.clientId, payload.negotiationAttemptId, payload.answer)
            }
        }

        currentSocket.on(Event.CLIENT_CANDIDATE) { args ->
            if (isStaleSocketCallback(currentSocket, "onStreamCreated.${Event.CLIENT_CANDIDATE}[${currentSocket.id()}]")) {
                SocketPayload.fromPayload(args).sendOkAck()
                return@on
            }
            XLog.v(getLog("onStreamCreated[${socketId()}]", "[${Event.CLIENT_CANDIDATE}]"))
            val payload = SocketPayload.fromPayload(args)
            if (payload.clientId.isEmpty() || payload.negotiationAttemptId.isEmpty() || (payload.candidate == null && payload.candidateEnd.not())) {
                val msg = "[${Event.CLIENT_CANDIDATE}] ClientId, NegotiationAttemptId or Candidate is empty"
                XLog.e(getLog("onStreamCreated", msg), IllegalArgumentException("onStreamCreated: $msg"))
                payload.sendErrorAck(Payload.ERROR_EMPTY_OR_BAD_DATA)
            } else if (payload.candidateEnd) {
                payload.sendOkAck()
                XLog.d(getLog("onStreamCreated", "[${Event.CLIENT_CANDIDATE}] End-of-candidates ignored. Client: ${payload.clientId}"))
            } else {
                payload.sendOkAck()
                eventListener.onClientCandidate(payload.clientId, payload.negotiationAttemptId, payload.candidate!!)
            }
        }

        currentSocket.on(Event.STREAM_LEAVE) { args ->
            if (isStaleSocketCallback(currentSocket, "onStreamCreated.${Event.STREAM_LEAVE}[${currentSocket.id()}]")) {
                SocketPayload.fromPayload(args).sendOkAck()
                return@on
            }
            XLog.v(getLog("onStreamCreated[${socketId()}]", "[${Event.STREAM_LEAVE}]"))
            val payload = SocketPayload.fromPayload(args)
            if (payload.clientId.isEmpty() || payload.joinAttemptId.isEmpty()) {
                val msg = "[${Event.STREAM_LEAVE}] ClientId or JoinAttemptId is empty"
                XLog.e(getLog("onStreamCreated", msg), IllegalArgumentException("onStreamCreated: $msg"))
                payload.sendErrorAck(Payload.ERROR_EMPTY_OR_BAD_DATA)
            } else {
                payload.sendOkAck()
                eventListener.onClientLeave(payload.clientId, payload.joinAttemptId)
            }
        }
    }

    internal fun sendStreamRemove(currentStreamId: StreamId) {
        XLog.d(getLog("sendStreamRemove[${socketId()}]", "currentStreamId: $currentStreamId"))

        val currentSocket = socket ?: return
        currentSocket.connected() || return

        currentSocket.off(Event.STREAM_JOIN).off(Event.CLIENT_ANSWER).off(Event.CLIENT_CANDIDATE).off(Event.STREAM_LEAVE)

        val startedAt = SystemClock.elapsedRealtime()
        currentSocket.emit(Event.STREAM_REMOVE, arrayOf(), object : AckWithTimeout(10_000) {
            override fun onSuccess(args: Array<Any?>?) { // Callback may never be called
                if (isStaleSocketCallback(currentSocket, "sendStreamRemove[${socketId()}]")) return

                val msg = "[${Event.STREAM_REMOVE}] Response: ${args.contentToString()}"
                XLog.v(this@SocketSignaling.getLog("sendStreamRemove[${socketId()}]", msg))
                when (val status = SocketAck.fromAck(args).status) {
                    Payload.OK -> {
                        XLog.d(this@SocketSignaling.getLog("sendStreamRemove[${socketId()}]", "OK, elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"))
                        eventListener.onStreamRemoved()
                    }

                    else -> {
                        val errorMessage = "[${Event.STREAM_REMOVE}] => $status"
                        XLog.w(
                            this@SocketSignaling.getLog(
                                "sendStreamRemove[${socketId()}]",
                                "$errorMessage, elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
                            )
                        )
                        eventListener.onError(Error.StreamRemoveError(errorMessage, IllegalStateException(errorMessage), retry = isRetryableAckStatus(status)))
                    }
                }
            }

            override fun onTimeout() {
                if (isStaleSocketCallback(currentSocket, "sendStreamRemove[${socketId()}]")) return

                val errorMessage =
                    "[${Event.STREAM_REMOVE}] => Timeout, classification=remove_ack_timeout_or_lost_ack, elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
                XLog.w(this@SocketSignaling.getLog("sendStreamRemove[${socketId()}]", "$errorMessage. Reconnecting before recreate."))
                currentSocket.off()
                currentSocket.close()
                if (socket === currentSocket) socket = null
                eventListener.onSocketDisconnected(errorMessage)
            }
        })
    }

    internal fun sendStreamStart(clientKey: ClientSessionKey? = null) {
        XLog.d(getLog("sendStreamStart[${socketId()}]", "ClientId: ${clientKey?.clientId ?: "ALL"}"))

        val currentSocket = socket ?: return
        currentSocket.connected() || return

        val data = JSONObject().put(Payload.CLIENT_ID, clientKey?.clientId?.value ?: "ALL").apply {
            clientKey?.let { put(Payload.JOIN_ATTEMPT_ID, it.joinAttemptId.value) }
        }
        val startedAt = SystemClock.elapsedRealtime()

        currentSocket.emit(Event.STREAM_START, arrayOf(data), object : AckWithTimeout(10_000) {
            override fun onSuccess(args: Array<Any?>?) {
                val logTag = "sendStreamStart[${socketId()}]"
                if (isStaleSocketCallback(currentSocket, logTag)) return

                val elapsed = SystemClock.elapsedRealtime() - startedAt
                XLog.v(this@SocketSignaling.getLog(logTag, "Response: ${args.contentToString()}"))
                when (val status = SocketAck.fromAck(args).status) {
                    Payload.OK -> XLog.d(this@SocketSignaling.getLog(logTag, "OK, clientId=${clientKey?.clientId ?: "ALL"}, elapsed_ms=$elapsed"))

                    Payload.ERROR_NO_CLIENT_FOUND -> {
                        XLog.d(this@SocketSignaling.getLog(logTag, "Client: ${clientKey?.clientId} => $status, elapsed_ms=$elapsed"))
                        clientKey?.let { eventListener.onClientStartNotFound(it, "[${Event.STREAM_START}]") }
                    }

                    Payload.ERROR_TIMEOUT_OR_NO_RESPONSE -> XLog.w(
                        this@SocketSignaling.getLog(logTag, "[${Event.STREAM_START}] => Timeout, elapsed_ms=$elapsed")
                    )

                    else -> {
                        val msg = "[${Event.STREAM_START}] => $status"
                        val cause = IllegalStateException("sendStreamStart => $status")
                        XLog.e(this@SocketSignaling.getLog(logTag, "Unexpected ACK status: $status, elapsed_ms=$elapsed"), cause)
                        eventListener.onError(Error.HostRelayError(msg, cause, retry = isRetryableAckStatus(status)))
                    }
                }
            }

            override fun onTimeout() {
                val logTag = "sendStreamStart[${socketId()}]"
                if (isStaleSocketCallback(currentSocket, logTag)) return
                XLog.w(this@SocketSignaling.getLog(logTag, "[${Event.STREAM_START}] => Timeout, elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"))
            }
        })
    }

    internal fun sendStreamStop() {
        XLog.d(getLog("sendStreamStop[${socketId()}]"))

        val currentSocket = socket ?: return
        currentSocket.connected() || return

        currentSocket.emit(Event.STREAM_STOP, arrayOf(), object : AckWithTimeout(1_000) {
            override fun onSuccess(args: Array<Any?>?) {
                val logTag = "sendStreamStop[${socketId()}]"
                if (isStaleSocketCallback(currentSocket, logTag)) return

                XLog.v(this@SocketSignaling.getLog(logTag, "Response: ${args.contentToString()}"))
                when (val status = SocketAck.fromAck(args).status) {
                    Payload.OK -> XLog.d(this@SocketSignaling.getLog(logTag, "OK"))
                    else -> XLog.w(this@SocketSignaling.getLog(logTag, status), IllegalArgumentException("sendStreamStop => $status"))
                }
            }

            override fun onTimeout() {
                if (isStaleSocketCallback(currentSocket, "sendStreamStop[${socketId()}]")) return
                XLog.w(this@SocketSignaling.getLog("sendStreamStop[${socketId()}]", "Server response timeout"))
            }
        })
    }

    internal fun sendHostOffer(key: NegotiationKey, offer: Offer): Boolean {
        XLog.d(getLog("sendHostOffer[${socketId()}]", "Client: ${key.clientId}, negotiationAttemptId=${key.attemptId}"))

        val currentSocket = socket ?: return false
        if (currentSocket.connected().not()) return false

        val data = JSONObject()
            .put(Payload.CLIENT_ID, key.clientId.value)
            .put(Payload.JOIN_ATTEMPT_ID, key.session.joinAttemptId.value)
            .put(Payload.NEGOTIATION_ATTEMPT_ID, key.attemptId.value)
            .put(Payload.OFFER, offer.description)

        emitHostRelayWithAck(currentSocket, Event.HOST_OFFER, data, "sendHostOffer", key)
        return true
    }

    internal fun sendHostCandidates(key: NegotiationKey, candidates: List<IceCandidate>) {
        XLog.d(getLog("sendHostCandidates[${socketId()}]", "Client: ${key.clientId}, negotiationAttemptId=${key.attemptId}"))

        val currentSocket = socket ?: return
        currentSocket.connected() || return

        fun IceCandidate.toJsonObject(): JSONObject =
            JSONObject().put(Payload.CANDIDATE, sdp).put(Payload.SPD_INDEX, sdpMLineIndex).put(Payload.SPD_MID, sdpMid)

        val data = JSONObject()
            .put(Payload.CLIENT_ID, key.clientId.value)
            .put(Payload.JOIN_ATTEMPT_ID, key.session.joinAttemptId.value)
            .put(Payload.NEGOTIATION_ATTEMPT_ID, key.attemptId.value)
            .put(Payload.CANDIDATES, JSONArray(candidates.map { it.toJsonObject() }.toTypedArray()))

        emitHostRelayWithAck(currentSocket, Event.HOST_CANDIDATE, data, "sendHostCandidates", key)
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
                if (isStaleSocketCallback(currentSocket, "sendRemoveClients[${socketId()}]")) return

                XLog.v(this@SocketSignaling.getLog("sendRemoveClients[${socketId()}]", "Response: ${args.contentToString()}"))
                when (val status = SocketAck.fromAck(args).status) {
                    Payload.OK -> XLog.d(this@SocketSignaling.getLog("sendRemoveClients[${socketId()}]", "OK"))
                    else -> XLog.w(
                        this@SocketSignaling.getLog("sendRemoveClients[${socketId()}]", "Unexpected ACK status: $status"),
                        IllegalStateException("sendRemoveClients => $status")
                    )
                }
            }

            override fun onTimeout() {
                if (isStaleSocketCallback(currentSocket, "sendRemoveClients[${socketId()}]")) return

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

        val negotiationAttemptId: AttemptId by lazy(LazyThreadSafetyMode.NONE) {
            AttemptId.validOrEmpty(json?.opt(Payload.NEGOTIATION_ATTEMPT_ID) as? String)
        }

        val joinAttemptId: AttemptId by lazy(LazyThreadSafetyMode.NONE) {
            AttemptId.validOrEmpty(json?.opt(Payload.JOIN_ATTEMPT_ID) as? String)
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
            runCatching {
                if (candidateEnd) null
                else json?.getJSONObject(Payload.CANDIDATE)?.toIceCandidate()
            }
                .onFailure { XLog.e(getLog("SocketPayload", "[${Event.CLIENT_CANDIDATE}] Json error: ${it.message}"), it) }
                .getOrDefault(null)
        }

        val candidateEnd: Boolean by lazy(LazyThreadSafetyMode.NONE) {
            if (json?.has(Payload.CANDIDATE) != true) return@lazy false
            val candidateValue = json.opt(Payload.CANDIDATE)
            candidateValue == null ||
                    candidateValue == JSONObject.NULL ||
                    candidateValue == "" ||
                    (candidateValue is JSONObject && candidateValue.has(Payload.CANDIDATE) && candidateValue.optString(Payload.CANDIDATE).isBlank())
        }

        fun sendOkAck() = ack?.call(JSONObject().put(Payload.STATUS, Payload.OK))

        fun sendErrorAck(message: String) = ack?.call(JSONObject().put(Payload.STATUS, message))

        @Throws(JSONException::class)
        private fun JSONObject.toIceCandidate(): IceCandidate =
            IceCandidate(getString(Payload.SPD_MID), getInt(Payload.SPD_INDEX), getString(Payload.CANDIDATE))
    }
}
