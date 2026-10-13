package com.viswa.localbeam.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpParserTest {

    private val mediaRendererReply = "HTTP/1.1 200 OK\r\n" +
        "CACHE-CONTROL: max-age=1800\r\n" +
        "EXT:\r\n" +
        "LOCATION: http://192.168.43.2:49152/description.xml\r\n" +
        "SERVER: Linux/3.14 UPnP/1.0 MediaRenderer/1.0\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
        "USN: uuid:4d696e69-444c-164e-9d41::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
        "\r\n"

    @Test
    fun `extracts the interesting headers`() {
        val response = SsdpParser.parse(mediaRendererReply)!!
        assertEquals("http://192.168.43.2:49152/description.xml", response.location)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", response.searchTarget)
        assertEquals(200, response.statusCode)
        assertTrue(response.isMediaRenderer)
    }

    @Test
    fun `header names are case insensitive`() {
        val reply = "HTTP/1.1 200 OK\nLocation: http://10.0.0.5:9197/dmr\nst: upnp:rootdevice\n\n"
        val response = SsdpParser.parse(reply)!!
        assertEquals("http://10.0.0.5:9197/dmr", response.location)
        assertEquals("upnp:rootdevice", response.searchTarget)
    }

    @Test
    fun `device key ignores the service suffix so one device is listed once`() {
        val response = SsdpParser.parse(mediaRendererReply)!!
        assertEquals("uuid:4d696e69-444c-164e-9d41", response.deviceKey)
    }

    @Test
    fun `detects a renderer through the usn when the st is generic`() {
        val reply = "HTTP/1.1 200 OK\r\n" +
            "LOCATION: http://192.168.43.2:49152/description.xml\r\n" +
            "ST: upnp:rootdevice\r\n" +
            "USN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertTrue(SsdpParser.parse(reply)!!.isMediaRenderer)
    }

    @Test
    fun `plain devices are not treated as renderers`() {
        val reply = "HTTP/1.1 200 OK\r\n" +
            "LOCATION: http://192.168.43.9:80/description.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
            "USN: uuid:xyz::urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"
        assertFalse(SsdpParser.parse(reply)!!.isMediaRenderer)
    }

    @Test
    fun `notify advertisements and junk are ignored`() {
        assertNull(SsdpParser.parse("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\n\r\n"))
        assertNull(SsdpParser.parse(""))
        assertNull(SsdpParser.parse("garbage"))
    }

    @Test
    fun `m-search is a well formed discovery request`() {
        val request = SsdpParser.buildMSearch("urn:schemas-upnp-org:device:MediaRenderer:1", 2)
        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(request.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(request.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(request.contains("MX: 2\r\n"))
        assertTrue(request.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }
}
