package com.example.localbeam.core

/**
 * Central place for the protocol constants this app speaks.
 */
object CastConstants {

    // ---------------------------------------------------------------- SSDP ---

    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val SSDP_MX_SECONDS = 2

    /** Target for a MediaRenderer (i.e. a TV that can play what we send it). */
    const val ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** Fallback target: every device on the link, filtered client side. */
    const val ST_ALL = "ssdp:all"

    const val MULTICAST_LOCK_TAG = "localbeam:ssdp"

    // ----------------------------------------------------------------- UPnP ---

    const val AV_TRANSPORT_SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"
    const val SOAP_ENVELOPE_NS = "http://schemas.xmlsoap.org/soap/envelope/"

    const val DEFAULT_INSTANCE_ID = 0

    // -------------------------------------------------------- Local server ---

    /** Preferred port for the embedded video server; nearby ports are used if taken. */
    const val DEFAULT_SERVER_PORT = 8080

    /** How many ports above [DEFAULT_SERVER_PORT] we are willing to try. */
    const val SERVER_PORT_ATTEMPTS = 10
}

/**
 * Everything DLNA related, kept in one place so the string values are verifiable.
 */
object Dlna {

    const val PROTOCOL_INFO_VIDEO_PREFIX = "http-get:*:"
    const val CONTENT_FEATURES_HEADER = "contentFeatures.dlna.org"
    const val TRANSFER_MODE_HEADER = "transferMode.dlna.org"
    const val TIME_SEEK_RANGE_HEADER = "TimeSeekRange.dlna.org"

    /**
     * OP=01 means "byte-seek is supported". That matches how our server answers
     * (HTTP Range -> 206 Partial Content) and is what lets a renderer translate
     * a Seek(REL_TIME) request into a byte offset.
     */
    const val DLNA_ORG_OP = "DLNA.ORG_OP=01"

    /** CI=0 -> the payload is not converted/transcoded. */
    const val DLNA_ORG_CI = "DLNA.ORG_CI=0"

    /** Standard "sender paced, streaming mode, background transfer" flags. */
    const val DLNA_ORG_FLAGS = "DLNA.ORG_FLAGS=01700000000000000000000000000000"

    val CONTENT_FEATURES: String = "$DLNA_ORG_OP;$DLNA_ORG_CI;$DLNA_ORG_FLAGS"

    const val TRANSFER_MODE_STREAMING = "Streaming"

    fun protocolInfo(mimeType: String): String = "$PROTOCOL_INFO_VIDEO_PREFIX$mimeType:DLNA.ORG_OP=01;DLNA.ORG_CI=0"
}
