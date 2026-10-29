package com.nuvio.tv.core.auth

import com.nuvio.tv.domain.model.ServerCapabilities
import com.nuvio.tv.domain.model.ServerConfiguration
import org.junit.Assert.*
import org.junit.Test

class QrLoginConfigurationTest {
    private val configured = ServerConfiguration("https://backend.example", "public-client-key",
        ServerCapabilities(emailPasswordAuth = false, tvLogin = true), isCustom = false,
        deviceLoginWebBaseUrl = "https://example/link")

    @Test fun `browser login page alone cannot configure QR generation`() {
        assertFalse(configured.copy(backendUrl = "", publishableKey = "").isQrLoginConfigured())
        assertFalse(configured.copy(backendUrl = " ").isQrLoginConfigured())
        assertFalse(configured.copy(publishableKey = " ").isQrLoginConfigured())
    }

    @Test fun `invalid backend fails before a request is constructed`() {
        assertFalse(configured.copy(backendUrl = "backend-without-scheme").isQrLoginConfigured())
        assertFalse(configured.copy(backendUrl = "file:///tmp/backend").isQrLoginConfigured())
        assertTrue(configured.isQrLoginConfigured())
        assertTrue(configured.copy(backendUrl = "http://192.168.1.2:8080", isCustom = true).isQrLoginConfigured())
    }
}
