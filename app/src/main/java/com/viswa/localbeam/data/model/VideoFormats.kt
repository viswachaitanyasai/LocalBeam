package com.viswa.localbeam.data.model

/**
 * Pure mapping helpers between MediaStore MIME types and the file extensions /
 * DLNA protocol info a renderer expects. No Android types, so it is unit tested.
 */
object VideoFormats {

    private val MIME_TO_EXTENSION = mapOf(
        "video/mp4" to "mp4",
        "video/3gpp" to "3gp",
        "video/3gpp2" to "3g2",
        "video/x-matroska" to "mkv",
        "video/webm" to "webm",
        "video/avi" to "avi",
        "video/x-msvideo" to "avi",
        "video/quicktime" to "mov",
        "video/mpeg" to "mpg",
        "video/mp2t" to "ts",
        "video/x-flv" to "flv",
        "video/x-ms-wmv" to "wmv",
        "video/ogg" to "ogv",
        "video/vnd.dlna.mpeg-tts" to "ts",
    )

    private val EXTENSION_TO_MIME = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "3gp" to "video/3gpp",
        "3g2" to "video/3gpp2",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/avi",
        "mov" to "video/quicktime",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "ts" to "video/mp2t",
        "flv" to "video/x-flv",
        "wmv" to "video/x-ms-wmv",
        "ogv" to "video/ogg",
    )

    /** Extensions MediaStore should have indexed; used to filter odd entries. */
    val SUPPORTED_EXTENSIONS: Set<String> = EXTENSION_TO_MIME.keys

    /**
     * Extension for a MIME type, falling back to the file name's own extension and
     * finally to `mp4`.
     */
    fun extensionForMime(mimeType: String?, fileName: String? = null): String {
        MIME_TO_EXTENSION[mimeType?.lowercase()?.substringBefore(';')?.trim()]?.let { return it }

        val fromName = fileName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.length <= 4 && it.all { c -> c.isLetterOrDigit() } }
        if (fromName != null) return fromName

        return "mp4"
    }

    /** MIME type for an extension, defaulting to `video/mp4`. */
    fun mimeForExtension(extension: String?): String =
        EXTENSION_TO_MIME[extension?.lowercase()] ?: "video/mp4"

    /**
     * Normalises a MIME type into something safe to place in a DLNA `protocolInfo`
     * and an HTTP `Content-Type` header.
     */
    fun normalizeMime(mimeType: String?, fileName: String? = null): String {
        val cleaned = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (!cleaned.isNullOrEmpty() && cleaned.startsWith("video/")) return cleaned
        return mimeForExtension(extensionForMime(mimeType, fileName))
    }
}
