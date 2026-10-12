package com.example.localbeam.data.remote

/**
 * Parses the HTTP-ish payload of an SSDP datagram.
 *
 * Payloads look like:
 * ```
 * HTTP/1.1 200 OK
 * CACHE-CONTROL: max-age=1800
 * EXT:
 * LOCATION: http://192.168.43.2:49152/description.xml
 * SERVER: Linux/3.14 UPnP/1.0 MediaRenderer/1.0
 * ST: urn:schemas-upnp-org:device:MediaRenderer:1
 * USN: uuid:4d696e69-444c-164e-9d41-d6cf8f9e0ff0::urn:schemas-upnp-org:device:MediaRenderer:1
 * ```
 *
 * Devices are inconsistent (LF instead of CRLF, mixed header case), so parsing is
 * deliberately lenient. Pure Kotlin, so this is covered by unit tests.
 */
object SsdpParser {

    /** `HTTP/1.1 200 OK` / `NOTIFY * HTTP/1.1` – we only care about 2xx replies. */
    private val STATUS_LINE = Regex("^HTTP/1\\.1\\s+(\\d{3})", RegexOption.IGNORE_CASE)

    data class Response(
        val location: String?,
        val server: String?,
        val searchTarget: String?,
        val uniqueServiceName: String?,
        val statusCode: Int?,
        val headers: Map<String, String>,
    ) {
        /**
         * Stable de-duplication key. The USN is `uuid:…::urn:…` and differs per search
         * target, so we keep only the `uuid:…` part – one entry per physical device.
         */
        val deviceKey: String? get() = uniqueServiceName?.substringBefore("::")?.trim()

        /** True when this reply describes a MediaRenderer (device *or* embedded service). */
        val isMediaRenderer: Boolean
            get() {
                val needle = "mediarenderer"
                return searchTarget?.contains(needle, ignoreCase = true) == true ||
                    uniqueServiceName?.contains(needle, ignoreCase = true) == true
            }
    }

    /**
     * Parses a datagram payload. Returns `null` for anything that is not a 2xx
     * SSDP search response (e.g. NOTIFY advertisements, truncated packets).
     */
    fun parse(text: String): Response? {
        if (text.isBlank()) return null

        val lines = text.split('\n')
        val statusLine = lines.firstOrNull()?.trim().orEmpty()
        val statusMatch = STATUS_LINE.find(statusLine)
        val statusCode = statusMatch?.groupValues?.get(1)?.toIntOrNull()
        if (statusCode == null || statusCode !in 200..299) return null

        val headers = linkedMapOf<String, String>()
        for (index in 1 until lines.size) {
            val line = lines[index].trim().trimEnd('\r')
            if (line.isEmpty()) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            // First value wins; duplicate headers in SSDP are noise.
            headers.putIfAbsent(name, value)
        }

        return Response(
            location = headers["location"]?.takeIf { it.isNotBlank() },
            server = headers["server"],
            searchTarget = headers["st"],
            uniqueServiceName = headers["usn"],
            statusCode = statusCode,
            headers = headers,
        )
    }

    /** Builds the M-SEARCH request body for a search target. */
    fun buildMSearch(searchTarget: String, mxSeconds: Int): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: 239.255.255.250:1900\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $mxSeconds\r\n")
        append("ST: $searchTarget\r\n")
        append("USER-AGENT: Android/UPnP/1.0 LocalBeam/1.0\r\n")
        append("\r\n")
    }
}
