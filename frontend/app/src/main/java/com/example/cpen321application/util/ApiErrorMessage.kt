package com.example.cpen321application.util

import com.google.gson.JsonParser
import com.google.gson.JsonParseException
import retrofit2.Response
import java.io.IOException

/** Never display an HTML proxy error, stack trace, or JSON envelope to the user. */
internal fun apiErrorMessage(response: Response<*>, fallback: String): String {
    when (response.code()) {
        401 -> return "Your session has expired. Please sign in again."
        403 -> return "This action is not allowed for your account."
        429 -> return "Too many requests. Please wait and try again."
    }
    if (response.code() !in listOf(400, 404, 409, 422)) return fallback
    return try {
        val json = JsonParser.parseString(response.errorBody()?.string() ?: return fallback)
        val error = if (json.isJsonObject) json.asJsonObject.get("error") else null
        if (error?.isJsonPrimitive == true && error.asJsonPrimitive.isString) {
            error.asString.trim().takeIf { it.isNotEmpty() && it.length <= 240 } ?: fallback
        } else fallback
    } catch (_: JsonParseException) {
        fallback
    } catch (_: IOException) {
        fallback
    }
}
