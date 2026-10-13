package com.viswa.localbeam.data.remote

import com.viswa.localbeam.core.CastConstants
import com.viswa.localbeam.data.model.LocalVideo
import com.viswa.localbeam.server.StreamContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoapMessagesTest {

    // --------------------------------------------------------------- escaping ---

    @Test
    fun `escape covers the five XML characters`() {
        assertEquals("Tom &amp; Jerry &lt;HD&gt; &quot;x&quot; &apos;y&apos;", SoapXml.escape("Tom & Jerry <HD> \"x\" 'y'"))
    }

    @Test
    fun `escape leaves clean text alone`() {
        assertEquals("Holiday 2024.mp4", SoapXml.escape("Holiday 2024.mp4"))
    }

    @Test
    fun `unescape reverses escaping`() {
        assertEquals("<DIDL-Lite>x & y</DIDL-Lite>", SoapXml.unescape("&lt;DIDL-Lite&gt;x &amp; y&lt;/DIDL-Lite&gt;"))
    }

    // ------------------------------------------------------- response reading ---

    @Test
    fun `reads values with or without a namespace prefix and attributes`() {
        val xml = "<u:GetTransportInfoResponse><CurrentTransportState val=\"x\">PLAYING</CurrentTransportState></u:GetTransportInfoResponse>"
        assertEquals("PLAYING", SoapXml.valueOf(xml, "CurrentTransportState"))
        assertNull(SoapXml.valueOf(xml, "RelTime"))
    }

    @Test
    fun `reads position info values`() {
        val xml = """
            <s:Body><u:GetPositionInfoResponse>
            <RelTime>00:01:05</RelTime>
            <TrackDuration>01:30:00</TrackDuration>
            </u:GetPositionInfoResponse></s:Body>
        """.trimIndent()
        assertEquals("00:01:05", SoapXml.valueOf(xml, "RelTime"))
        assertEquals("01:30:00", SoapXml.valueOf(xml, "TrackDuration"))
    }

    @Test
    fun `detects a soap fault and explains it in AVTransport terms`() {
        val fault = """
            <s:Envelope><s:Body><s:Fault>
            <faultcode>s:Client</faultcode>
            <detail><UPnPError>
            <errorCode>716</errorCode>
            <errorDescription>Resource not found</errorDescription>
            </UPnPError></detail></s:Fault></s:Body></s:Envelope>
        """.trimIndent()

        assertTrue(SoapXml.isFault(fault))
        val message = SoapXml.faultMessage(fault)!!
        assertTrue(message.contains("Resource not found"))
        assertTrue(message.contains("UPnP error 716"))
        assertTrue(message.contains("cannot play this media format"))
    }

    @Test
    fun `non fault responses are not flagged`() {
        assertFalse(SoapXml.isFault("<s:Body><u:PlayResponse/></s:Body>"))
    }

    // ------------------------------------------------------- envelope building ---

    @Test
    fun `set av transport uri carries the url and escaped metadata`() {
        val metadata = "<DIDL-Lite><item/></DIDL-Lite>"
        val body = SoapEnvelope.setAvTransportUri("http://192.168.43.1:8080/video.mp4", metadata)

        assertTrue(body.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertTrue(body.contains("<s:Envelope xmlns:s=\"${CastConstants.SOAP_ENVELOPE_NS}\""))
        assertTrue(body.contains("<u:SetAVTransportURI xmlns:u=\"${CastConstants.AV_TRANSPORT_SERVICE_TYPE}\">"))
        assertTrue(body.contains("<InstanceID>0</InstanceID>"))
        assertTrue(body.contains("<CurrentURI>http://192.168.43.1:8080/video.mp4</CurrentURI>"))
        // The metadata is an XML document inside XML, so its markup is escaped once more.
        assertTrue(body.contains("&lt;DIDL-Lite&gt;&lt;item/&gt;&lt;/DIDL-Lite&gt;"))
        assertFalse(body.contains("<CurrentURIMetaData><DIDL-Lite>"))
    }

    @Test
    fun `play sends speed one and seek sends a rel time target`() {
        assertTrue(SoapEnvelope.play().contains("<Speed>1</Speed>"))
        val seek = SoapEnvelope.seek("00:01:30")
        assertTrue(seek.contains("<Unit>REL_TIME</Unit>"))
        assertTrue(seek.contains("<Target>00:01:30</Target>"))
    }

    @Test
    fun `soap action header is quoted as the spec requires`() {
        assertEquals(
            "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            SoapEnvelope.soapActionHeader(
                CastConstants.AV_TRANSPORT_SERVICE_TYPE,
                "SetAVTransportURI",
            ),
        )
    }

    @Test
    fun `titles with xml characters survive the round trip`() {
        val title = "Tom & Jerry <HD>"
        val metadata = DidlLite.build(
            title = title,
            url = "http://192.168.43.1:8080/video.mp4",
            mimeType = "video/mp4",
            sizeBytes = 1024,
            durationMs = 65_000,
        )
        val body = SoapEnvelope.setAvTransportUri("http://192.168.43.1:8080/video.mp4", metadata)

        // Unescaping envelope then metadata must give the original title back.
        val didl = SoapXml.unescape(SoapXml.valueOf(body, "CurrentURIMetaData")!!)
        assertEquals(title, SoapXml.unescape(SoapXml.valueOf(didl, "dc:title")!!))
    }

    // ------------------------------------------------------------ didl metadata ---

    @Test
    fun `didl advertises byte seek support and the real file size`() {
        val didl = DidlLite.build(
            title = "Movie",
            url = "http://192.168.43.1:8080/video.mkv",
            mimeType = "video/x-matroska",
            sizeBytes = 5_000_000,
            durationMs = 3_725_000,
        )

        assertTrue(didl.startsWith("<DIDL-Lite xmlns=\"${DidlLite.DIDL_NAMESPACE}\""))
        assertTrue(didl.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:video/x-matroska:DLNA.ORG_OP=01;DLNA.ORG_CI=0\""))
        assertTrue(didl.contains("size=\"5000000\""))
        assertTrue(didl.contains("duration=\"01:02:05.000\""))
        assertTrue(didl.contains(">http://192.168.43.1:8080/video.mkv</res>"))
    }

    @Test
    fun `a repositioned stream is described as what is left of the file`() {
        val video = LocalVideo(
            id = 7,
            uri = "content://media/external/video/media/7",
            title = "Big Buck Bunny",
            mimeType = "video/x-matroska",
            sizeBytes = 1_000_000,
            durationMs = 600_000,
            folderPath = "/storage/emulated/0/Movies",
            folderName = "Movies",
        )
        val content = StreamContent.from(video, startSeconds = 180)
        val window = content.window()

        val didl = DidlLite.build(
            title = content.title,
            url = "http://192.168.43.1:8080${content.path}",
            mimeType = content.contentType,
            sizeBytes = window.length,
            durationMs = window.remainingDurationMs,
        )

        // 70% of the bytes and the last seven minutes: the TV's own timeline.
        assertTrue(didl.contains("size=\"700000\""))
        assertTrue(didl.contains("duration=\"00:07:00.000\""))
        assertTrue(didl.contains(">http://192.168.43.1:8080/video.mkv</res>"))
    }

    @Test
    fun `didl omits attributes it cannot know`() {
        val didl = DidlLite.build(
            title = "Unknown",
            url = "http://192.168.43.1:8080/video.mp4",
            mimeType = "video/mp4",
            sizeBytes = -1,
            durationMs = 0,
        )
        assertFalse(didl.contains("size="))
        assertFalse(didl.contains("duration="))
    }
}
