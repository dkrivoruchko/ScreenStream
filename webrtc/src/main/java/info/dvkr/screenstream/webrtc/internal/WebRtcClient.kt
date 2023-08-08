package info.dvkr.screenstream.webrtc.internal

import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.WebRtcPublicClient
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
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

internal class WebRtcClient(
    internal val clientId: ClientId,
    private val factory: PeerConnectionFactory,
    private val videoCodecs: List<RtpCapabilities.CodecCapability>,
    private val audioCodecs: List<RtpCapabilities.CodecCapability>,
    private val eventListener: EventListener
) {

    interface EventListener {
        @AnyThread
        fun onHostOffer(clientId: ClientId, offer: Offer)

        @AnyThread
        fun onHostCandidate(clientId: ClientId, candidate: IceCandidate)

        @AnyThread
        fun onClientAddress(clientId: ClientId)

        @AnyThread
        fun onClientDisconnected(clientId: ClientId)

        @AnyThread
        fun onError(clientId: ClientId, cause: Throwable)
    }

    private companion object {
        private val iceServers = listOf(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
        )

        private val rtcConfig = RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            iceCandidatePoolSize = 8
        }

        private val regexIPv4 = "^(([0-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5])(\\.(?!\$)|\$)){4}\$".toRegex()
        private val regexIPv6Standard = "^(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\$".toRegex()
        private val regexIPv6Compressed = "^((?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)::((?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)\$".toRegex()
    }

    @Volatile
    private var peerConnection: PeerConnection? = null

    @Volatile
    private var currentMediaStreamId: MediaStreamId? = null

    @Volatile
    private var clientAddress: String = "-"

    init {
        XLog.d(getLog("init", "Client: $clientId"))
    }

    internal fun toPublic(): WebRtcPublicClient = WebRtcPublicClient(clientId.value.hashCode().toLong(), clientAddress)

    @Synchronized
    internal fun start(mediaStream: LocalMediaSteam): Boolean {
        XLog.d(getLog("start", "MediaStream: '${mediaStream.id}', Client: $clientId"))

        if (currentMediaStreamId == mediaStream.id) {
            XLog.d(getLog("start", "Already started with mediaStream: ${mediaStream.id}, Client: $clientId. Ignoring."))
            return false
        }

        val mediaStreamId = mediaStream.id

        val connectionObserver = WebRTCPeerConnectionObserver(clientId, object : WebRTCPeerConnectionObserverEventListener {
            @WorkerThread
            override fun onHostCandidate(candidate: IceCandidate) {
                XLog.d(this@WebRtcClient.getLog("onHostCandidate", "MediaStream: $mediaStreamId, Client: $clientId"))
                if (mediaStreamId == currentMediaStreamId) eventListener.onHostCandidate(clientId, candidate)
                else XLog.w(this@WebRtcClient.getLog("onHostCandidates", "Expecting '$mediaStreamId' but is '$currentMediaStreamId'. Ignoring"))
            }

            @WorkerThread
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
                XLog.d(this@WebRtcClient.getLog("onSelectedCandidatePairChanged", "MediaStream: $mediaStreamId, Client: $clientId"))
                if (mediaStreamId == currentMediaStreamId) {
                    clientAddress = event.runCatching { remote.sdp.split(' ', limit = 6).drop(4).first() }
                        .map { if (regexIPv4.matches(it) || regexIPv6Standard.matches(it) || regexIPv6Compressed.matches(it)) it else "-" }
                        .getOrElse { "-" }
                    eventListener.onClientAddress(clientId)
                } else
                    XLog.w(this@WebRtcClient.getLog("onSelectedCandidatePairChanged", "Expecting '$mediaStreamId' but is '$currentMediaStreamId'. Ignoring"))
            }

            @WorkerThread
            override fun onPeerDisconnected() {
                XLog.d(this@WebRtcClient.getLog("onPeersDisconnected", "MediaStream: $mediaStreamId, Client: $clientId"))
                if (mediaStreamId == currentMediaStreamId) eventListener.onClientDisconnected(clientId)
                else XLog.w(this@WebRtcClient.getLog("onPeersDisconnected", "Expecting '$mediaStreamId' but is '$currentMediaStreamId'. Ignoring"))
            }
        })

        peerConnection = factory.createPeerConnection(rtcConfig, connectionObserver)!!.apply {
            addTrack(mediaStream.videoTrack)
            addTrack(mediaStream.audioTrack)
            transceivers.forEach {
                if (it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) it.setCodecPreferences(videoCodecs)
                if (it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) it.setCodecPreferences(audioCodecs)
            }
            //setBitrate(200_000, 4_000_000, 8_000_000) doesn't work
        }

        XLog.d(getLog("start", "createHostOffer: MediaStream: $mediaStreamId, Client: $clientId"))

        peerConnection!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                XLog.d(this@WebRtcClient.getLog("createHostOffer", "onCreateSuccess. Client: $clientId "))
                setHostOffer(mediaStreamId, SessionDescription(SessionDescription.Type.OFFER, sessionDescription.description))
            }

            override fun onCreateFailure(s: String?) =
                eventListener.onError(clientId, IllegalStateException("Client: $clientId. createHostOffer.onCreateFailure: $s"))

            override fun onSetSuccess() =
                eventListener.onError(clientId, IllegalStateException("Unexpected: createHostOffer.onSetSuccess. Client:$clientId"))

            override fun onSetFailure(s: String?) =
                eventListener.onError(clientId, IllegalStateException("Unexpected: createHostOffer.onSetFailure. Client:$clientId"))
        },
            MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            }
        )

        currentMediaStreamId = mediaStream.id

        return true
    }

    @Synchronized
    internal fun stopIfMismatch(mediaStream: LocalMediaSteam?) {
        XLog.d(getLog("stopIfMismatch", "Client: $clientId, MediaStreamId: ${mediaStream?.id}"))

        if (currentMediaStreamId != mediaStream?.id) {
            XLog.w(getLog("stopIfMismatch", "Requesting '${mediaStream?.id}' but current is '$currentMediaStreamId'. Stopping"))
            stop()
        }
    }

    @Synchronized
    internal fun stop() {
        XLog.d(getLog("stop", "Client: $clientId"))

        peerConnection?.dispose()
        peerConnection = null
        currentMediaStreamId = null
    }

    @Synchronized
    internal fun setClientAnswer(mediaStreamId: MediaStreamId, answer: Answer) {
        XLog.d(getLog("setClientAnswer", "MediaStreamId: $mediaStreamId, Client: $clientId"))

        if (currentMediaStreamId != mediaStreamId) {
            XLog.w(getLog("setClientAnswer", "Requesting '$mediaStreamId' but current is '$currentMediaStreamId'. Ignoring"))
            return
        }

        peerConnection!!.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() =
                    XLog.d(this@WebRtcClient.getLog("setClientAnswer.setRemoteDescription", "onSetSuccess. Client: $clientId "))

                override fun onSetFailure(s: String?) =
                    eventListener.onError(clientId, IllegalStateException("Client: $clientId. setClientAnswer.onSetFailure: $s"))

                override fun onCreateSuccess(sessionDescription: SessionDescription?) =
                    eventListener.onError(clientId, IllegalStateException("Unexpected: setClientAnswer.onCreateSuccess. Client: $clientId"))

                override fun onCreateFailure(s: String?) =
                    eventListener.onError(clientId, IllegalStateException("Unexpected: setClientAnswer.onCreateFailure. Client: $clientId"))

            },
            answer.asSessionDescription()
        )
    }

    @Synchronized
    internal fun setClientCandidate(mediaStreamId: MediaStreamId, candidate: IceCandidate) {
        XLog.d(getLog("setClientCandidate", "MediaStreamId: $mediaStreamId, Client: $clientId"))

        if (currentMediaStreamId != mediaStreamId) {
            XLog.w(getLog("setClientCandidates", "Expecting '$mediaStreamId' but is '$currentMediaStreamId'. Ignoring"))
            return
        }

        peerConnection!!.addIceCandidate(candidate)
    }

    @WorkerThread
    private fun setHostOffer(mediaStreamId: MediaStreamId, sessionDescription: SessionDescription) {
        XLog.d(getLog("setHostOffer", "MediaStreamId: $mediaStreamId, Client: $clientId"))

        if (currentMediaStreamId != mediaStreamId) {
            XLog.w(getLog("setHostOffer", "Requesting '$mediaStreamId' but current is '$currentMediaStreamId'. Ignoring"))
            return
        }

        peerConnection!!.setLocalDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    XLog.d(this@WebRtcClient.getLog("setHostOffer", "onSetSuccess. Client: $clientId"))
                    if (currentMediaStreamId == mediaStreamId)
                        eventListener.onHostOffer(clientId, Offer(sessionDescription.description))
                    else
                        XLog.w(this@WebRtcClient.getLog("setHostOffer", "Requesting '$mediaStreamId' but current is '$currentMediaStreamId'. Ignoring"))
                }

                override fun onSetFailure(s: String?) =
                    eventListener.onError(clientId, IllegalStateException("Client: $clientId. setHostOffer.onSetFailure: $s"))

                override fun onCreateSuccess(sessionDescription: SessionDescription?) =
                    eventListener.onError(clientId, IllegalStateException("Unexpected: setHostOffer.onCreateSuccess. Client: $clientId"))

                override fun onCreateFailure(s: String?) =
                    eventListener.onError(clientId, IllegalStateException("Unexpected: setHostOffer.onCreateFailure. Client: $clientId"))
            },
            sessionDescription
        )
    }

    private interface WebRTCPeerConnectionObserverEventListener {
        @WorkerThread
        fun onHostCandidate(candidate: IceCandidate)

        @WorkerThread
        fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent)

        @WorkerThread
        fun onPeerDisconnected()
    }

    private class WebRTCPeerConnectionObserver(
        private val clientId: ClientId,
        private val listener: WebRTCPeerConnectionObserverEventListener,
    ) : PeerConnection.Observer {

        override fun onSignalingChange(signalingState: SignalingState?) {
            XLog.v(getLog("onSignalingChange", "Client: $clientId => $signalingState"))
        }

        override fun onIceConnectionChange(iceConnectionState: IceConnectionState?) {
            XLog.v(getLog("onIceConnectionChange", "Client: $clientId => $iceConnectionState"))
        }

        override fun onStandardizedIceConnectionChange(newState: IceConnectionState?) {
            super.onStandardizedIceConnectionChange(newState)
            XLog.v(getLog("onStandardizedIceConnectionChange", "Client: $clientId => $newState"))
        }

        override fun onConnectionChange(newState: PeerConnectionState) {
            super.onConnectionChange(newState)
            XLog.v(getLog("onConnectionChange", "Client: $clientId => $newState"))
            if (newState in listOf(PeerConnectionState.DISCONNECTED, PeerConnectionState.FAILED)) listener.onPeerDisconnected()
        }

        override fun onIceConnectionReceivingChange(b: Boolean) {
            XLog.v(getLog("onIceConnectionReceivingChange", "Client: $clientId => $b"))
        }

        override fun onIceGatheringChange(iceGatheringState: IceGatheringState?) {
            XLog.v(getLog("onIceGatheringChange", "Client: $clientId => $iceGatheringState"))
        }

        override fun onIceCandidate(iceCandidate: IceCandidate) {
            XLog.v(getLog("onIceCandidate", "Client: $clientId => ${iceCandidate.sdp}"))
            listener.onHostCandidate(iceCandidate)
        }

        override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>?) {
            XLog.v(getLog("onIceCandidatesRemoved", "Client: $clientId => $iceCandidates"))
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            super.onSelectedCandidatePairChanged(event)
            XLog.v(getLog("onSelectedCandidatePairChanged", "Client: $clientId => $event"))
            listener.onSelectedCandidatePairChanged(event)
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
            super.onTrack(transceiver)
            XLog.v(getLog("onTrack", "Client: $clientId => $transceiver"))
        }
    }

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        javaClass != other?.javaClass -> false
        else -> clientId == (other as WebRtcClient).clientId
    }

    override fun hashCode(): Int = clientId.hashCode()
}