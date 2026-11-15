package info.dvkr.screenstream.rtmp.internal.rtmp.amf

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Marker for AMF0 `undefined` / `null` read from the wire. */
internal object Amf0Null

/** Minimal AMF0 writer: only the types needed for RTMP commands and onMetaData. */
internal class Amf0Writer {
    private val out = ByteArrayOutputStream(256)

    fun number(value: Number): Amf0Writer = apply {
        out.write(0x00)
        val bits = java.lang.Double.doubleToLongBits(value.toDouble())
        for (shift in 56 downTo 0 step 8) out.write(((bits ushr shift) and 0xFF).toInt())
    }

    fun boolean(value: Boolean): Amf0Writer = apply {
        out.write(0x01)
        out.write(if (value) 1 else 0)
    }

    fun string(value: String): Amf0Writer = apply {
        out.write(0x02)
        rawString(value)
    }

    fun nullValue(): Amf0Writer = apply { out.write(0x05) }

    fun obj(properties: Map<String, Any?>): Amf0Writer = apply {
        out.write(0x03)
        properties.forEach { (key, value) -> rawString(key); any(value) }
        out.write(0x00); out.write(0x00); out.write(0x09)
    }

    fun ecmaArray(properties: Map<String, Any?>): Amf0Writer = apply {
        out.write(0x08)
        val n = properties.size
        out.write(n ushr 24); out.write(n ushr 16); out.write(n ushr 8); out.write(n)
        properties.forEach { (key, value) -> rawString(key); any(value) }
        out.write(0x00); out.write(0x00); out.write(0x09)
    }

    fun strictArray(values: List<Any?>): Amf0Writer = apply {
        out.write(0x0A)
        val n = values.size
        out.write(n ushr 24); out.write(n ushr 16); out.write(n ushr 8); out.write(n)
        values.forEach { any(it) }
    }

    fun any(value: Any?): Amf0Writer = apply {
        when (value) {
            null -> nullValue()
            is Number -> number(value)
            is Boolean -> boolean(value)
            is String -> string(value)
            is List<*> -> strictArray(value)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                obj(value as Map<String, Any?>)
            }
            else -> throw IllegalArgumentException("Unsupported AMF0 type: ${value::class}")
        }
    }

    private fun rawString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "AMF0 string too long" }
        out.write(bytes.size ushr 8); out.write(bytes.size and 0xFF)
        out.write(bytes, 0, bytes.size)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Minimal AMF0 reader. Returns Double, Boolean, String, Map<String, Any?>, List<Any?> or [Amf0Null]. */
internal class Amf0Reader(private val data: ByteArray, private var pos: Int = 0) {

    fun hasMore(): Boolean = pos < data.size

    @Throws(IOException::class)
    fun readValue(): Any {
        val marker = readU8()
        return when (marker) {
            0x00 -> java.lang.Double.longBitsToDouble(readLong())
            0x01 -> readU8() != 0
            0x02 -> readUtf(readU16())
            0x03 -> readProperties()
            0x05, 0x06 -> Amf0Null
            0x08 -> { pos += 4; readProperties() }               // ECMA array: count is advisory
            0x0A -> {                                              // strict array
                val n = readInt()
                List(n) { readValue() }
            }
            0x0B -> { pos += 10; Amf0Null }                       // date: double + timezone
            0x0C -> readUtf(readInt())                             // long string
            else -> throw IOException("Unsupported AMF0 marker 0x${marker.toString(16)}")
        }
    }

    private fun readProperties(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            val keyLen = readU16()
            if (keyLen == 0 && pos < data.size && (data[pos].toInt() and 0xFF) == 0x09) { pos++; break }
            val key = readUtf(keyLen)
            map[key] = readValue()
        }
        return map
    }

    private fun readU8(): Int {
        if (pos >= data.size) throw IOException("AMF0: unexpected end of data")
        return data[pos++].toInt() and 0xFF
    }

    private fun readU16(): Int = (readU8() shl 8) or readU8()
    private fun readInt(): Int = (readU16() shl 16) or readU16()
    private fun readLong(): Long {
        val hi = readInt().toLong() and 0xFFFFFFFFL
        val lo = readInt().toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }

    private fun readUtf(len: Int): String {
        if (len < 0 || pos + len > data.size) throw IOException("AMF0: string out of bounds")
        return String(data, pos, len, Charsets.UTF_8).also { pos += len }
    }
}
