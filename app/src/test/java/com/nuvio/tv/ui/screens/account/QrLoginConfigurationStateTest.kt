package com.nuvio.tv.ui.screens.account

import org.junit.Assert.*
import org.junit.Test

class QrLoginConfigurationStateTest {
    @Test fun `missing config stops loading and clears stale pairing data with an actionable error`() {
        val state = AccountUiState(isLoading = true, qrLoginCode = "old-code", qrLoginUserCode = "old-user-code",
            qrLoginUrl = "https://example/old", qrLoginVerificationUri = "https://example/link", qrLoginNonce = "old-nonce",
            qrLoginExpiresAtMillis = 1234L)
            .withMissingQrLoginConfiguration("Install a configured build")
        assertFalse(state.isLoading)
        assertEquals("Install a configured build", state.error)
        assertEquals(state.error, state.qrLoginStatus)
        assertNull(state.qrLoginCode)
        assertNull(state.qrLoginUserCode)
        assertNull(state.qrLoginUrl)
        assertNull(state.qrLoginVerificationUri)
        assertNull(state.qrLoginNonce)
        assertNull(state.qrLoginExpiresAtMillis)
    }
}
