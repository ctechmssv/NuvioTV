package com.nuvio.tv.core.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StreamPreparationTest {
    private val origin = "https://media.example/video?old=expired"
    private val preparing = """{"version":1,"state":"preparing","stage":"buffering","progress":0.25,"downloadedBytes":1048576,"targetBytes":4194304,"downloadSpeedBytesPerSecond":524288,"peers":3,"seeders":2,"etaSeconds":6}"""
    private val ready = """{"version":1,"state":"ready","stage":"ready","progress":1,"playbackUrl":"https://media.example/video?fresh=token"}"""

    @Test fun `parser accepts bounded optional telemetry and a fresh ready URL`() {
        val status = StreamPreparationProtocol.parse(preparing, origin)
        assertEquals(0.25f, status.progress)
        assertEquals(1048576L, status.downloadedBytes)
        assertEquals(3L, status.peers)
        assertEquals("https://media.example/video?fresh=token", StreamPreparationProtocol.parse(ready, origin).playbackUrl)
        val minimal = StreamPreparationProtocol.parse("""{"version":1,"state":"preparing","stage":"source"}""", origin)
        assertNull(minimal.progress)
        assertNull(minimal.etaSeconds)
        val fractional = StreamPreparationProtocol.parse(preparing.replace("524288", "524288.7").replace("\"etaSeconds\":6", "\"etaSeconds\":6.4"), origin)
        assertEquals(524288.7, fractional.downloadSpeedBytesPerSecond!!, 0.0001)
        assertEquals(6.4, fractional.etaSeconds!!, 0.0001)
    }

    @Test fun `parser fails closed on version shape telemetry and unsafe playback`() {
        listOf("{}", "[]", "not json", ready.replace("\"version\":1", "\"version\":2"),
            preparing.replace("0.25", "1.25"), preparing.replace("0.25", "\"0.25\""),
            preparing.replace("1048576", "-1"), ready.replace("https://media.example/video", "https://evil.example/video"),
            ready.replace("\"ready\",\"stage\"", "\"unknown\",\"stage\""),
            ready.replace("\"playbackUrl\":\"https://media.example/video?fresh=token\"", "\"playbackUrl\":null"),
            " ".repeat(16_385)).forEach { body ->
            assertThrows(StreamPreparationException::class.java) { StreamPreparationProtocol.parse(body, origin) }
        }
    }

    @Test fun `same origin accepts normalized default port and rejects credentials fragments schemas and controls`() {
        assertEquals("media.example", StreamPreparationProtocol.sameOriginUrl(origin, "https://media.example:443/prep").host)
        listOf("http://media.example/prep", "https://media.example:444/prep", "https://evil.example/prep",
            "https://user:secret@media.example/prep", "https://@media.example/prep", "https://media.example/prep#fragment",
            "file:///tmp/video", "https://media.example/a\nb", "https://media.example\\evil.example/prep").forEach {
            assertThrows(StreamPreparationException::class.java) { StreamPreparationProtocol.sameOriginUrl(origin, it) }
        }
    }

    @Test fun `selection posts once polls then hands off fresh media and releases on exit`() = runTest {
        val transport = FakeTransport(mutableListOf(preparing, ready))
        val session = StreamPreparationSession(origin, transport)
        var opened: String? = null
        val job = launch { opened = session.awaitReady {} }
        runCurrent()
        assertNull(opened)
        assertEquals(listOf("POST"), transport.methods)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("https://media.example/video?fresh=token", opened)
        assertEquals(listOf("POST", "GET"), transport.methods)
        assertEquals(0, transport.closed)
        job.join()
        session.close()
        session.close()
        assertEquals(1, transport.closed)
    }

    @Test fun `back or changed source cancels polling and releases interest without media handoff`() = runTest {
        val transport = FakeTransport(mutableListOf(preparing))
        val session = StreamPreparationSession(origin, transport)
        var opened = false
        val job = launch { session.awaitReady {}; opened = true }
        runCurrent()
        job.cancel()
        job.join()
        advanceTimeBy(5_000)
        assertFalse(opened)
        assertEquals(listOf("POST"), transport.methods)
        assertEquals(1, transport.closed)
    }

    @Test fun `deadline malformed failed and network responses release interest without opening media`() = runTest {
        for (response in listOf("{}", """{"version":1,"state":"failed","stage":"failed","error":"safe_enum"}""")) {
            val transport = FakeTransport(mutableListOf(response))
            try { StreamPreparationSession(origin, transport).awaitReady {}; fail("must fail") }
            catch (_: StreamPreparationException) { }
            assertEquals(1, transport.closed)
        }
        val waiting = object : StreamPreparationTransport {
            var closed = false
            override suspend fun request(method: String): String = CompletableDeferred<String>().await()
            override fun close() { closed = true }
        }
        try { StreamPreparationSession(origin, waiting, deadlineMs = 180_000).awaitReady {}; fail("deadline") }
        catch (_: TimeoutCancellationException) { }
        assertTrue(waiting.closed)
        val network = object : StreamPreparationTransport {
            var closed = false
            override suspend fun request(method: String): String = throw StreamPreparationException("network_failed")
            override fun close() { closed = true }
        }
        try { StreamPreparationSession(origin, network).awaitReady {}; fail("network") }
        catch (_: StreamPreparationException) { }
        assertTrue(network.closed)
    }

    private class FakeTransport(val responses: MutableList<String>) : StreamPreparationTransport {
        val methods = mutableListOf<String>()
        var closed = 0
        override suspend fun request(method: String): String {
            methods += method
            return responses.removeAt(0)
        }
        override fun close() { closed++ }
    }
}
