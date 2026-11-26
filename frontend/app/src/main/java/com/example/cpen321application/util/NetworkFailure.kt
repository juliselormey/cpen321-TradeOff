package com.example.cpen321application.util

import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

internal inline fun <T> runOrReportNetworkFailure(onError: (Exception) -> Unit, block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    onError(e); null
} catch (e: HttpException) {
    onError(e); null
} catch (e: JsonParseException) {
    onError(e); null
}

