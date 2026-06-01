package info.dvkr.screenstream.mjpeg.internal.mp4

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object Mp4BoxWriter {
    private const val MOVIE_TIMESCALE = 1000
    private const val VIDEO_TRACK_ID = 1
    private const val AUDIO_TRACK_ID = 2
    private const val VIDEO_TIMESCALE = 90_000

    internal fun initSegment(config: Mp4StreamConfig): ByteArray =
        concat(
            ftyp(),
            moov(config)
        )

    internal fun videoSegment(sequence: Int, packet: Mp4VideoPacket, timestampOffsetUs: Long = 0L): ByteArray {
        val baseTime = (packet.timestampUs - timestampOffsetUs).coerceAtLeast(0L).usToScale(VIDEO_TIMESCALE)
        val duration = packet.durationUs.usToScale(VIDEO_TIMESCALE).coerceAtLeast(1L)
        val flags = if (packet.isKeyFrame) SAMPLE_DEPENDS_ON_OTHERS.notSampleFlags() else SAMPLE_NON_SYNC_FLAGS
        return mediaSegment(
            sequence = sequence,
            trackId = VIDEO_TRACK_ID,
            baseMediaDecodeTime = baseTime,
            sampleDuration = duration,
            sampleData = packet.data,
            sampleFlags = flags
        )
    }

    internal fun audioSegment(sequence: Int, packet: Mp4AudioPacket, timestampOffsetUs: Long = 0L): ByteArray =
        mediaSegment(
            sequence = sequence,
            trackId = AUDIO_TRACK_ID,
            baseMediaDecodeTime = (packet.timestampUs - timestampOffsetUs).coerceAtLeast(0L).usToScale(AAC_SAMPLE_RATE_SCALE),
            sampleDuration = packet.durationSamples.toLong().coerceAtLeast(1L),
            sampleData = packet.data.stripAdtsHeader(),
            sampleFlags = SAMPLE_DEPENDS_ON_OTHERS.notSampleFlags()
        )

    private fun ftyp(): ByteArray =
        box(
            "ftyp",
            concat(
                ascii("iso6"),
                u32(1),
                ascii("iso6"),
                ascii("mp41"),
                ascii("avc1"),
                ascii("dash"),
                ascii("iso5")
            )
        )

    private fun moov(config: Mp4StreamConfig): ByteArray =
        box(
            "moov",
            buildList {
                add(mvhd(nextTrackId = if (config.audio != null) AUDIO_TRACK_ID + 1 else VIDEO_TRACK_ID + 1))
                config.video?.let { add(videoTrak(it)) }
                config.audio?.let { add(audioTrak(it)) }
                add(mvex(config))
            }.concat()
        )

    private fun mvhd(nextTrackId: Int): ByteArray =
        fullBox(
            "mvhd",
            version = 0,
            flags = 0,
            payload = concat(
                u32(0), // creation_time
                u32(0), // modification_time
                u32(MOVIE_TIMESCALE),
                u32(0), // duration unknown for live stream
                u32(0x00010000), // rate 1.0
                u16(0x0100), // volume 1.0
                u16(0),
                u32(0),
                u32(0),
                unityMatrix(),
                ByteArray(24),
                u32(nextTrackId)
            )
        )

    private fun videoTrak(config: Mp4VideoConfig): ByteArray =
        box(
            "trak",
            concat(
                tkhd(VIDEO_TRACK_ID, volume = 0, width = config.width, height = config.height),
                mdia(
                    timescale = VIDEO_TIMESCALE,
                    handler = "vide",
                    mediaHeader = vmhd(),
                    sampleDescription = avc1(config)
                )
            )
        )

    private fun audioTrak(config: Mp4AudioConfig): ByteArray =
        box(
            "trak",
            concat(
                tkhd(AUDIO_TRACK_ID, volume = 0x0100, width = 0, height = 0),
                mdia(
                    timescale = config.sampleRate,
                    handler = "soun",
                    mediaHeader = smhd(),
                    sampleDescription = mp4a(config)
                )
            )
        )

    private fun tkhd(trackId: Int, volume: Int, width: Int, height: Int): ByteArray =
        fullBox(
            "tkhd",
            version = 0,
            flags = 0x000007,
            payload = concat(
                u32(0), // creation_time
                u32(0), // modification_time
                u32(trackId),
                u32(0),
                u32(0), // duration unknown for live stream
                u32(0),
                u32(0),
                u16(0), // layer
                u16(0), // alternate_group
                u16(volume),
                u16(0),
                unityMatrix(),
                u32(width shl 16),
                u32(height shl 16)
            )
        )

    private fun mdia(timescale: Int, handler: String, mediaHeader: ByteArray, sampleDescription: ByteArray): ByteArray =
        box(
            "mdia",
            concat(
                mdhd(timescale),
                hdlr(handler),
                minf(mediaHeader, sampleDescription)
            )
        )

    private fun mdhd(timescale: Int): ByteArray =
        fullBox(
            "mdhd",
            version = 0,
            flags = 0,
            payload = concat(
                u32(0),
                u32(0),
                u32(timescale),
                u32(0),
                u16(0x55C4), // und
                u16(0)
            )
        )

    private fun hdlr(handler: String): ByteArray {
        val name = if (handler == "vide") "VideoHandler" else "SoundHandler"
        return fullBox(
            "hdlr",
            version = 0,
            flags = 0,
            payload = concat(
                u32(0),
                ascii(handler),
                u32(0),
                u32(0),
                u32(0),
                ascii(name),
                byteArrayOf(0)
            )
        )
    }

    private fun vmhd(): ByteArray =
        fullBox(
            "vmhd",
            version = 0,
            flags = 0x000001,
            payload = concat(u16(0), u16(0), u16(0), u16(0))
        )

    private fun smhd(): ByteArray =
        fullBox("smhd", version = 0, flags = 0, payload = concat(u16(0), u16(0)))

    private fun minf(mediaHeader: ByteArray, sampleDescription: ByteArray): ByteArray =
        box(
            "minf",
            concat(
                mediaHeader,
                dinf(),
                stbl(sampleDescription)
            )
        )

    private fun dinf(): ByteArray =
        box(
            "dinf",
            fullBox(
                "dref",
                version = 0,
                flags = 0,
                payload = concat(
                    u32(1),
                    fullBox("url ", version = 0, flags = 0x000001, payload = ByteArray(0))
                )
            )
        )

    private fun stbl(sampleDescription: ByteArray): ByteArray =
        box(
            "stbl",
            concat(
                stsd(sampleDescription),
                fullBox("stts", 0, 0, u32(0)),
                fullBox("stsc", 0, 0, u32(0)),
                fullBox("stsz", 0, 0, concat(u32(0), u32(0))),
                fullBox("stco", 0, 0, u32(0))
            )
        )

    private fun stsd(sampleDescription: ByteArray): ByteArray =
        fullBox("stsd", version = 0, flags = 0, payload = concat(u32(1), sampleDescription))

    private fun avc1(config: Mp4VideoConfig): ByteArray =
        box(
            "avc1",
            concat(
                ByteArray(6),
                u16(1), // data_reference_index
                u16(0),
                u16(0),
                u32(0),
                u32(0),
                u32(0),
                u16(config.width),
                u16(config.height),
                u32(0x00480000),
                u32(0x00480000),
                u32(0),
                u16(1),
                compressorName("ScreenStream"),
                u16(0x0018),
                u16(0xFFFF),
                avcC(config)
            )
        )

    private fun avcC(config: Mp4VideoConfig): ByteArray {
        val sps = config.sps
        val pps = config.pps
        val profile = if (sps.size > 1) sps[1] else 0x42
        val compatibility = if (sps.size > 2) sps[2] else 0x00
        val level = if (sps.size > 3) sps[3] else 0x1F
        return box(
            "avcC",
            concat(
                byteArrayOf(1, profile, compatibility, level),
                byteArrayOf(0xFF.toByte()), // 4-byte NAL lengths
                byteArrayOf(0xE1.toByte()), // one SPS
                u16(sps.size),
                sps,
                byteArrayOf(1), // one PPS
                u16(pps.size),
                pps
            )
        )
    }

    private fun mp4a(config: Mp4AudioConfig): ByteArray =
        box(
            "mp4a",
            concat(
                ByteArray(6),
                u16(1), // data_reference_index
                u32(0),
                u32(0),
                u16(config.channelCount),
                u16(16),
                u16(0),
                u16(0),
                u32(config.sampleRate shl 16),
                esds(config)
            )
        )

    private fun esds(config: Mp4AudioConfig): ByteArray {
        val decoderSpecific = descriptor(0x05, config.audioSpecificConfig)
        val decoderConfig = descriptor(
            0x04,
            concat(
                byteArrayOf(0x40), // MPEG-4 Audio
                byteArrayOf(0x15), // AudioStream
                byteArrayOf(0, 0, 0), // bufferSizeDB
                u32(0),
                u32(0),
                decoderSpecific
            )
        )
        val slConfig = descriptor(0x06, byteArrayOf(0x02))
        val esDescriptor = descriptor(0x03, concat(u16(1), byteArrayOf(0), decoderConfig, slConfig))
        return fullBox("esds", version = 0, flags = 0, payload = esDescriptor)
    }

    private fun descriptor(tag: Int, payload: ByteArray): ByteArray {
        require(payload.size < 128) { "Descriptor payload too large: ${payload.size}" }
        return concat(byteArrayOf(tag.toByte(), payload.size.toByte()), payload)
    }

    private fun mvex(config: Mp4StreamConfig): ByteArray =
        box(
            "mvex",
            buildList {
                config.video?.let { add(trex(VIDEO_TRACK_ID)) }
                config.audio?.let { add(trex(AUDIO_TRACK_ID)) }
            }.concat()
        )

    private fun trex(trackId: Int): ByteArray =
        fullBox(
            "trex",
            version = 0,
            flags = 0,
            payload = concat(
                u32(trackId),
                u32(1),
                u32(0),
                u32(0),
                u32(0)
            )
        )

    private fun mediaSegment(
        sequence: Int,
        trackId: Int,
        baseMediaDecodeTime: Long,
        sampleDuration: Long,
        sampleData: ByteArray,
        sampleFlags: Int
    ): ByteArray {
        val trafWithPlaceholder = traf(trackId, baseMediaDecodeTime, sampleDuration, sampleData.size, sampleFlags, dataOffset = 0)
        val moofWithPlaceholder = moof(sequence, trafWithPlaceholder)
        val dataOffset = moofWithPlaceholder.size + 8
        val moof = moof(sequence, traf(trackId, baseMediaDecodeTime, sampleDuration, sampleData.size, sampleFlags, dataOffset))
        return concat(moof, box("mdat", sampleData))
    }

    private fun moof(sequence: Int, traf: ByteArray): ByteArray =
        box("moof", concat(mfhd(sequence), traf))

    private fun mfhd(sequence: Int): ByteArray =
        fullBox("mfhd", version = 0, flags = 0, payload = u32(sequence))

    private fun traf(
        trackId: Int,
        baseMediaDecodeTime: Long,
        sampleDuration: Long,
        sampleSize: Int,
        sampleFlags: Int,
        dataOffset: Int
    ): ByteArray =
        box(
            "traf",
            concat(
                tfhd(trackId),
                tfdt(baseMediaDecodeTime),
                trun(sampleDuration, sampleSize, sampleFlags, dataOffset)
            )
        )

    private fun tfhd(trackId: Int): ByteArray =
        fullBox("tfhd", version = 0, flags = 0x020000, payload = u32(trackId))

    private fun tfdt(baseMediaDecodeTime: Long): ByteArray =
        fullBox("tfdt", version = 1, flags = 0, payload = u64(baseMediaDecodeTime))

    private fun trun(sampleDuration: Long, sampleSize: Int, sampleFlags: Int, dataOffset: Int): ByteArray =
        fullBox(
            "trun",
            version = 0,
            flags = 0x000001 or 0x000100 or 0x000200 or 0x000400,
            payload = concat(
                u32(1),
                u32(dataOffset),
                u32(sampleDuration),
                u32(sampleSize),
                u32(sampleFlags)
            )
        )

    private fun compressorName(name: String): ByteArray {
        val bytes = name.encodeToByteArray().take(31).toByteArray()
        return ByteArray(32).also {
            it[0] = bytes.size.toByte()
            bytes.copyInto(it, destinationOffset = 1)
        }
    }

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray =
        box(
            type,
            concat(
                byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()),
                payload
            )
        )

    private fun box(type: String, payload: ByteArray): ByteArray {
        require(type.length == 4) { "MP4 box type must be 4 chars: $type" }
        return ByteBuffer.allocate(payload.size + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size + 8)
            .put(ascii(type))
            .put(payload)
            .array()
    }

    private fun unityMatrix(): ByteArray =
        concat(
            u32(0x00010000),
            u32(0),
            u32(0),
            u32(0),
            u32(0x00010000),
            u32(0),
            u32(0),
            u32(0),
            u32(0x40000000)
        )

    private fun ByteArray.stripAdtsHeader(): ByteArray {
        if (size < 7) return this
        val b0 = this[0].toInt() and 0xFF
        val b1 = this[1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return this
        val protectionAbsent = (b1 and 0x01) == 1
        val headerSize = if (protectionAbsent) 7 else 9
        return if (size > headerSize) copyOfRange(headerSize, size) else ByteArray(0)
    }

    private fun Long.usToScale(timescale: Int): Long = (this * timescale) / 1_000_000L

    private fun Int.notSampleFlags(): Int = this shl 24

    private fun u16(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()

    private fun u32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun u32(value: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()

    private fun u64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private fun concat(vararg arrays: ByteArray): ByteArray = arrays.asList().concat()

    private fun List<ByteArray>.concat(): ByteArray =
        ByteArrayOutputStream(sumOf { it.size }).also { output -> forEach { output.write(it) } }.toByteArray()

    private const val AAC_SAMPLE_RATE_SCALE = 48_000
    private const val SAMPLE_DEPENDS_ON_OTHERS = 2
    private const val SAMPLE_NON_SYNC_FLAGS = 0x01010000
}
