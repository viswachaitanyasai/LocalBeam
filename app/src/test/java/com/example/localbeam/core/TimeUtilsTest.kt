package com.example.localbeam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun `formats seconds as hh mm ss`() {
        assertEquals("00:00:00", TimeUtils.toRelTime(0))
        assertEquals("00:01:05", TimeUtils.toRelTime(65))
        assertEquals("01:02:05", TimeUtils.toRelTime(3725))
        assertEquals("10:00:00", TimeUtils.toRelTime(36_000))
    }

    @Test
    fun `negative input is clamped`() {
        assertEquals("00:00:00", TimeUtils.toRelTime(-10))
    }

    @Test
    fun `parses the forms renderers actually send`() {
        assertEquals(65L, TimeUtils.parseRelTime("00:01:05"))
        assertEquals(65L, TimeUtils.parseRelTime("0:01:05"))
        assertEquals(65L, TimeUtils.parseRelTime("00:01:05.500"))
        assertEquals(3725L, TimeUtils.parseRelTime("1:02:05"))
        assertEquals(90L, TimeUtils.parseRelTime("01:30"))
    }

    @Test
    fun `unknown positions are reported as null instead of zero`() {
        assertNull(TimeUtils.parseRelTime(null))
        assertNull(TimeUtils.parseRelTime(""))
        assertNull(TimeUtils.parseRelTime("NOT_IMPLEMENTED"))
        assertNull(TimeUtils.parseRelTime("garbage"))
    }

    @Test
    fun `clock format is short form`() {
        assertEquals("0:00", TimeUtils.formatClock(0))
        assertEquals("1:05", TimeUtils.formatClock(65))
        assertEquals("62:05", TimeUtils.formatClock(3725))
    }

    @Test
    fun `duration shows a placeholder when unknown`() {
        assertEquals("--:--", TimeUtils.formatDuration(0))
        assertEquals("--:--", TimeUtils.formatDuration(-1))
        assertEquals("01:02:05", TimeUtils.formatDuration(3_725_000))
    }

    @Test
    fun `sizes are human readable`() {
        assertEquals("512 B", TimeUtils.formatSize(512))
        assertEquals("1.0 KB", TimeUtils.formatSize(1024))
        assertEquals("11.8 MB", TimeUtils.formatSize(12_345_678))
        assertEquals("—", TimeUtils.formatSize(0))
    }
}
