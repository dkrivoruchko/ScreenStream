package info.dvkr.screenstream.rtsp.internal.rtsp.core

internal class ResponseBuilder(private val statusCode: Int, private val statusText: String) {
    private val headers: LinkedHashMap<String, String> = LinkedHashMap()
    private var body: ByteArray? = null

    fun header(name: String, value: String): ResponseBuilder {
        headers[name] = value
        return this
    }

    fun bodyAscii(text: String): ResponseBuilder {
        body = text.toByteArray(Charsets.US_ASCII)
        return this
    }

    fun build(): String {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 ").append(statusCode).append(' ').append(statusText).append("\r\n")
        val bodyBytes = body
        if (bodyBytes != null) headers["Content-Length"] = bodyBytes.size.toString()
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        if (bodyBytes != null) sb.append(bodyBytes.toString(Charsets.US_ASCII))
        return sb.toString()
    }

    companion object {
        fun ok(): ResponseBuilder = ResponseBuilder(200, "OK")
        fun error(code: Int, text: String): ResponseBuilder = ResponseBuilder(code, text)
    }
}

