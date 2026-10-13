package com.viswa.localbeam.data.remote

import java.net.URI

/**
 * Resolves the `controlURL` values found in a device description.
 *
 * Those are frequently relative (`/upnp/control/AVTransport1`, `upnp/control/…`)
 * and occasionally contain characters that make [URI] throw (spaces are the usual
 * offender), so resolution falls back to plain string surgery.
 */
object UrlResolver {

    /** Absolute `http(s)` URL for [reference], resolved against [base]. */
    fun resolve(base: String, reference: String?): String? {
        val raw = reference?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val escaped = raw.replace(" ", "%20").replace("\t", "%20")
        if (escaped.startsWith("http://", ignoreCase = true) ||
            escaped.startsWith("https://", ignoreCase = true)
        ) {
            return escaped
        }

        // RFC 3986 resolution handles "/abs", "rel" and "../rel" correctly.
        runCatching { URI(base).resolve(escaped).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?.let { return it }

        return concatWithBaseDirectory(base, escaped)
    }

    /**
     * Directory part of a URL, always ending in `/`.
     * `http://192.168.43.2:49152/description.xml` -> `http://192.168.43.2:49152/`
     */
    fun baseDirectory(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val pathStart = url.indexOf('/', schemeEnd + 3)
        // No path at all, e.g. "http://192.168.43.2:49152"
        if (pathStart < 0) return "$url/"
        val lastSlash = url.lastIndexOf('/')
        val cut = if (lastSlash < pathStart) pathStart + 1 else lastSlash + 1
        return url.substring(0, cut)
    }

    private fun concatWithBaseDirectory(base: String, reference: String): String? {
        val directory = baseDirectory(base)
        if (!directory.startsWith("http", ignoreCase = true)) return null
        return directory.trimEnd('/') + "/" + reference.trimStart('/')
    }
}
