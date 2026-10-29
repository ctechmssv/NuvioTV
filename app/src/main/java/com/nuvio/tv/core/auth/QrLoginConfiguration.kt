package com.nuvio.tv.core.auth

import com.nuvio.tv.domain.model.ServerConfiguration
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class MissingQrLoginConfigurationException : IllegalStateException("QR sign-in is not configured in this build")

/** QR generation needs a backend and its public client key, not just the browser login page. */
internal fun ServerConfiguration.isQrLoginConfigured(): Boolean =
    backendUrl.trim().toHttpUrlOrNull() != null && publishableKey.isNotBlank()
