package dev.dmkr.screencaptureengine

/**
 * Borrowed encoded image bytes delivered to normal consumers.
 *
 * This is not a raw frame and not a transport packet. The frame is valid only for the admitted synchronous callback invocation that received it. Consumers
 * that need bytes after the callback returns must copy them.
 */
public interface EncodedImageFrame {
    /** Encoded byte format declared by the encoder provider. */
    public val format: EncodedImageFormat

    /** Number of encoded bytes available from this borrowed frame. */
    public val byteCount: Int

    /** Monotonic sequence number assigned per successfully published encoded frame. */
    public val sequence: Long

    /** Publication timestamp from elapsed realtime, in nanoseconds. */
    public val timestampElapsedRealtimeNanos: Long

    /** Copies encoded bytes into [destination] and returns the number of bytes copied. */
    public fun copyTo(destination: ByteArray, destinationOffset: Int = 0): Int

    /** Returns a newly allocated copy of the encoded bytes. */
    public fun copyBytes(): ByteArray
}
