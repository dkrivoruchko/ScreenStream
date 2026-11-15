package info.dvkr.screenstream.rtmp.internal.rtmp

import info.dvkr.screenstream.rtmp.internal.rtmp.amf.Amf0Reader

/** Decoded AMF0 command message: name, transaction id and the remaining arguments (index 0 = command object). */
internal class RtmpCommand(val name: String, val transactionId: Double, val args: List<Any>) {

    internal fun statusCode(argIndex: Int = 1): String? = (args.getOrNull(argIndex) as? Map<*, *>)?.get("code") as? String

    internal companion object {
        /** Returns null for non-command messages and for malformed payloads. */
        internal fun from(message: RtmpMessage): RtmpCommand? {
            if (message.typeId != RtmpConst.MSG_COMMAND_AMF0) return null
            return runCatching {
                val reader = Amf0Reader(message.payload)
                val name = reader.readValue() as String
                val transactionId = reader.readValue() as? Double ?: 0.0
                val args = mutableListOf<Any>()
                while (reader.hasMore()) args += reader.readValue()
                RtmpCommand(name, transactionId, args)
            }.getOrNull()
        }
    }
}
