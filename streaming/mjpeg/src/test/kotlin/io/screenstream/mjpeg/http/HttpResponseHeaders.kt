package io.screenstream.mjpeg.http

/** Independent wire fields: retain repeatable headers and reject conflicting singleton values. */
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
        if (name in SINGLETON_RESPONSE_FIELDS) {
            check(values.all { it == value }) { "Conflicting HTTP header: $name" }
        }
        values += value
    }
    return HttpResponseHeaders(fields.mapValues { (_, values) -> values.toList() })
}

private val SINGLETON_RESPONSE_FIELDS = setOf(
    "content-length", "content-type", "content-range",
    "access-control-allow-origin", "access-control-allow-credentials",
)
