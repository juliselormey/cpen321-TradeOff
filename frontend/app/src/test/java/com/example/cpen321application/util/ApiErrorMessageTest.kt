package com.example.cpen321application.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class ApiErrorMessageTest {
    private fun error(code: Int, body: String) = Response.error<Unit>(code, body.toResponseBody("application/json".toMediaType()))

    @Test fun extractsReadableValidationMessage() {
        assertEquals("Insufficient cash balance", apiErrorMessage(error(400, """{"error":"Insufficient cash balance"}"""), "Try again"))
    }
    @Test fun hidesHtmlMalformedBodiesAndServerInternals() {
        listOf("<html>proxy error</html>", "not JSON", "{}", """{"error":123}""").forEach {
            assertEquals("Try again", apiErrorMessage(error(400, it), "Try again"))
        }
        assertEquals("Try again", apiErrorMessage(error(500, """{"error":"SQL stack trace"}"""), "Try again"))
    }
    @Test fun expiredSessionHasAnActionableMessage() {
        assertTrue(apiErrorMessage(error(401, "{}"), "Try again").contains("sign in again"))
    }
}
