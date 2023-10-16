package info.dvkr.screenstream.webrtc.internal

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
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
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

internal class WebRtcClient(
    internal val clientId: ClientId,
    private val factory: PeerConnectionFactory,
    private val videoCodecs: List<RtpCapabilities.CodecCapability>,
    private val audioCodecs: List<RtpCapabilities.CodecCapability>,
    private val eventListener: EventListener
) {

    internal interface EventListener {
        fun onHostOffer(clientId: ClientId, offer: Offer)
        fun onHostCandidates(clientId: ClientId, candidates: List<IceCandidate>)
        fun onClientAddress(clientId: ClientId)
        fun onError(clientId: ClientId, cause: Throwable)
    }

    @OptIn(ExperimentalStdlibApi::class)
    internal val publicId: String = CRC32().apply { update(clientId.value.encodeToByteArray()) }.value.toHexString().takeLast(8).uppercase()

    private enum class State { CREATED, PENDING_OFFER, PENDING_OFFER_ACCEPT, OFFER_ACCEPTED }

    private val id: String = "${clientId.value}#$publicId"
    private val pendingIceCandidates: MutableList<IceCandidate> = Collections.synchronizedList(mutableListOf())

    private val state: AtomicReference<State> = AtomicReference(State.CREATED)
    private val clientAddress: AtomicReference<String> = AtomicReference("-")

    @Volatile
    private var mediaStreamId: MediaStreamId? = null

    @Volatile
    private var peerConnection: PeerConnection? = null

    init {
        XLog.d(getLog("init", "Client: $id"))
    }

    // WebRTC-HT thread
    internal fun start(mediaStream: LocalMediaSteam) {
        XLog.d(getLog("start", "Client: $id, mediaStream: ${mediaStream.id}"))

        if (state.get() != State.CREATED) {
            val msg = "Wrong client $id state: $state, expecting: ${State.CREATED}"
            XLog.w(getLog("start", msg), IllegalStateException("start: $msg"))
            stop()
        }

        val observer = WebRTCPeerConnectionObserver(clientId, ::onHostCandidate, ::onCandidatePairChanged, ::onPeerDisconnected)
        peerConnection = factory.createPeerConnection(rtcConfig, observer)!!.apply {
            addTrack(mediaStream.videoTrack)
            addTrack(mediaStream.audioTrack)
            transceivers.forEach {
                if (it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) it.setCodecPreferences(videoCodecs)
                if (it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) it.setCodecPreferences(audioCodecs)
            }
            //setBitrate(200_000, 4_000_000, 8_000_000) doesn't work
        }

        mediaStreamId = mediaStream.id
        pendingIceCandidates.clear()
        state.set(State.PENDING_OFFER)

        XLog.d(getLog("start", "createOffer: Client: $id, mediaStream: ${mediaStream.id}"))
        peerConnection!!.createOffer(object : SdpObserver {
            // Signaling thread
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                XLog.d(this@WebRtcClient.getLog("start", "createOffer.onSuccess: Client: $id"))
                setHostOffer(mediaStream.id, SessionDescription(SessionDescription.Type.OFFER, sessionDescription.description))
            }

            // Signaling thread
            override fun onCreateFailure(s: String?) =
                eventListener.onError(clientId, IllegalStateException("Client: $id. createHostOffer.onFailure: $s"))

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
        mediaStreamId = null
        pendingIceCandidates.clear()
        clientAddress.set("-")
        state.set(State.CREATED)
    }

    // WebRTC-HT thread
    internal fun toClient(): WebRtcState.Client = WebRtcState.Client(clientId.value, publicId, clientAddress.get())

    // Signaling thread
    private fun setHostOffer(mediaStreamId: MediaStreamId, sessionDescription: SessionDescription) {
        if (state.get() != State.PENDING_OFFER) {
            val msg = "Wrong client $id state: $state, expecting: ${State.PENDING_OFFER}"
            XLog.w(getLog("setHostOffer", msg), IllegalStateException("setHostOffer: $msg"))
            eventListener.onError(clientId, IllegalStateException("setHostOffer: $msg"))
            return
        }

        XLog.d(getLog("setHostOffer", "Client: $id, mediaStreamId: $mediaStreamId"))

        peerConnection!!.setLocalDescription(getSdpObserver {
            // Signaling thread
            onSuccess {
                XLog.d(this@WebRtcClient.getLog("setHostOffer", "onSuccess. Client: $id"))
                if (state.get() != State.PENDING_OFFER) {
                    val msg = "Wrong client $id state: $state, expecting: ${State.PENDING_OFFER}"
                    XLog.w(this@WebRtcClient.getLog("setHostOffer.onSuccess", msg), IllegalStateException("setHostOffer.onSuccess: $msg"))
                    eventListener.onError(clientId, IllegalStateException("setHostOffer.onSuccess: $msg"))
                } else {
                    state.set(State.PENDING_OFFER_ACCEPT)
                    eventListener.onHostOffer(clientId, Offer(sessionDescription.description))
                }
            }
            onFailure { eventListener.onError(clientId, IllegalStateException("Client: $id. setHostOffer.onFailure: ${it.message}")) }
        }, sessionDescription)
    }

    // Signaling thread
    private fun onHostCandidate(candidate: IceCandidate) {
        val msg = "Client: $id, MediaStream: $mediaStreamId, State: $state"
        when (state.get()) {
            State.CREATED, State.PENDING_OFFER -> {
                XLog.w(this@WebRtcClient.getLog("onHostCandidate", "$msg. Ignoring"), IllegalStateException("onHostCandidate: $msg"))
                eventListener.onError(clientId, IllegalStateException("Client: $id. onHostCandidate: $msg"))
            }

            State.PENDING_OFFER_ACCEPT -> {
                XLog.d(this@WebRtcClient.getLog("onHostCandidate", "$msg. Accumulating"))
                pendingIceCandidates.add(candidate)
            }

            State.OFFER_ACCEPTED -> {
                XLog.d(this@WebRtcClient.getLog("onHostCandidate", msg))
                val list = pendingIceCandidates.apply { add(candidate) }.toList()
                pendingIceCandidates.clear()
                eventListener.onHostCandidates(clientId, list)
            }

            else -> Unit // Nothing
        }
    }

    // WebRTC-HT thread
    internal fun onHostOfferConfirmed() {
        if (state.get() != State.PENDING_OFFER_ACCEPT) {
            val msg = "Wrong client $id state: $state, expecting: ${State.PENDING_OFFER_ACCEPT}"
            XLog.w(getLog("onHostOfferConfirmed", msg), IllegalStateException("onHostOfferConfirmed: $msg"))
            eventListener.onError(clientId, IllegalStateException("onHostOfferConfirmed: $msg"))
        } else {
            XLog.d(getLog("onHostOfferConfirmed", "Client: $id"))
            state.set(State.OFFER_ACCEPTED)
            val list = pendingIceCandidates.toList()
            pendingIceCandidates.clear()
            eventListener.onHostCandidates(clientId, list)
        }
    }

    // WebRTC-HT thread
    internal fun setClientAnswer(mediaStreamId: MediaStreamId, answer: Answer) {
        if (state.get() != State.OFFER_ACCEPTED) {
            val msg = "Wrong client $id state: $state, expecting: ${State.OFFER_ACCEPTED}"
            XLog.w(getLog("setClientAnswer", msg), IllegalStateException("setClientAnswer: $msg"))
            eventListener.onError(clientId, IllegalStateException("setClientAnswer: $msg"))
            return
        }

        XLog.d(getLog("setClientAnswer", "Client: $id, mediaStreamId: $mediaStreamId"))

        if (this.mediaStreamId != mediaStreamId) {
            val msg = "Requesting '$mediaStreamId' but current is '${this.mediaStreamId}'."
            XLog.w(getLog("setClientAnswer", msg), IllegalStateException("setClientAnswer: $msg"))
            eventListener.onError(clientId, IllegalStateException("setClientAnswer: $msg"))
            return
        }

        peerConnection!!.setRemoteDescription(getSdpObserver {
            // Signaling thread
            onSuccess { XLog.d(this@WebRtcClient.getLog("setClientAnswer.onSuccess", "Client: $id")) }
            onFailure { eventListener.onError(clientId, IllegalStateException("Client: $id. setClientAnswer.onFailure: ${it.message}")) }
        }, answer.asSessionDescription())
    }

    // WebRTC-HT thread
    internal fun setClientCandidate(mediaStreamId: MediaStreamId, candidate: IceCandidate) {
        if (state.get() != State.OFFER_ACCEPTED) {
            val msg = "Wrong client $id state: $state, expecting: ${State.OFFER_ACCEPTED}"
            XLog.w(getLog("setClientCandidate", msg), IllegalStateException("setClientCandidate: $msg"))
            eventListener.onError(clientId, IllegalStateException("setClientCandidate: $msg"))
            return
        }

        XLog.d(getLog("setClientCandidate", "Client: $id, mediaStreamId: $mediaStreamId"))

        if (this.mediaStreamId != mediaStreamId) {
            val msg = "Requesting '$mediaStreamId' but current is '${this.mediaStreamId}'."
            XLog.w(getLog("setClientCandidate", msg), IllegalStateException("setClientCandidate: $msg"))
            eventListener.onError(clientId, IllegalStateException("setClientCandidate: $msg"))
            return
        }

        peerConnection!!.addIceCandidate(candidate)
    }

    // Signaling thread
    private fun onCandidatePairChanged(event: CandidatePairChangeEvent) {
        val msg = "Client: $id, MediaStream: $mediaStreamId, State: $state"

        if (state.get() == State.OFFER_ACCEPTED) {
            XLog.d(this@WebRtcClient.getLog("onCandidatePairChanged", msg))
            clientAddress.set(event.runCatching { remote.sdp.split(' ', limit = 6).drop(4).first() }
                .map { if (regexIPv4.matches(it) || regexIPv6Standard.matches(it) || regexIPv6Compressed.matches(it)) it else "-" }
                .getOrElse { "-" })
            eventListener.onClientAddress(clientId)
        } else {
            XLog.d(this@WebRtcClient.getLog("onCandidatePairChanged", "Ignoring"), IllegalStateException("onCandidatePairChanged: $msg"))
        }
    }

    // Signaling thread
    private fun onPeerDisconnected() {
        val msg = "Client: $id, MediaStream: '$mediaStreamId', State: $state"
        XLog.d(this@WebRtcClient.getLog("onPeersDisconnected", msg))
        eventListener.onError(clientId, IllegalStateException("onPeerDisconnected: $msg"))
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
        private val onCandidatePairChanged: (CandidatePairChangeEvent) -> Unit,
        private val onPeerDisconnected: () -> Unit
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
            if (newState in listOf(PeerConnectionState.DISCONNECTED, PeerConnectionState.FAILED)) onPeerDisconnected()
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
            XLog.v(getLog("onIceCandidateError", "Client: $clientId"))
        }

        override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>?) {
            XLog.v(getLog("onIceCandidatesRemoved", "Client: $clientId, ${iceCandidates?.size}"))
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            XLog.v(getLog("onSelectedCandidatePairChanged", "Client: $clientId"))
            onCandidatePairChanged(event)
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
        private val iceServers = listOf(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
        )

        @JvmStatic
        private val rtcConfig = RTCConfiguration(iceServers.asSequence().shuffled().take(2).toList()).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }

        @JvmStatic
        private val regexIPv4 = "^(([0-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5])(\\.(?!\$)|\$)){4}\$".toRegex()

        @JvmStatic
        private val regexIPv6Standard = "^(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\$".toRegex()

        @JvmStatic
        private val regexIPv6Compressed = "^((?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)::((?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)\$".toRegex()
    }
}