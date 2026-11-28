package com.example.cpen321application.util

import com.google.gson.JsonParser
import com.google.gson.JsonParseException
import java.util.Base64

/** Expiry is a UI/session hint only; the server still verifies the JWT signature. */
internal fun tokenExpiryMillis(token: String?): Long? = try {
    val parts = token?.split('.')
    if (parts?.size != 3) null else {
        val payload = JsonParser.parseString(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
        val expiry = if (payload.isJsonObject) payload.asJsonObject.get("exp") else null
        if (expiry?.isJsonPrimitive == true && expiry.asJsonPrimitive.isNumber) {
            expiry.asBigDecimal.multiply(1000.toBigDecimal()).longValueExact().takeIf { it > 0 }
        } else null
    }
} catch (_: IllegalArgumentException) { null
} catch (_: ArithmeticException) { null
} catch (_: JsonParseException) { null }

internal fun tokenIsCurrent(token: String?, now: Long = System.currentTimeMillis()): Boolean =
    tokenExpiryMillis(token)?.let { it > now } == true
