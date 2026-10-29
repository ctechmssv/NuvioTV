package com.nuvio.tv.core.player

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class StreamPreparationHttpTransport(
    private val origin: String,
    endpoint: String,
    private val headers: Map<String, String>,
    client: OkHttpClient = OkHttpClient(),
) : StreamPreparationTransport {
    private val endpoint = StreamPreparationProtocol.sameOriginUrl(origin, endpoint)
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()

    private fun requestBuilder(url: HttpUrl, method: String): Request.Builder = Request.Builder()
        .url(url).apply {
            headers.forEach { (key, value) ->
                if (key.lowercase() !in setOf("host", "content-length", "content-type", "range", "accept")) header(key, value)
            }
        }.header("Accept", "application/json")
        .method(method, if (method == "POST") ByteArray(0).toRequestBody(null) else null)

    override suspend fun request(method: String): String = withContext(Dispatchers.IO) {
        var target = endpoint
        var requestMethod = method
        repeat(6) { redirects ->
            val call = client.newCall(requestBuilder(target, requestMethod).build())
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(StreamPreparationException("network_failed"))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
            response.use {
                if (it.code in setOf(301, 302, 303, 307, 308)) {
                    if (redirects == 5) throw StreamPreparationException("invalid_response")
                    val location = it.header("Location") ?: throw StreamPreparationException("invalid_response")
                    if (location.any { char -> char <= ' ' || char == '\u007f' } || '\\' in location) {
                        throw StreamPreparationException("invalid_url")
                    }
                    target = StreamPreparationProtocol.sameOriginUrl(origin,
                        target.resolve(location)?.toString() ?: throw StreamPreparationException("invalid_url"))
                    if (it.code == 303) requestMethod = "GET"
                } else {
                    if (it.code !in setOf(200, 202)) throw StreamPreparationException("http_failed")
                    val body = it.body ?: throw StreamPreparationException("invalid_response")
                    if (body.contentLength() > StreamPreparationProtocol.MAX_RESPONSE_BYTES) throw StreamPreparationException("invalid_response")
                    val source = body.source()
                    // request reads at most limit+1 into the buffer; never allocate an unbounded body.
                    if (source.request(StreamPreparationProtocol.MAX_RESPONSE_BYTES + 1)) throw StreamPreparationException("invalid_response")
                    return@withContext source.readUtf8()
                }
            }
        }
        throw StreamPreparationException("invalid_response")
    }

    override fun close() {
        // No credentials can leave the origin: redirect following stays disabled for DELETE.
        runCatching { client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
            .newCall(requestBuilder(endpoint, "DELETE").build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) = response.close()
            }) }
    }
}
