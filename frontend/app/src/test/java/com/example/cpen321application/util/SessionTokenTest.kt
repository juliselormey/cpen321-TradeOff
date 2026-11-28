package com.example.cpen321application.util

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SessionTokenTest {
    private fun token(payload: String): String = "header." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(payload.toByteArray()) + ".signature"

    @Test fun tokenPresenceDoesNotMeanValidSession() {
        assertFalse(tokenIsCurrent(null, 0))
        assertFalse(tokenIsCurrent("not-a-jwt", 0))
        assertFalse(tokenIsCurrent(token("""{"sub":"user"}"""), 0))
        assertFalse(tokenIsCurrent(token("""{"exp":"not-a-number"}"""), 0))
    }
    @Test fun expiresExactlyAtTheClaimedTime() {
        val value = token("""{"sub":"user","exp":100}""")
        assertEquals(100000L, tokenExpiryMillis(value))
        assertTrue(tokenIsCurrent(value, 99999))
        assertFalse(tokenIsCurrent(value, 100000))
        assertFalse(tokenIsCurrent(value, 100001))
    }
    @Test fun overflowingAndMalformedClaimsAreRejected() {
        assertNull(tokenExpiryMillis(token("""{"exp":1e100}""")))
        assertNull(tokenExpiryMillis(token("[]")))
        assertNull(tokenExpiryMillis("header.invalid-base64.signature"))
    }
}
