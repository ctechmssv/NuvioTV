package com.nuvio.tv.core.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Optional, server-owned startup preparation; never starts the client's torrent engine. */
data class StreamPreparationHint(val url: String)

internal class StreamPreparationException(val reason: String) : Exception(reason)

internal data class StreamPreparationStatus(
    val state: String,
    val stage: String,
    val progress: Float?,
    val downloadedBytes: Long?,
    val targetBytes: Long?,
    val downloadSpeedBytesPerSecond: Double?,
    val peers: Long?,
    val seeders: Long?,
    val etaSeconds: Double?,
    val playbackUrl: String?,
)

internal object StreamPreparationProtocol {
    const val MAX_RESPONSE_BYTES = 16_384L
    private val json = Json { isLenient = false }

    fun sameOriginUrl(origin: String, candidate: String): HttpUrl {
        fun parse(value: String): HttpUrl {
            if (value.length > 8192 || value.any { it <= ' ' || it == '\u007f' } || '\\' in value) {
                throw StreamPreparationException("invalid_url")
            }
            val url = value.toHttpUrlOrNull() ?: throw StreamPreparationException("invalid_url")
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
                throw StreamPreparationException("invalid_url")
            }
            // Empty userinfo (http://@host/) is also rejected, before URL normalization.
            if ('@' in value.substringAfter("://").substringBefore('/').substringBefore('?')) {
                throw StreamPreparationException("invalid_url")
            }
            return url
        }
        val base = parse(origin)
        val target = parse(candidate)
        if (base.scheme != target.scheme || base.host != target.host || base.port != target.port) {
            throw StreamPreparationException("origin_mismatch")
        }
        return target
    }

    fun parse(body: String, origin: String): StreamPreparationStatus {
        try {
            if (body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) fail()
            val root = json.parseToJsonElement(body) as? JsonObject ?: fail()
            fun text(name: String): String? {
                val element = root[name] ?: return null
                if (element == JsonNull) return null
                val primitive = element as? JsonPrimitive ?: fail()
                if (!primitive.isString) fail()
                return primitive.content
            }
            fun number(name: String): Long? {
                val element = root[name] ?: return null
                if (element == JsonNull) return null
                val primitive = element as? JsonPrimitive ?: fail()
                if (primitive.isString) fail()
                return primitive.longOrNull?.takeIf { it >= 0 } ?: fail()
            }
            fun measurement(name: String): Double? {
                val element = root[name] ?: return null
                if (element == JsonNull) return null
                val primitive = element as? JsonPrimitive ?: fail()
                if (primitive.isString) fail()
                return primitive.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 } ?: fail()
            }
            if (number("version") != 1L) throw StreamPreparationException("unsupported_version")
            val state = text("state")?.takeIf { it in setOf("preparing", "ready", "failed") } ?: fail()
            val stage = text("stage")?.takeIf { it in setOf("source", "metadata", "buffering", "ready", "failed") } ?: fail()
            val progress = root["progress"]?.takeUnless { it == JsonNull }?.let {
                val primitive = it as? JsonPrimitive ?: fail()
                if (primitive.isString) fail()
                primitive.floatOrNull?.takeIf { value -> value.isFinite() && value in 0f..1f } ?: fail()
            }
            val playbackUrl = text("playbackUrl")
            if (state == "ready") {
                if (playbackUrl == null) fail()
                sameOriginUrl(origin, playbackUrl)
            } else if (playbackUrl != null) fail()
            // Never display or log server error text (it may contain secrets).
            return StreamPreparationStatus(state, stage, progress, number("downloadedBytes"),
                number("targetBytes"), measurement("downloadSpeedBytesPerSecond"), number("peers"),
                number("seeders"), measurement("etaSeconds"), playbackUrl)
        } catch (e: StreamPreparationException) {
            throw e
        } catch (_: Exception) {
            throw StreamPreparationException("invalid_response")
        }
    }

    private fun fail(): Nothing = throw StreamPreparationException("invalid_response")
}

internal interface StreamPreparationTransport {
    suspend fun request(method: String): String
    /** Nonblocking, bounded best effort; safe even after the owning scope was cancelled. */
    fun close()
}

/** One selected-source interest. A ready handoff is retained until close, never repolled. */
internal class StreamPreparationSession(
    private val origin: String,
    private val transport: StreamPreparationTransport,
    private val pollIntervalMs: Long = 1_000,
    private val deadlineMs: Long = 180_000,
) {
    private var closed = false
    suspend fun awaitReady(onStatus: (StreamPreparationStatus) -> Unit): String {
        var handedOff = false
        try {
            return withTimeout(deadlineMs) {
                var method = "POST"
                while (true) {
                    val status = StreamPreparationProtocol.parse(transport.request(method), origin)
                    onStatus(status)
                    when (status.state) {
                        "ready" -> {
                            handedOff = true
                            return@withTimeout requireNotNull(status.playbackUrl)
                        }
                        "failed" -> throw StreamPreparationException("server_failed")
                    }
                    delay(pollIntervalMs)
                    method = "GET"
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        } finally {
            if (!handedOff) close()
        }
    }
    fun close() {
        if (!closed) {
            closed = true
            transport.close()
        }
    }
}
