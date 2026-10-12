package com.example.localbeam.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DlnaTimeSeekTest {

    @Test
    fun `parses an open ended npt`() {
        assertEquals(DlnaTimeSeek.Request(60, null), DlnaTimeSeek.parse("npt=00:01:00.000-"))
    }

    @Test
    fun `parses a closed npt with duration`() {
        assertEquals(
            DlnaTimeSeek.Request(30, 60),
            DlnaTimeSeek.parse("npt=0:00:30-0:01:00/0:10:00"),
        )
    }

    @Test
    fun `parses plain seconds`() {
        assertEquals(DlnaTimeSeek.Request(30, null), DlnaTimeSeek.parse("npt=30.500-"))
    }

    @Test
    fun `handles the playseek spelling some vendors use`() {
        assertEquals(DlnaTimeSeek.Request(10, null), DlnaTimeSeek.parse("playseek=00:00:10-"))
    }

    @Test
    fun `npt now means resume, which we cannot honour as a range`() {
        assertNull(DlnaTimeSeek.parse("npt=now-"))
    }

    @Test
    fun `ignores unrelated or malformed headers`() {
        assertNull(DlnaTimeSeek.parse(null))
        assertNull(DlnaTimeSeek.parse(""))
        assertNull(DlnaTimeSeek.parse("bytes=0-99"))
        assertNull(DlnaTimeSeek.parse("npt=abc-"))
    }

    @Test
    fun `converts a start time into a byte offset`() {
        // Half way through a one minute video that is 1 MB: 512 KB in.
        assertEquals(512_000L, DlnaTimeSeek.toByteOffset(30, durationMs = 60_000, totalBytes = 1_024_000))
    }

    @Test
    fun `clamps the byte offset to the file`() {
        assertEquals(1000L, DlnaTimeSeek.toByteOffset(999, durationMs = 10_000, totalBytes = 1000))
        assertEquals(0L, DlnaTimeSeek.toByteOffset(-5, durationMs = 10_000, totalBytes = 1000))
    }

    @Test
    fun `unknown duration cannot produce an offset`() {
        assertEquals(0L, DlnaTimeSeek.toByteOffset(30, durationMs = 0, totalBytes = 1000))
    }

    @Test
    fun `response header uses the npt range form`() {
        assertEquals(
            "npt=00:01:00.000-00:10:00.000/00:10:00.000",
            DlnaTimeSeek.responseHeader(startSeconds = 60, totalDurationSeconds = 600),
        )
    }
}
