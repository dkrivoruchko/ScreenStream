package io.screenstream.mjpeg.http

/** Independent wire fields: reject conflicting framing and repeated CORS origins; retain repeatable fields. */
internal class HttpResponseHeaders(private val fields: Map<String, List<String>>) {
    val keys: Set<String> get() = fields.keys

    /** Assertions using this accessor expect one unambiguous value. */
    operator fun get(name: String): String? = fields[name.lowercase()]?.distinct()?.single()

    fun getAll(name: String): List<String>? = fields[name.lowercase()]
}

internal fun readHttpResponseHeaders(readLine: () -> String): HttpResponseHeaders {
    val fields = linkedMapOf<String, MutableList<String>>()
    while (true) {
        val line = readLine()
        if (line.isEmpty()) break
        val separator = line.indexOf(':')
        check(separator > 0) { "Invalid HTTP header line" }
        val name = line.substring(0, separator).lowercase()
        val value = line.substring(separator + 1).trim()
        val values = fields.getOrPut(name) { mutableListOf() }
        if (name == "access-control-allow-origin") {
            check(values.isEmpty()) { "Duplicate HTTP header: $name" }
        } else if (name in CONSISTENT_VALUE_RESPONSE_FIELDS) {
            check(values.all { it == value }) { "Conflicting HTTP header: $name" }
        }
        values += value
    }
    check("transfer-encoding" !in fields || "content-length" !in fields) {
        "HTTP response has both Transfer-Encoding and Content-Length"
    }
    return HttpResponseHeaders(fields.mapValues { (_, values) -> values.toList() })
}

private val CONSISTENT_VALUE_RESPONSE_FIELDS = setOf(
    "content-length", "content-type", "content-range",
    "access-control-allow-credentials",
)
