package com.viswa.localbeam.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlResolverTest {

    private val base = "http://192.168.43.2:49152/description.xml"

    @Test
    fun `absolute control urls are passed through`() {
        assertEquals(
            "http://10.0.0.5:9197/control/AVTransport1",
            UrlResolver.resolve(base, "http://10.0.0.5:9197/control/AVTransport1"),
        )
    }

    @Test
    fun `root relative control urls are resolved against the description url`() {
        assertEquals(
            "http://192.168.43.2:49152/upnp/control/AVTransport1",
            UrlResolver.resolve(base, "/upnp/control/AVTransport1"),
        )
    }

    @Test
    fun `path relative control urls replace the last path segment`() {
        assertEquals(
            "http://192.168.43.2:49152/upnp/control/AVTransport1",
            UrlResolver.resolve(base, "upnp/control/AVTransport1"),
        )
    }

    @Test
    fun `parent relative control urls are normalised`() {
        assertEquals(
            "http://192.168.43.2:49152/control/AVTransport1",
            UrlResolver.resolve("http://192.168.43.2:49152/upnp/device.xml", "../control/AVTransport1"),
        )
    }

    @Test
    fun `spaces in control urls are escaped instead of throwing`() {
        assertEquals(
            "http://192.168.43.2:49152/upnp/control%20/AVTransport1",
            UrlResolver.resolve(base, "/upnp/control /AVTransport1"),
        )
    }

    @Test
    fun `missing control urls resolve to null`() {
        assertNull(UrlResolver.resolve(base, null))
        assertNull(UrlResolver.resolve(base, ""))
        assertNull(UrlResolver.resolve(base, "   "))
    }

    @Test
    fun `base directory keeps the authority and drops the file name`() {
        assertEquals("http://192.168.43.2:49152/", UrlResolver.baseDirectory(base))
        assertEquals("http://192.168.43.2:49152/", UrlResolver.baseDirectory("http://192.168.43.2:49152"))
        assertEquals("http://192.168.43.2:49152/a/", UrlResolver.baseDirectory("http://192.168.43.2:49152/a/b.xml"))
        assertEquals("http://192.168.43.2:49152/", UrlResolver.baseDirectory("http://192.168.43.2:49152/description.xml?x=1"))
    }
}
