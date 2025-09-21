package info.dvkr.screenstream.rtsp.internal.rtsp.server

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

internal object ByteArrayPool {

    private val buckets = intArrayOf(
        4 * 1024, 8 * 1024, 16 * 1024, 32 * 1024, 64 * 1024, 128 * 1024, 256 * 1024, 512 * 1024, 1024 * 1024, 2 * 1024 * 1024
    )
    private const val MAX_PER_BUCKET = 32
    private val pools: Map<Int, ConcurrentLinkedQueue<ByteArray>> = buckets.associateWith { ConcurrentLinkedQueue<ByteArray>() }

    private fun bucketSize(size: Int): Int = buckets.firstOrNull { it >= size } ?: size

    fun get(size: Int): ByteArray {
        val bsz = bucketSize(size)
        val q = pools[bsz]
        return q?.poll() ?: ByteArray(bsz)
    }

    fun recycle(array: ByteArray) {
        val bsz = bucketSize(array.size)
        val q = pools[bsz] ?: return
        if (q.size >= MAX_PER_BUCKET) return
        q.offer(array)
    }
}

internal class SharedBuffer(val bytes: ByteArray) {
    private val refs = AtomicInteger(0)

    fun addRef(n: Int) {
        if (n > 0) refs.addAndGet(n)
    }

    fun release() {
        if (refs.decrementAndGet() == 0) ByteArrayPool.recycle(bytes)
    }

    fun releaseAll() {
        while (refs.getAndSet(0) > 0) {
            ByteArrayPool.recycle(bytes)
        }
    }
}

internal data class VideoBlob(
    val buf: SharedBuffer,
    val length: Int,
    val timestampUs: Long,
    val isKeyFrame: Boolean
)

internal data class AudioBlob(
    val buf: SharedBuffer,
    val length: Int,
    val timestampUs: Long
)
