package com.zionhuang.innertube.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseCookieTest {
    @Test
    fun `classic header form survives normalization`() {
        val cookie = "SID=abc123; SAPISID=xyz; __Secure-3PSID=qrs"
        assertEquals(cookie, normalizeCookieString(cookie))
        val map = parseCookieString(cookie)
        assertEquals("abc123", map["SID"])
        assertEquals("xyz", map["SAPISID"])
    }

    @Test
    fun `cookie editor json export is converted to header form`() {
        val json =
            """[{"domain":".youtube.com","name":"SID","value":"abc123","path":"/"},
               {"domain":".youtube.com","name":"SAPISID","value":"8O4q2g","path":"/"},
               {"domain":".youtube.com","name":"__Secure-3PSID","value":"g.a000","path":"/"}]"""
        val normalized = normalizeCookieString(json)
        val map = parseCookieString(normalized)
        assertEquals("abc123", map["SID"])
        assertEquals("8O4q2g", map["SAPISID"])
        assertEquals("g.a000", map["__Secure-3PSID"])
        assertTrue(normalized.startsWith("SID=abc123; "))
    }

    @Test
    fun `single object export is handled`() {
        val json = """{"name":"SAPISID","value":"zzz"}"""
        val map = parseCookieString(normalizeCookieString(json))
        assertEquals("zzz", map["SAPISID"])
    }

    @Test
    fun `broken json falls back to raw passthrough`() {
        val raw = "SID=a; SAPISID=b"
        assertEquals(raw, normalizeCookieString(raw))
    }
}
