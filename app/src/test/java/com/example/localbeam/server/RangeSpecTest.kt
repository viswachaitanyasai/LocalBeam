package com.example.localbeam.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeSpecTest {

    private val total = 2048L

    @Test
    fun `no header means send the whole file`() {
        assertEquals(RangeRequest.Full, RangeSpec.parse(null, total))
        assertEquals(RangeRequest.Full, RangeSpec.parse("", total))
        assertEquals(RangeRequest.Full, RangeSpec.parse("   ", total))
    }

    @Test
    fun `closed range is honoured`() {
        val range = RangeSpec.parse("bytes=0-99", total)
        assertEquals(RangeRequest.Partial(0, 99), range)
    }

    @Test
    fun `open ended range runs to the last byte`() {
        assertEquals(RangeRequest.Partial(100, total - 1), RangeSpec.parse("bytes=100-", total))
    }

    @Test
    fun `suffix range counts back from the end`() {
        assertEquals(RangeRequest.Partial(total - 500, total - 1), RangeSpec.parse("bytes=-500", total))
    }

    @Test
    fun `suffix larger than the file becomes the whole file`() {
        assertEquals(RangeRequest.Partial(0, total - 1), RangeSpec.parse("bytes=-999999", total))
    }

    @Test
    fun `end beyond the file is clamped`() {
        // RFC 7233: an over-long end is legal and means "to the end".
        assertEquals(RangeRequest.Partial(0, total - 1), RangeSpec.parse("bytes=0-999999", total))
        assertEquals(RangeRequest.Partial(1024, total - 1), RangeSpec.parse("bytes=1024-4096", total))
    }

    @Test
    fun `start beyond the file is unsatisfiable`() {
        assertEquals(RangeRequest.Unsatisfiable, RangeSpec.parse("bytes=2048-", total))
        assertEquals(RangeRequest.Unsatisfiable, RangeSpec.parse("bytes=5000-6000", total))
    }

    @Test
    fun `reversed range is unsatisfiable`() {
        assertEquals(RangeRequest.Unsatisfiable, RangeSpec.parse("bytes=500-100", total))
    }

    @Test
    fun `zero length suffix is unsatisfiable`() {
        assertEquals(RangeRequest.Unsatisfiable, RangeSpec.parse("bytes=-0", total))
    }

    @Test
    fun `garbage falls back to the whole file`() {
        assertEquals(RangeRequest.Full, RangeSpec.parse("bytes=abc-def", total))
        assertEquals(RangeRequest.Full, RangeSpec.parse("items=0-10", total))
        assertEquals(RangeRequest.Full, RangeSpec.parse("bytes=", total))
    }

    @Test
    fun `multiple ranges fall back to the whole file instead of a bad 206`() {
        assertEquals(RangeRequest.Full, RangeSpec.parse("bytes=0-49,100-149", total))
    }

    @Test
    fun `extra parameters after a semicolon are tolerated`() {
        assertEquals(RangeRequest.Partial(10, 19), RangeSpec.parse("bytes=10-19;q=1", total))
    }

    @Test
    fun `partial response length covers the requested bytes`() {
        val range = RangeSpec.parse("bytes=100-199", total) as RangeRequest.Partial
        assertEquals(100L, range.length)
    }

    @Test
    fun `headers are rendered in the RFC form`() {
        assertEquals("bytes 0-1023/2048", RangeSpec.contentRange(0, 1023, 2048))
        assertEquals("bytes */2048", RangeSpec.unsatisfiableContentRange(2048))
    }

    @Test
    fun `unknown file size never produces a partial response`() {
        assertTrue(RangeSpec.parse("bytes=0-99", 0) is RangeRequest.Full)
    }
}
