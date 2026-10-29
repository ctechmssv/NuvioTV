package com.nuvio.tv.ui.screens.account

/** An unconfigured build must stop its spinner and discard any previous QR data. */
internal fun AccountUiState.withMissingQrLoginConfiguration(message: String): AccountUiState = copy(
    isLoading = false,
    error = message,
    qrLoginCode = null,
    qrLoginUserCode = null,
    qrLoginUrl = null,
    qrLoginVerificationUri = null,
    qrLoginNonce = null,
    qrLoginBitmap = null,
    qrLoginStatus = message,
    qrLoginExpiresAtMillis = null,
)
