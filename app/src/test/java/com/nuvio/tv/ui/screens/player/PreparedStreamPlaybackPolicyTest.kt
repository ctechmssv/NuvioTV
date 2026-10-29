package com.nuvio.tv.ui.screens.player

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PreparedStreamPlaybackPolicyTest {
    @Test fun `long read timeout is scoped to prepared client and keeps the original unchanged`() {
        val legacy = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request()) }.build()
        val prepared = PlayerPlaybackNetworking.preparedStreamClient(legacy, "https://media.example/play")
        assertEquals(15_000, legacy.readTimeoutMillis)
        assertEquals(150_000, prepared.readTimeoutMillis)
        assertEquals(15_000, prepared.connectTimeoutMillis)
        assertEquals(1, legacy.interceptors.size)
        assertTrue(prepared.interceptors.isEmpty())
    }

    @Test fun `prepared media cannot redirect credentials outside the source origin`() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/steal")))
            val client = PlayerPlaybackNetworking.preparedStreamClient(OkHttpClient(), server.url("/play").toString())
            assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(server.url("/play")).header("X-Secret", "private").build()).execute().close()
            }
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun `MPV legacy startup timeout remains thirty seconds but prepared startup can wait for a cold range`() {
        val counters = MpvStartupWatchdogPolicy.Counters(absoluteTicks = 59, stallTicks = 59)
        val legacy = MpvStartupWatchdogPolicy.Input(true, false, false, false, counters)
        assertEquals(MpvStartupWatchdogPolicy.Action.AbsoluteTimeout, MpvStartupWatchdogPolicy.step(legacy).action)
        assertEquals(MpvStartupWatchdogPolicy.Action.Continue, MpvStartupWatchdogPolicy.step(legacy.copy(serverPrepared = true)).action)
        assertEquals(MpvStartupWatchdogPolicy.Action.AbsoluteTimeout, MpvStartupWatchdogPolicy.step(legacy.copy(
            serverPrepared = true, counters = counters.copy(absoluteTicks = 359))).action)
    }

    @Test fun `cold prepared range does not trigger the normal fifteen second Exo stall recovery`() {
        val input = PlayerStallWatchdogPolicy.Input(20_000, 10_000, 90_000, 20_000)
        assertTrue(PlayerStallWatchdogPolicy.evaluate(input) is PlayerStallWatchdogPolicy.Decision.SeekPastBufferedEdge)
        assertEquals(PlayerStallWatchdogPolicy.Decision.KeepWaiting,
            PlayerStallWatchdogPolicy.evaluate(input.copy(thresholdMs = 150_000)))
    }
}
