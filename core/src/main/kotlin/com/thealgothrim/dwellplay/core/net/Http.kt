package com.thealgothrim.dwellplay.core.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

val DwellJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  isLenient = true
}

fun defaultHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

/** A request that failed with an HTTP status. */
class HttpException(val code: Int, message: String) : IOException(message)

/** Runs [request] off the main thread and returns the body, throwing [HttpException] on a non-2xx. */
suspend fun OkHttpClient.fetch(request: Request): String =
    withContext(Dispatchers.IO) {
      val response = newCall(request).await()
      response.use {
        val body = it.body.string()
        if (!it.isSuccessful) throw HttpException(it.code, "HTTP ${it.code} from ${request.url.host}")
        body
      }
    }

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
  enqueue(
      object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
          cont.resumeWith(Result.success(response))
        }
      }
  )
  cont.invokeOnCancellation { runCatching { cancel() } }
}
