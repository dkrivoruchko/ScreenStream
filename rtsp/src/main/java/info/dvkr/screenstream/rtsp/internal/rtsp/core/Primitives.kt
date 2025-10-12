package info.dvkr.screenstream.rtsp.internal.rtsp.core

@JvmInline
internal value class CSeq(val value: Int) {
    override fun toString(): String = value.toString()
}

@JvmInline
internal value class SessionId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
internal value class TrackId(val value: Int) {
    override fun toString(): String = value.toString()
}

@JvmInline
internal value class SeqNumber(val value: Int) {
    override fun toString(): String = value.toString()
}

@JvmInline
internal value class RtpTimestamp(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
internal value class Ssrc(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
internal value class InterleavedCh(val value: Int) {
    override fun toString(): String = value.toString()
}

