package info.dvkr.screenstream.webrtc.internal

import info.dvkr.screenstream.webrtc.ui.WebRtcState
import io.screenstream.foundation.logging.Logger
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
    private val eventListener: EventListener,
    private val log: Logger
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
        log.d("WebRtcClient.init", "Client: $publicId")
    }

    private fun isCurrentPeerAttempt(peerConnection: PeerConnection, attemptId: AttemptId): Boolean =
        this.peerConnection === peerConnection && negotiationAttemptId == attemptId

    private fun sessionKey(): ClientSessionKey = ClientSessionKey(clientId, joinAttemptId)

    private fun negotiationKey(attemptId: AttemptId): NegotiationKey = NegotiationKey(sessionKey(), attemptId)

    // WebRTC-HT thread
    internal fun start(mediaStream: LocalMediaSteam) {
        log.d("WebRtcClient.start", "Client: $publicId")

        if (state.get() != State.CREATED) {
            val msg = "Wrong client $publicId state: $state, expecting: ${State.CREATED}"
            log.w("WebRtcClient.start", msg, IllegalStateException("start: $msg"))
            stop()
        }
        negotiationAttemptId = AttemptId.random()
        val startNegotiationAttemptId = negotiationAttemptId

        var observerPeerConnection: PeerConnection? = null
        val observer = WebRTCPeerConnectionObserver(
            log = log,
            attemptId = startNegotiationAttemptId,
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

        log.d("WebRtcClient.start", "createOffer: Client: $publicId")
        currentPeerConnection.createOffer(
            object : SdpObserver {
                // Signaling thread
                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                    log.d("WebRtcClient.start", "createOffer.onSuccess: Client: $publicId")
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
                        log.i("WebRtcClient.start", "createOffer.onFailure for stale client. Ignoring: $publicId")
                        return
                    }
                    eventListener.onError(negotiationKey(startNegotiationAttemptId), IllegalStateException("Client: $publicId. createHostOffer.onFailure"))
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
        log.d("WebRtcClient.stop", "Client: $publicId")

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
            log.i("WebRtcClient.setHostOffer", "Ignoring stale offer attemptId=$offerAttemptId, active=$negotiationAttemptId")
            return
        }

        if (state.get() != State.PENDING_OFFER) {
            val msg = "Wrong client $publicId state: $state, expecting: ${State.PENDING_OFFER}"
            log.i("WebRtcClient.setHostOffer", "$msg. Ignoring stale callback.")
            return
        }

        log.d("WebRtcClient.setHostOffer", "Client: $publicId")

        val currentPeerConnection = peerConnection
        if (currentPeerConnection == null) {
            log.i("WebRtcClient.setHostOffer", "peerConnection is null. Ignoring stale callback.")
            return
        }
        if (currentPeerConnection !== offerPeerConnection) {
            log.i("WebRtcClient.setHostOffer", "Stale peerConnection. Ignoring. Client: $publicId")
            return
        }

        currentPeerConnection.setLocalDescription(getSdpObserver {
            // Signaling thread
            onSuccess {
                log.d("WebRtcClient.setHostOffer", "onSuccess. Client: $publicId")

                val hostCandidates = synchronized(pendingCandidatesLock) {
                    if (!isCurrentPeerAttempt(currentPeerConnection, offerAttemptId)) {
                        log.i("WebRtcClient.setHostOffer.onSuccess", "Ignoring stale callback after lock. Client: $publicId")
                        return@synchronized null
                    }
                    if (state.get() != State.PENDING_OFFER) {
                        val msg = "Wrong client $publicId state: $state, expecting: ${State.PENDING_OFFER}"
                        log.i("WebRtcClient.setHostOffer.onSuccess", "$msg. Ignoring stale callback.")
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
                    log.i("WebRtcClient.setHostOffer.onFailure", "Ignoring stale/benign failure for client $publicId")
                } else {
                    eventListener.onError(negotiationKey(offerAttemptId), IllegalStateException("Client: $publicId. setHostOffer.onFailure"))
                }
            }
        }, sessionDescription)
    }

    // Signaling thread
    private fun onHostCandidate(candidate: IceCandidate, callbackAttemptId: AttemptId, callbackPeerConnection: PeerConnection) {
        if (callbackAttemptId != negotiationAttemptId) {
            log.i("WebRtcClient.onHostCandidate", "Ignoring stale callback attemptId=$callbackAttemptId, active=$negotiationAttemptId")
            return
        }
        if (peerConnection !== callbackPeerConnection) {
            log.i("WebRtcClient.onHostCandidate", "Ignoring stale peerConnection callback. Client: $publicId")
            return
        }

        val msg = "Client: $publicId, State: $state"
        val hostCandidates = synchronized(pendingCandidatesLock) {
            if (callbackAttemptId != negotiationAttemptId || peerConnection !== callbackPeerConnection) {
                log.i("WebRtcClient.onHostCandidate", "$msg. Ignoring stale callback after lock")
                return@synchronized null
            }
            when (state.get()) {
                State.CREATED -> {
                    log.i("WebRtcClient.onHostCandidate", "$msg. Ignoring stale candidate")
                    null
                }

                State.PENDING_OFFER -> {
                    log.d("WebRtcClient.onHostCandidate", "$msg. Accumulating")
                    pendingHostCandidates.add(candidate)
                    null
                }

                State.LOCAL_OFFER_SET -> {
                    log.d("WebRtcClient.onHostCandidate", msg)
                    pendingHostCandidates.apply { add(candidate) }.toList().also { pendingHostCandidates.clear() }
                }

            }
        }

        if (!hostCandidates.isNullOrEmpty()) eventListener.onHostCandidates(negotiationKey(callbackAttemptId), hostCandidates)
    }

    // WebRTC-HT thread
    internal fun setClientAnswer(mediaStreamId: MediaStreamId, attemptId: AttemptId, answer: Answer) {
        if (state.get() != State.LOCAL_OFFER_SET) {
            log.i("WebRtcClient.setClientAnswer", "Client $publicId in ${state.get()} state. Ignoring stale answer.")
            return
        }
        if (attemptId != negotiationAttemptId) {
            log.i("WebRtcClient.setClientAnswer", "Ignoring stale answer attemptId=$attemptId, active=$negotiationAttemptId")
            return
        }

        log.d("WebRtcClient.setClientAnswer", "Client: $publicId")

        if (this.mediaStreamId != mediaStreamId) {
            val msg = "Media stream does not match the active negotiation."
            log.w("WebRtcClient.setClientAnswer", "$msg Ignoring stale answer.", IllegalStateException("WebRtcClient.setClientAnswer.staleMediaStreamId"))
            return
        }

        val currentPeerConnection = peerConnection
        if (currentPeerConnection == null) {
            log.i("WebRtcClient.setClientAnswer", "peerConnection is null. Ignoring stale answer.")
            return
        }

        synchronized(queuedClientDataLock) {
            remoteAnswerReceived = true
        }

        currentPeerConnection.setRemoteDescription(getSdpObserver {
            // Signaling thread
            onSuccess {
                if (!isCurrentPeerAttempt(currentPeerConnection, attemptId)) {
                    log.i("WebRtcClient.setClientAnswer.onSuccess", "Ignoring stale callback. Client: $publicId")
                    return@onSuccess
                }

                log.d("WebRtcClient.setClientAnswer.onSuccess", "Client: $publicId")
                eventListener.onClientAnswerApplied(negotiationKey(attemptId))
            }
            onFailure {
                synchronized(queuedClientDataLock) { remoteAnswerReceived = false }
                if (peerConnection !== currentPeerConnection || state.get() == State.CREATED || isBenignSdpStateError(it.message)) {
                    log.i("WebRtcClient.setClientAnswer.onFailure", "Ignoring stale/benign failure for client $publicId")
                } else {
                    eventListener.onError(negotiationKey(attemptId), IllegalStateException("Client: $publicId. setClientAnswer.onFailure"))
                }
            }
        }, answer.asSessionDescription())
    }

    // WebRTC-HT thread
    internal fun onClientAnswerApplied(mediaStreamId: MediaStreamId, attemptId: AttemptId) {
        if (state.get() != State.LOCAL_OFFER_SET) {
            log.i("WebRtcClient.onClientAnswerApplied", "Client $publicId in ${state.get()} state. Ignoring stale callback.")
            return
        }
        if (attemptId != negotiationAttemptId) {
            log.i("WebRtcClient.onClientAnswerApplied", "Ignoring stale answer attemptId=$attemptId, active=$negotiationAttemptId")
            return
        }
        if (this.mediaStreamId != mediaStreamId) {
            log.i("WebRtcClient.onClientAnswerApplied", "Ignoring stale media stream: attemptId=$attemptId")
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
            log.i("WebRtcClient.requestKeyFrame", "Video sender is null. Client: $publicId, attemptId=$attemptId")
            return
        }

        val generated = runCatching { sender.generateKeyFrame() }.onFailure {
            log.w("WebRtcClient.requestKeyFrame", "Failed. Client: $publicId, attemptId=$attemptId", it)
        }.getOrDefault(false)

        if (generated) log.d("WebRtcClient.requestKeyFrame", "Requested. Client: $publicId, attemptId=$attemptId")
        else log.w("WebRtcClient.requestKeyFrame", "Rejected. Client: $publicId, attemptId=$attemptId")
    }

    // WebRTC-HT thread
    internal fun setClientCandidate(mediaStreamId: MediaStreamId, candidate: IceCandidate) {
        if (state.get() != State.LOCAL_OFFER_SET) {
            log.i("WebRtcClient.setClientCandidate", "Client $publicId in ${state.get()} state. Ignoring stale candidate.")
            return
        }

        log.d("WebRtcClient.setClientCandidate", "Client: $publicId")

        if (this.mediaStreamId != mediaStreamId) {
            val msg = "Media stream does not match the active negotiation."
            log.w(
                "WebRtcClient.setClientCandidate",
                "$msg Ignoring stale candidate.",
                IllegalStateException("WebRtcClient.setClientCandidate.staleMediaStreamId")
            )
            return
        }

        val currentPeerConnection = peerConnection
        if (currentPeerConnection == null) {
            log.i("WebRtcClient.setClientCandidate", "peerConnection is null. Ignoring stale candidate.")
            return
        }

        val queued = synchronized(queuedClientDataLock) {
            if (!remoteAnswerApplied) {
                log.i("WebRtcClient.setClientCandidate", "Client $publicId remote answer not applied. Queueing candidate.")
                if (queuedCandidates.size >= MAX_QUEUED_CANDIDATES) queuedCandidates.removeAt(0)
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
            log.i("WebRtcClient.onCandidatePairChanged", "Ignoring stale callback attemptId=$callbackAttemptId, active=$negotiationAttemptId")
            return
        }
        log.d("WebRtcClient.onCandidatePairChanged", "Client: $publicId, State: $state")

        callbackPeerConnection.getStats { report ->
            if (!isCurrentPeerAttempt(callbackPeerConnection, callbackAttemptId)) {
                log.i("WebRtcClient.onCandidatePairChanged", "Ignoring stale stats callback attemptId=$callbackAttemptId, active=$negotiationAttemptId")
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
            log.i("WebRtcClient.onPeerConnectionStateChanged", "Ignoring stale callback attemptId=$callbackAttemptId, active=$negotiationAttemptId")
            return
        }

        lastPeerConnectionState = peerConnectionState
        val connectionStateEpoch = ++peerConnectionStateEpoch
        when (peerConnectionState) {
            PeerConnectionState.CONNECTED -> {
                requestKeyFrameIfReady(callbackAttemptId)
            }

            PeerConnectionState.FAILED -> {
                val msg = "Client: $publicId, State: $state"
                log.d("WebRtcClient.onPeerFailed", msg)
                if (state.get() != State.LOCAL_OFFER_SET) {
                    log.i("WebRtcClient.onPeerFailed", "Client not active anymore. Ignoring. $msg")
                    return
                }
                eventListener.onError(negotiationKey(callbackAttemptId), IllegalStateException("onPeerFailed: $msg"))
            }

            PeerConnectionState.DISCONNECTED -> {
                val msg = "Client: $publicId, State: $state"
                log.d("WebRtcClient.onPeerDisconnected", msg)
                if (state.get() != State.LOCAL_OFFER_SET || !remoteAnswerApplied) {
                    log.i("WebRtcClient.onPeerDisconnected", "Client not answered/active anymore. Ignoring. $msg")
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
        private val log: Logger,
        private val attemptId: AttemptId,
        private val onHostCandidate: (IceCandidate) -> Unit,
        private val onCandidatePairChanged: () -> Unit,
        private val onPeerConnectionStateChanged: (PeerConnectionState) -> Unit
    ) : PeerConnection.Observer {

        override fun onSignalingChange(signalingState: SignalingState?) {
            log.v("WebRtcClient.PeerConnection.onSignalingChange", "attemptId=$attemptId, $signalingState")
        }

        override fun onIceConnectionChange(iceConnectionState: IceConnectionState?) {
            log.v("WebRtcClient.PeerConnection.onIceConnectionChange", "attemptId=$attemptId, $iceConnectionState")
            //stats
        }

        override fun onStandardizedIceConnectionChange(newState: IceConnectionState?) {
            log.v("WebRtcClient.PeerConnection.onStandardizedIceConnectionChange", "attemptId=$attemptId, $newState")
        }

        override fun onConnectionChange(newState: PeerConnectionState) {
            log.v("WebRtcClient.PeerConnection.onConnectionChange", "attemptId=$attemptId, $newState")
            onPeerConnectionStateChanged(newState)
        }

        override fun onIceConnectionReceivingChange(b: Boolean) {
            log.v("WebRtcClient.PeerConnection.onIceConnectionReceivingChange", "attemptId=$attemptId, Receiving: $b")
        }

        override fun onIceGatheringChange(iceGatheringState: IceGatheringState?) {
            log.v("WebRtcClient.PeerConnection.onIceGatheringChange", "attemptId=$attemptId, $iceGatheringState")
        }

        override fun onIceCandidate(iceCandidate: IceCandidate) {
            log.v("WebRtcClient.PeerConnection.onIceCandidate", "attemptId=$attemptId")
            onHostCandidate(iceCandidate)
        }

        override fun onIceCandidateError(event: IceCandidateErrorEvent?) {
            log.w("WebRtcClient.PeerConnection.onIceCandidateError", "attemptId=$attemptId, code=${event?.errorCode}")
        }

        override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>?) {
            log.v("WebRtcClient.PeerConnection.onIceCandidatesRemoved", "attemptId=$attemptId, Removed candidates: ${iceCandidates?.size}")
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            log.v("WebRtcClient.PeerConnection.onSelectedCandidatePairChanged", "attemptId=$attemptId")
            onCandidatePairChanged()
        }

        override fun onAddStream(mediaStream: MediaStream?) {
            log.v("WebRtcClient.PeerConnection.onAddStream", "attemptId=$attemptId")
        }

        override fun onRemoveStream(mediaStream: MediaStream?) {
            log.v("WebRtcClient.PeerConnection.onRemoveStream", "attemptId=$attemptId")
        }

        override fun onDataChannel(dataChannel: DataChannel?) {
            log.v("WebRtcClient.PeerConnection.onDataChannel", "attemptId=$attemptId")
        }

        override fun onRenegotiationNeeded() {
            log.v("WebRtcClient.PeerConnection.onRenegotiationNeeded", "attemptId=$attemptId")
        }

        override fun onAddTrack(rtpReceiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            log.v("WebRtcClient.PeerConnection.onAddTrack", "attemptId=$attemptId, Streams: ${mediaStreams?.size}")
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            log.v("WebRtcClient.PeerConnection.onTrack", "attemptId=$attemptId")
        }

        override fun onRemoveTrack(receiver: RtpReceiver?) {
            log.v("WebRtcClient.PeerConnection.onRemoveTrack", "attemptId=$attemptId")
        }
    }

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        javaClass != other?.javaClass -> false
        else -> clientId == (other as WebRtcClient).clientId
    }

    override fun hashCode(): Int = clientId.hashCode()

    private companion object {
        private const val MAX_QUEUED_CANDIDATES = 128

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
