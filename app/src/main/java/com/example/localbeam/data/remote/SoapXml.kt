package com.example.localbeam.data.remote

/**
 * Tiny, dependency-free XML helpers for SOAP traffic.
 *
 * Request bodies are built by hand (see [SoapEnvelope]) and responses only ever
 * need a handful of scalar values, so a full XML writer/parser would be overkill.
 * Everything here is pure Kotlin and unit tested.
 */
object SoapXml {

    /** Escapes text for use as XML element content and attribute values. */
    fun escape(value: String): String {
        if (value.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) {
            return value
        }
        val builder = StringBuilder(value.length + 16)
        for (character in value) {
            when (character) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&apos;")
                else -> builder.append(character)
            }
        }
        return builder.toString()
    }

    /**
     * Text content of the first `<tag>` element, tolerating a namespace prefix
     * (`<u:TransportState>`) and attributes. `null` when the tag is absent.
     */
    fun valueOf(xml: String, tag: String): String? {
        val pattern = Regex(
            "<(?:[A-Za-z0-9_.-]+:)?${Regex.escape(tag)}(?:\\s[^>]*)?>([\\s\\S]*?)</(?:[A-Za-z0-9_.-]+:)?${Regex.escape(tag)}\\s*>",
            RegexOption.IGNORE_CASE,
        )
        return pattern.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** True when the response is a SOAP fault instead of an action result. */
    fun isFault(xml: String): Boolean =
        xml.contains(":Fault", ignoreCase = true) || xml.contains("<Fault", ignoreCase = true)

    /**
     * Human readable reason from a SOAP fault, using UPnP's own fields:
     * `UPnPError/errorCode` + `errorDescription`, or the generic `faultstring`.
     */
    fun faultMessage(xml: String): String? {
        val errorCode = valueOf(xml, "errorCode")
        val description = valueOf(xml, "errorDescription")
        if (errorCode != null || description != null) {
            val upnpHint = errorCode?.let { code -> upnpErrorHint(code.toIntOrNull()) }
            return listOfNotNull(
                description?.takeIf { it.isNotBlank() },
                errorCode?.let { "UPnP error $it" },
                upnpHint,
            ).joinToString(" · ")
        }

        val faultString = valueOf(xml, "faultstring") ?: valueOf(xml, "faultcode")
        if (!faultString.isNullOrBlank()) return faultString

        return xml.take(200).takeIf { it.isNotBlank() }
    }

    /** Decodes the `&lt;DIDL-Lite&gt;…` payload a renderer echoes back. */
    fun unescape(value: String): String {
        if (!value.contains('&')) return value
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    /**
     * The three UPnP AVTransport error codes users actually hit, turned into advice
     * instead of a number.
     */
    private fun upnpErrorHint(code: Int?): String? = when (code) {
        701 -> "the TV rejected the transition (it may still be starting up)"
        714 -> "illegal seek target"
        716 -> "the TV cannot play this media format"
        718 -> "the TV could not load the URL (is the phone on the same network?)"
        else -> null
    }
}
