package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class StreamPreparationNavigationTest {
    @Test fun `resume navigation preserves a preparation capability without double decoding`() {
        val preparation = "https://media.example/prep?reference=a%2Fb%2Bc"
        val args = PlayerNavigationArgs.from(SavedStateHandle(mapOf(
            "streamUrl" to "https://media.example/play?expired=old",
            "streamPreparationUrl" to preparation,
        )))
        assertEquals(preparation, args.streamPreparationUrl)
    }

    @Test fun `legacy navigation has no preparation capability`() {
        assertNull(PlayerNavigationArgs.from(SavedStateHandle(mapOf("streamUrl" to "https://media.example/video")))
            .streamPreparationUrl)
    }
}
