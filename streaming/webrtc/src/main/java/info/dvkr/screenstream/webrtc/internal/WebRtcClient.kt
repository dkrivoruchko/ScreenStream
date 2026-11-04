package info.dvkr.screenstream.webrtc.internal

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.ui.WebRtcState
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnection.IceGatheringState
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnection.PeerConnectionState
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnection.SignalingState
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

internal class WebRtcClient(
    internal val clientId: ClientId,
    private val joinAttemptId: AttemptId,
    iceServers: List<IceServer>,
    private val factory: PeerConnectionFactory,
    private val videoCodecs: List<RtpCapabilities.CodecCapability>,
    private val audioCodecs: List<RtpCapabilities.CodecCapability>,
    private val eventListener: EventListener
) {

    internal interface EventListener {
        fun onHostOffer(key: NegotiationKey, offer: Offer)
        fun onHostCandidates(key: NegotiationKey, candidates: List<IceCandidate>)
        fun onClientAddress(key: ClientSessionKey)
        fun onClientAnswerApplied(key: NegotiationKey)
        fun onPeerDisconnected(key: NegotiationKey, connectionStateEpoch: Long)
        fun onError(key: NegotiationKey, cause: Throwable)
    }

    internal val publicId: String = CRC32().apply { update(clientId.value.encodeToByteArray()) }.value.toHexString().takeLast(8).uppercase()

    private enum class State { CREATED, PENDING_OFFER, LOCAL_OFFER_SET }

    private val id: String = "${clientId.value}#$publicId"
    private val rtcConfig = RTCConfiguration(iceServers.ifEmpty { defaultIceServers }).apply {
        tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
    }
    private val pendingCandidatesLock = Any()
    private val pendingHostCandidates: MutableList<IceCandidate> = mutableListOf()
    private val queuedClientDataLock = Any()
    private val queuedCandidates: MutableList<IceCandidate> = mutableListOf()

    private val state: AtomicReference<State> = AtomicReference(State.CREATED)
    private val clientAddress: AtomicReference<String> = AtomicReference("-") //TODO

    @Volatile
    internal var negotiationAttemptId: AttemptId = AttemptId.random()
        private set

    @Volatile
    private var mediaStreamId: MediaStreamId? = null

    @Volatile
    private var peerConnection: PeerConnection? = null

    @Volatile
    private var videoSender: RtpSender? = null

    @Volatile
    private var remoteAnswerReceived: Boolean = false

    @Volatile
    private var remoteAnswerApplied: Boolean = false

    @Volatile
    private var keyFrameRequestedOnConnected: Boolean = false

    @Volatile
    private var lastPeerConnectionState: PeerConnectionState = PeerConnectionState.NEW

    @Volatile
    private var peerConnectionStateEpoch: Long = 0

    init {
        XLog.d(getLog("init", "Client: $id"))
    }

    private fun isCurrentPeerAttempt(peerConnection: PeerConnection, attemptId: AttemptId): Boolean =
        this.peerConnection === peerConnection && negotiationAttemptId == attemptId

    private fun sessionKey(): ClientSessionKey = ClientSessionKey(clientId, joinAttemptId)

    private fun negotiationKey(attemptId: AttemptId): NegotiationKey = NegotiationKey(sessionKey(), attemptId)

    // WebRTC-HT thread
    internal fun start(mediaStream: LocalMediaSteam) {
        XLog.d(getLog("start", "Client: $id, mediaStream: ${mediaStream.id}"))

        if (state.get() != State.CREATED) {
            val msg = "Wrong client $id state: $state, expecting: ${State.CREATED}"
            XLog.w(getLog("start", msg), IllegalStateException("start: $msg"))
            stop()
        }
        negotiationAttemptId = AttemptId.random()
        val startNegotiationAttemptId = negotiationAttemptId

        var observerPeerConnection: PeerConnection? = null
        val observer = WebRTCPeerConnectionObserver(
            clientId = clientId,
            onHostCandidate = { candidate ->
                observerPeerConnection?.let { onHostCandidate(candidate, startNegotiationAttemptId, it) }
            },
            onCandidatePairChanged = {
                observerPeerConnection?.let { onCandidatePairChanged(startNegotiationAttemptId, it) }
            },
            onPeerConnectionStateChanged = { peerConnectionState ->
                onPeerConnectionStateChanged(startNegotiationAttemptId, peerConnectionState)
            }
        )
        val currentPeerConnection = factory.createPeerConnection(rtcConfig, observer)!!
        observerPeerConnection = currentPeerConnection
        currentPeerConnection.apply {
            videoSender = addTrack(mediaStream.videoTrack)
            addTrack(mediaStream.audioTrack)
            transceivers.forEach {
                if (it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                    it.setCodecPreferences(videoCodecs)
                    it.sender.parameters = it.sender.parameters.apply {
                        //TODO A user settings can be introduced for this
                        //TODO Deprecated. Migrate to contentHint once available: https://developer.mozilla.org/en-US/docs/Web/API/MediaStreamTrack/contentHint
                        degradationPreference = RtpParameters.DegradationPreference.BALANCED
                    }
                }
                if (it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) it.setCodecPreferences(audioCodecs)
            }
            // TODO setBitrate(200_000, 2_000_000, 4_000_000)
        }
        peerConnection = currentPeerConnection

        mediaStreamId = mediaStream.id
        synchronized(pendingCandidatesLock) { pendingHostCandidates.clear() }
        synchronized(queuedClientDataLock) {
            queuedCandidates.clear()
            remoteAnswerReceived = false
            remoteAnswerApplied = false
            keyFrameRequestedOnConnected = false
        }
        state.set(State.PENDING_OFFER)

        XLog.d(getLog("start", "createOffer: Client: $id, mediaStream: ${mediaStream.id}"))
        currentPeerConnection.createOffer(
            object : SdpObserver {
                // Signaling thread
                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                    XLog.d(this@WebRtcClient.getLog("start", "createOffer.onSuccess: Client: $id"))
                    setHostOffer(
                        mediaStream.id,
                        SessionDescription(SessionDescription.Type.OFFER, sessionDescription.description),
                        currentPeerConnection,
                        startNegotiationAttemptId
                    )
                }

                // Signaling thread
                override fun onCreateFailure(s: String?) {
                    if (state.get() == State.CREATED || !isCurrentPeerAttempt(currentPeerConnection, startNegotiationAttemptId)) {
                        XLog.i(this@WebRtcClient.getLog("start", "createOffer.onFailure for stale client. Ignoring: $id"))
                        return
                    }
                    eventListener.onError(negotiationKey(startNegotiationAttemptId), IllegalStateException("Client: $id. createHostOffer.onFailure: $s"))
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(s: String?) = Unit
            },
            MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            }
        )
    }

    // WebRTC-HT thread
    internal fun stop() {
        XLog.d(getLog("stop", "Client: $clientId"))

        peerConnection?.dispose()
        peerConnection = null
        videoSender = null
        mediaStreamId = null
        synchronized(pendingCandidatesLock) { pendingHostCandidates.clear() }
        synchronized(queuedClientDataLock) {
            queuedCandidates.clear()
            remoteAnswerReceived = false
            remoteAnswerApplied = false
            keyFrameRequestedOnConnected = false
        }
        lastPeerConnectionState = PeerConnectionState.NEW
        peerConnectionStateEpoch = 0
        clientAddress.set("-")
        state.set(State.CREATED)
    }

    // WebRTC-HT thread
    internal fun toClient(): WebRtcState.Client = WebRtcState.Client(clientId.value, publicId, clientAddress.get())

    // WebRTC-HT thread
    internal fun isNegotiationUnanswered(attemptId: AttemptId): Boolean =
        attemptId == negotiationAttemptId && state.get() != State.CREATED && !remoteAnswerReceived

    // WebRTC-HT thread
    internal fun isProlongedDisconnected(attemptId: AttemptId, connectionStateEpoch: Long): Boolean =
        attemptId == negotiationAttemptId &&
                state.get() == State.LOCAL_OFFER_SET &&
                remoteAnswerApplied &&
                this.peerConnectionStateEpoch == connectionStateEpoch &&
                lastPeerConnectionState == PeerConnectionState.DISCONNECTED

    // Signaling thread
    private fun setHostOffer(
        mediaStreamId: MediaStreamId,
        sessionDescription: SessionDescription,
        offerPeerConnection: PeerConnection,
        offerAttemptId: AttemptId
    ) {
        if (offerAttemptId != negotiationAttemptId) {
            XLog.i(getLog("setHostOffer", "Ignoring stale offer attemptId=$offerAttemptId, active=$negotiationAttemptId"))
            return
        }

        if (state.get() != State.PENDING_OFFER) {
            val msg = "Wrong client $id state: $state, expecting: ${State.PENDING_OFFER}"
            XLog.i(getLog("setHostOffer", "$msg. Ignoring stale callback."))
            return
        }

        XLog.d(getLog("setHostOffer", "Client: $id, mediaStreamId: $mediaStreamId"))

        val currentPeerConnection = peerConnection
        if (currentPeerConnection == null) {
            XLog.i(getLog("setHostOffer", "peerConnection is null. Ignoring stale callback."))
            return
        }
        if (currentPeerConnection !== offerPeerConnection) {
            XLog.i(getLog("setHostOffer", "Stale peerConnection. Ignoring. Client: $id"))
            return
        }

        currentPeerConnection.setLocalDescription(getSdpObserver {
            // Signaling thread
            onSuccess {
                XLog.d(this@WebRtcClient.getLog("setHostOffer", "onSuccess. Client: $id"))

                val hostCandidates = synchronized(pendingCandidatesLock) {
                    if (!isCurrentPeerAttempt(currentPeerConnection, offerAttemptId)) {
                        XLog.i(this@WebRtcClient.getLog("setHostOffer.onSuccess", "Ignoring stale callback after lock. Client: $id"))
                        return@synchronized null
                    }
                    if (state.get() != State.PENDING_OFFER) {
                        val msg = "Wrong client $id state: $state, expecting: ${State.PENDING_OFFER}"
                        XLog.i(this@WebRtcClient.getLog("setHostOffer.onSuccess", "$msg. Ignoring stale callback."))
                        return@synchronized null
                    }

                    pendingHostCandidates.toList().also {
                        state.set(State.LOCAL_OFFER_SET)
                        pendingHostCandidates.clear()
                    }
                } ?: return@onSuccess

                val key = negotiationKey(offerAttemptId)
                eventListener.onHostOffer(key, Offer(sessionDescription.description))
                if (hostCandidates.isNotEmpty()) eventListener.onHostCandidates(key, hostCandidates)
            }
            onFailure {
                if (!isCurrentPeerAttempt(currentPeerConnection, offerAttemptId) || state.get() == State.CREATED || isBenignSdpStateError(it.message)) {
                    XLog.i(this@WebRtcClient.getLog("setHostOffer.onFailure", "Ignoring stale/benign failure for client $id: ${it.message}"))
                } else {
                    eventListener.onError(negotiationKey(offerAttemptId), IllegalStateException("Client: $id. setHostOffer.onFailure: ${it.message}"))
                }
            }
        }, sessionDescription)
    }

    // Signaling thread
    private fun onHostCandidate(candidate: IceCandidate, callbackAttemptId: AttemptId, callbackPeerConnection: PeerConnection) {
        if (callbackAttemptId != negotiationAttemptId) {
            XLog.i(getLog("onHostCandidate", "Ignoring stale callback attemptId=$callbackAttemptId, active=$negotiationAttemptId"))
            return
        }
        if (peerConnection !== callbackPeerConnection) {
            XLog.i(getLog("onHostCandidate", "Ignoring stale peerConnection callback. Client: $id"))
            return
        }

        val msg = "Client: $id, MediaStream: $mediaStreamId, State: $state"
        val hostCandidates = synchronized(pendingCandidatesLock) {
            if (callbackAttemptId != negotiationAttemptId || peerConnection !== callbackPeerConnection) {
                XLog.i(this@WebRtcClient.getLog("onHostCandidate", "$msg. Ignoring stale callback after lock"))
                return@synchronized null
            }
            when (state.get()) {
                State.CREATED -> {
                    XLog.i(this@WebRtcClient.getLog("onHostCandidate", "$msg. Ignoring stale candidate"))
                    null
                }

                State.PENDING_OFFER -> {
                    XLog.d(this@WebRtcClient.getLog("onHostCandidate", "$msg. Accumulating"))
                    pendingHostCandidates.add(candidate)
                    null
                }

                State.LOCAL_OFFER_SET -> {
                    XLog.d(this@WebRtcClient.getLog("onHostCandidate", msg))
                    pendingHostCandidates.apply { add(candidate) }.toList().also { pendingHostCandidates.clear() }
                }

            }
        }

        if (!hostCandidates.isNullOrEmpty()) eventListener.onHostCandidates(negotiationKey(callbackAttemptId), hostCandidates)
    }

    // WebRTC-HT thread
    internal fun setClientAnswer(mediaStreamId: MediaStreamId, attemptId: AttemptId, answer: Answer) {
        if (state.get() != State.LOCAL_OFFER_SET) {
            XLog.i(getLog("setClientAnswer", "Client $id in ${state.get()} state. Ignoring stale answer."))
            return
        }
        if (attemptId != negotiationAttemptId) {
            XLog.i(getLog("setClientAnswer", "Ignoring stale answer attemptId=$attemptId, active=$negotiationAttemptId"))
            return
        }

        XLog.d(getLog("setClientAnswer", "Client: $id, mediaStreamId: $mediaStreamId"))

        if (this.mediaStreamId != mediaStreamId) {
            val msg = "Requesting '$mediaStreamId' but current is '${this.mediaStreamId}'."
            XLog.w(
                getLog("setClientAnswer", "$msg Ignoring stale answer."),
                IllegalStateException("WebRtcClient.setClientAnswer.staleMediaStreamId")
            )
            return
        }

        val currentPeerConnection = peerConnection
        if (currentPeerConnection == null) {
            XLog.i(getLog("setClientAnswer", "peerConnection is null. Ignoring stale answer."))
            return
        }

        synchronized(queuedClientDataLock) {
            remoteAnswerReceived = true
        }

        currentPeerConnection.setRemoteDescription(getSdpObserver {
            // Signaling thread
            onSuccess {
                if (!isCurrentPeerAttempt(currentPeerConnection, attemptId)) {
                    XLog.i(this@WebRtcClient.getLog("setClientAnswer.onSuccess", "Ignoring stale callback. Client: $id"))
                    return@onSuccess
                }

                XLog.d(this@WebRtcClient.getLog("setClientAnswer.onSuccess", "Client: $id"))
                eventListener.onClientAnswerApplied(negotiationKey(attemptId))
            }
            onFailure {
                synchronized(queuedClientDataLock) { remoteAnswerReceived = false }
                if (peerConnection !== currentPeerConnection || state.get() == State.CREATED || isBenignSdpStateError(it.message)) {
                    XLog.i(this@WebRtcClient.getLog("setClientAnswer.onFailure", "Ignoring stale/benign failure for client $id: ${it.message}"))
                } else {
                    eventListener.onError(negotiationKey(attemptId), IllegalStateException("Client: $id. setClientAnswer.onFailure: ${it.message}"))
                }
            }
        }, answer.asSessionDescription())
    }

    // WebRTC-HT thread
    internal fun onClientAnswerApplied(mediaStreamId: MediaStreamId, attemptId: AttemptId) {
        if (state.get() != State.LOCAL_OFFER_SET) {
            XLog.i(getLog("onClientAnswerApplied", "Client $id in ${state.get()} state. Ignoring stale callback."))
            return
        }
        if (attemptId != negotiationAttemptId) {
            XLog.i(getLog("onClientAnswerApplied", "Ignoring stale answer attemptId=$attemptId, active=$negotiationAttemptId"))
            return
        }
        if (this.mediaStreamId != mediaStreamId) {
            XLog.i(getLog("onClientAnswerApplied", "Ignoring stale mediaStreamId=$mediaStreamId, active=${this.mediaStreamId}"))
            return
        }

        val clientCandidates = synchronized(queuedClientDataLock) {
            remoteAnswerApplied = true
            queuedCandidates.toList().also { queuedCandidates.clear() }
        }
        clientCandidates.forEach { setClientCandidate(mediaStreamId, it) }
        requestKeyFrameIfReady(attemptId)
    }

    private fun requestKeyFrameIfReady(attemptId: AttemptId) {
        if (state.get() != State.LOCAL_OFFER_SET || !remoteAnswerApplied || lastPeerConnectionState != PeerConnectionState.CONNECTED) return
        if (keyFrameRequestedOnConnected) return

        keyFrameRequestedOnConnected = true
        val sender = videoSender
        if (sender == null) {
            XLog.i(getLog("requestKeyFrame", "Video sender is null. Client: $id, attemptId=$attemptId"))
            return
        }

        val generated = runCatching { sender.generateKeyFrame() }.onFailure {
            XLog.w(getLog("requestKeyFrame", "Failed. Client: $id, attemptId=$attemptId"), it)
        }.getOrDefault(false)

        if (generated) XLog.d(getLog("requestKeyFrame", "Requested. Client: $id, attemptId=$attemptId"))
        else XLog.w(getLog("requestKeyFrame", "Rejected. Client: $id, attemptId=$attemptId"))
    }

    // WebRTC-HT thread
    internal fun setClientCandidate(mediaStreamId: MediaStreamId, candidate: IceCandidate) {
        if (state.get() != State.LOCAL_OFFER_SET) {
            XLog.i(getLog("setClientCandidate", "Client $id in ${state.get()} state. Ignoring stale candidate."))
            return
        }

        XLog.d(getLog("setClientCandidate", "Client: $id, mediaStreamId: $mediaStreamId"))

        if (this.mediaStreamId != mediaStreamId) {
            val msg = "Requesting '$mediaStreamId' but current is '${this.mediaStreamId}'."
            XLog.w(
                getLog("setClientCandidate", "$msg Ignoring stale candidate."),
                IllegalStateException("WebRtcClient.setClientCandidate.staleMediaStreamId")
            )
            return
        }

        val currentPeerConnection = peerConnection
        if (currentPeerConnection == null) {
            XLog.i(getLog("setClientCandidate", "peerConnection is null. Ignoring stale candidate."))
            return
        }

        val queued = synchronized(queuedClientDataLock) {
            if (!remoteAnswerApplied) {
                XLog.i(getLog("setClientCandidate", "Client $id remote answer not applied. Queueing candidate."))
                queuedCandidates.add(candidate)
                true
            } else {
                false
            }
        }
        if (queued) return

        currentPeerConnection.addIceCandidate(candidate)
    }

    // Signaling thread
    private fun onCandidatePairChanged(callbackAttemptId: AttemptId, callbackPeerConnection: PeerConnection) {
        if (!isCurrentPeerAttempt(callbackPeerConnection, callbackAttemptId)) {
            XLog.i(getLog("onCandidatePairChanged", "Ignoring stale callback attemptId=$callbackAttemptId, active=$negotiationAttemptId"))
            return
        }
        XLog.d(this@WebRtcClient.getLog("onCandidatePairChanged", "Client: $id, MediaStream: $mediaStreamId, State: $state"))

        callbackPeerConnection.getStats { report ->
            if (!isCurrentPeerAttempt(callbackPeerConnection, callbackAttemptId)) {
                XLog.i(getLog("onCandidatePairChanged", "Ignoring stale stats callback attemptId=$callbackAttemptId, active=$negotiationAttemptId"))
                return@getStats
            }
            val transport = report.statsMap.filter { it.value.type == "transport" }.values.firstOrNull() ?: return@getStats
            val selectedCandidatePairId = transport.members.get("selectedCandidatePairId") as String? ?: return@getStats

            val selectedCandidatePair = report.statsMap[selectedCandidatePairId] ?: return@getStats
            val localCandidateId = selectedCandidatePair.members["localCandidateId"] as String? ?: return@getStats
            val remoteCandidateId = selectedCandidatePair.members["remoteCandidateId"] as String? ?: return@getStats
            val localCandidate = report.statsMap[localCandidateId] ?: return@getStats
            val remoteCandidate = report.statsMap[remoteCandidateId] ?: return@getStats

            val localNetworkType = localCandidate.members["networkType"] as String? ?: ""
            val localCandidateType = (localCandidate.members["candidateType"] as String? ?: "").let { type ->
                when {
                    type.equals("host", ignoreCase = true) -> "HOST"
                    type.equals("srflx", ignoreCase = true) -> "STUN"
                    type.equals("prflx", ignoreCase = true) -> "STUN"
                    type.equals("relay", ignoreCase = true) -> "TURN"
                    else -> null
                }
            }
            val remoteIP = remoteCandidate.members["ip"] as String? ?: ""

            clientAddress.set("${localNetworkType.uppercase()}${localCandidateType?.let { " [$it]" }}\n${remoteIP.ifBlank { "-" }}")
            eventListener.onClientAddress(sessionKey())
        }
    }

    // Signaling thread
    private fun onPeerConnectionStateChanged(callbackAttemptId: AttemptId, peerConnectionState: PeerConnectionState) {
        if (callbackAttemptId != negotiationAttemptId) {
            XLog.i(getLog("onPeerConnectionStateChanged", "Ignoring stale callback attemptId=$callbackAttemptId, active=$negotiationAttemptId"))
            return
        }

        lastPeerConnectionState = peerConnectionState
        val connectionStateEpoch = ++peerConnectionStateEpoch
        when (peerConnectionState) {
            PeerConnectionState.CONNECTED -> {
                requestKeyFrameIfReady(callbackAttemptId)
            }

            PeerConnectionState.FAILED -> {
                val msg = "Client: $id, MediaStream: '$mediaStreamId', State: $state"
                XLog.d(this@WebRtcClient.getLog("onPeerFailed", msg))
                if (state.get() != State.LOCAL_OFFER_SET) {
                    XLog.i(this@WebRtcClient.getLog("onPeerFailed", "Client not active anymore. Ignoring. $msg"))
                    return
                }
                eventListener.onError(negotiationKey(callbackAttemptId), IllegalStateException("onPeerFailed: $msg"))
            }

            PeerConnectionState.DISCONNECTED -> {
                val msg = "Client: $id, MediaStream: '$mediaStreamId', State: $state"
                XLog.d(this@WebRtcClient.getLog("onPeerDisconnected", msg))
                if (state.get() != State.LOCAL_OFFER_SET || !remoteAnswerApplied) {
                    XLog.i(this@WebRtcClient.getLog("onPeerDisconnected", "Client not answered/active anymore. Ignoring. $msg"))
                    return
                }
                eventListener.onPeerDisconnected(negotiationKey(callbackAttemptId), connectionStateEpoch)
            }

            else -> Unit
        }
    }

    private fun isBenignSdpStateError(message: String?): Boolean {
        val msg = message?.lowercase() ?: return false
        return msg.contains("called in wrong state: stable") || msg.contains("called in wrong state: closed")
    }

    private inline fun getSdpObserver(crossinline callback: Result<Unit>.() -> Unit): SdpObserver = object : SdpObserver {
        override fun onSetSuccess() = callback(Result.success(Unit))
        override fun onSetFailure(s: String?) = callback(Result.failure(RuntimeException(s)))
        override fun onCreateSuccess(p0: SessionDescription?) = Unit
        override fun onCreateFailure(p0: String?) = Unit
    }

    private class WebRTCPeerConnectionObserver(
        private val clientId: ClientId,
        private val onHostCandidate: (IceCandidate) -> Unit,
        private val onCandidatePairChanged: () -> Unit,
        private val onPeerConnectionStateChanged: (PeerConnectionState) -> Unit
    ) : PeerConnection.Observer {

        override fun onSignalingChange(signalingState: SignalingState?) {
            XLog.v(getLog("onSignalingChange", "Client: $clientId => $signalingState"))
        }

        override fun onIceConnectionChange(iceConnectionState: IceConnectionState?) {
            XLog.v(getLog("onIceConnectionChange", "Client: $clientId => $iceConnectionState"))
            //stats
        }

        override fun onStandardizedIceConnectionChange(newState: IceConnectionState?) {
            XLog.v(getLog("onStandardizedIceConnectionChange", "Client: $clientId => $newState"))
        }

        override fun onConnectionChange(newState: PeerConnectionState) {
            XLog.v(getLog("onConnectionChange", "Client: $clientId => $newState"))
            onPeerConnectionStateChanged(newState)
        }

        override fun onIceConnectionReceivingChange(b: Boolean) {
            XLog.v(getLog("onIceConnectionReceivingChange", "Client: $clientId => $b"))
        }

        override fun onIceGatheringChange(iceGatheringState: IceGatheringState?) {
            XLog.v(getLog("onIceGatheringChange", "Client: $clientId => $iceGatheringState"))
        }

        override fun onIceCandidate(iceCandidate: IceCandidate) {
            XLog.v(getLog("onIceCandidate", "Client: $clientId"))
            onHostCandidate(iceCandidate)
        }

        override fun onIceCandidateError(event: IceCandidateErrorEvent?) {
            val details = event?.let { "url=${it.url}, code=${it.errorCode}, text=${it.errorText}" } ?: "-"
            XLog.w(getLog("onIceCandidateError", "Client: $clientId, $details"))
        }

        override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>?) {
            XLog.v(getLog("onIceCandidatesRemoved", "Client: $clientId, ${iceCandidates?.size}"))
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            XLog.v(getLog("onSelectedCandidatePairChanged", "Client: $clientId"))
            onCandidatePairChanged()
        }

        override fun onAddStream(mediaStream: MediaStream?) {
            XLog.v(getLog("onAddStream", "Client: $clientId => $mediaStream"))
        }

        override fun onRemoveStream(mediaStream: MediaStream?) {
            XLog.v(getLog("onRemoveStream", "Client: $clientId => $mediaStream"))
        }

        override fun onDataChannel(dataChannel: DataChannel?) {
            XLog.v(getLog("onDataChannel", "Client: $clientId => $dataChannel"))
        }

        override fun onRenegotiationNeeded() {
            XLog.v(getLog("onRenegotiationNeeded", "Client: $clientId"))
        }

        override fun onAddTrack(rtpReceiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            XLog.v(getLog("onAddTrack", "Client: $clientId => $rtpReceiver \n $mediaStreams"))
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            XLog.v(getLog("onTrack", "Client: $clientId => $transceiver"))
        }

        override fun onRemoveTrack(receiver: RtpReceiver?) {
            XLog.v(getLog("onRemoveTrack", "Client: $clientId => $receiver"))
        }
    }

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        javaClass != other?.javaClass -> false
        else -> clientId == (other as WebRtcClient).clientId
    }

    override fun hashCode(): Int = clientId.hashCode()

    private companion object {
        @JvmStatic
        private val defaultIceServers
            get() = sequenceOf(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302",
                "stun:stun2.l.google.com:19302",
                "stun:stun3.l.google.com:19302",
                "stun:stun4.l.google.com:19302",
            ).shuffled().take(2).map { IceServer.builder(it).createIceServer() }.toList()
    }
}
