package vip.wangxiaoming.music.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UserPrefsTest {

    @Test
    fun maxConcurrent_is_clamped_to_1_10() {
        assertEquals(1, UserPrefs.maxConcurrent(0))
        assertEquals(1, UserPrefs.maxConcurrent(-5))
        assertEquals(7, UserPrefs.maxConcurrent(7))
        assertEquals(10, UserPrefs.maxConcurrent(11))
        assertEquals(10, UserPrefs.maxConcurrent(999))
    }

    @Test
    fun qualityOrDefault_rejects_unknown() {
        assertEquals("320k", UserPrefs.qualityOrDefault(null))
        assertEquals("320k", UserPrefs.qualityOrDefault("999k"))
        assertEquals("flac", UserPrefs.qualityOrDefault("flac"))
    }

    @Test
    fun sourceOrDefault_rejects_unknown() {
        assertEquals("kw", UserPrefs.sourceOrDefault(null))
        assertEquals("kw", UserPrefs.sourceOrDefault("bilibili"))
        assertEquals("tx", UserPrefs.sourceOrDefault("tx"))
    }

    @Test
    fun authCache_isLoggedIn_requires_username() {
        assertEquals(false, AuthCache().isLoggedIn)
        assertEquals(true, AuthCache(username = "u").isLoggedIn)
    }
}