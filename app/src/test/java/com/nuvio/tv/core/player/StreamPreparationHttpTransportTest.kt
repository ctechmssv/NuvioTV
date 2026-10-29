package com.nuvio.tv.core.player

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class StreamPreparationHttpTransportTest {
    @Test fun `same origin redirects preserve headers and method and DELETE releases interest`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/status"))
            server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(204))
            val transport = StreamPreparationHttpTransport(server.url("/play").toString(),
                server.url("/prepare").toString(), mapOf("Authorization" to "Bearer private", "Range" to "bytes=0-"))
            assertEquals("{}", transport.request("POST"))
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("POST", first.method)
            assertEquals("POST", second.method)
            assertEquals("Bearer private", second.getHeader("Authorization"))
            assertNull(first.getHeader("Range"))
            transport.close()
            assertEquals("DELETE", server.takeRequest(5, TimeUnit.SECONDS)?.method)
        }
    }

    @Test fun `cross origin redirect never reaches destination with credentials`() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/steal")))
                val transport = StreamPreparationHttpTransport(server.url("/play").toString(),
                    server.url("/prepare").toString(), mapOf("Authorization" to "private"))
                try { transport.request("POST"); fail("must reject redirect") }
                catch (e: StreamPreparationException) { assertEquals("origin_mismatch", e.reason) }
                assertEquals(0, other.requestCount)
            }
        }
    }

    @Test fun `oversized declared and chunked responses are rejected`() = runBlocking {
        MockWebServer().use { server ->
            val big = "x".repeat(16_385)
            server.enqueue(MockResponse().setBody(big))
            server.enqueue(MockResponse().setChunkedBody(big, 128))
            val transport = StreamPreparationHttpTransport(server.url("/play").toString(), server.url("/prepare").toString(), emptyMap())
            repeat(2) {
                try { transport.request("GET"); fail("body must be bounded") }
                catch (e: StreamPreparationException) { assertEquals("invalid_response", e.reason) }
            }
        }
    }

    @Test fun `cancelling a pending HTTP request does not deliver a late response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(20, TimeUnit.SECONDS).setBody("{}"))
            val transport = StreamPreparationHttpTransport(server.url("/play").toString(), server.url("/prepare").toString(), emptyMap())
            val request = async { transport.request("POST") }
            // Let the IO dispatcher send its request before cancellation.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
        }
    }
}
