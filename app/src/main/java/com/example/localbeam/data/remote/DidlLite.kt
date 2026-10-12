package com.example.localbeam.data.remote

import com.example.localbeam.core.Dlna
import com.example.localbeam.core.TimeUtils

/**
 * Builds the DIDL-Lite document that travels in `SetAVTransportURI`'s
 * `CurrentURIMetaData` argument.
 *
 * Renderers ignore the plain `CurrentURI` surprisingly often and only start
 * playback when the metadata advertises a usable `res` element, so the important
 * parts are: the DLNA protocol info (byte-seek support) and, when known, the
 * `duration` and `size` attributes – TVs use `duration` to turn a `Seek(REL_TIME)`
 * into a byte offset.
 */
object DidlLite {

    const val DIDL_NAMESPACE = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    const val DC_NAMESPACE = "http://purl.org/dc/elements/1.1/"
    const val UPNP_NAMESPACE = "urn:schemas-upnp-org:metadata-1-0/upnp/"

    const val VIDEO_ITEM_CLASS = "object.item.videoItem"

    /**
     * @param title shown on the TV's own UI.
     * @param url the LAN URL of our streaming server.
     * @param mimeType e.g. `video/mp4`; must match what the server sends.
     * @param sizeBytes `-1`/`0` when unknown (the attribute is then omitted).
     * @param durationMs `-1`/`0` when unknown.
     */
    fun build(
        title: String,
        url: String,
        mimeType: String,
        sizeBytes: Long,
        durationMs: Long,
    ): String {
        val resAttributes = buildString {
            append(" protocolInfo=\"")
            append(SoapXml.escape(Dlna.protocolInfo(mimeType)))
            append('"')
            if (sizeBytes > 0L) {
                append(" size=\"")
                append(sizeBytes)
                append('"')
            }
            if (durationMs > 0L) {
                append(" duration=\"")
                append(toDlnaDuration(durationMs))
                append('"')
            }
        }

        return buildString {
            append("<DIDL-Lite xmlns=\"").append(DIDL_NAMESPACE).append('"')
            append(" xmlns:dc=\"").append(DC_NAMESPACE).append('"')
            append(" xmlns:upnp=\"").append(UPNP_NAMESPACE).append("\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>").append(SoapXml.escape(title)).append("</dc:title>")
            append("<upnp:class>").append(VIDEO_ITEM_CLASS).append("</upnp:class>")
            append("<res").append(resAttributes).append('>')
            append(SoapXml.escape(url))
            append("</res>")
            append("</item>")
            append("</DIDL-Lite>")
        }
    }

    /**
     * DLNA durations look like `01:02:05.000` (a `HH:MM:SS.mmm` variant), unlike
     * UPnP's `01:02:05`.
     */
    fun toDlnaDuration(durationMs: Long): String {
        val safe = if (durationMs < 0) 0L else durationMs
        val millis = safe % 1000
        val relTime = TimeUtils.toRelTime(safe / 1000)
        return "$relTime.${"%03d".format(millis)}"
    }
}
