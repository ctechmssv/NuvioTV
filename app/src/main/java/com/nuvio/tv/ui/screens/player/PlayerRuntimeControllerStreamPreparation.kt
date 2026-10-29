package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.core.player.StreamPreparationHttpTransport
import com.nuvio.tv.core.player.StreamPreparationSession
import com.nuvio.tv.core.player.StreamPreparationStatus
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shared gate before either engine can probe/open a hinted media source. */
internal fun PlayerRuntimeController.prepareServerPlaybackIfNeeded(
    url: String,
    headers: Map<String, String>,
    onReady: (String) -> Unit,
): Boolean {
    val endpoint = streamPreparationUrl ?: return false
    if (preparedServerPlaybackUrl == url) return false
    cancelServerStreamPreparation()
    _uiState.update { it.copy(error = null, showLoadingOverlay = true, isBuffering = true, isServerPreparing = true) }
    streamPreparationJob = scope.launch {
        try {
            val session = StreamPreparationSession(url, StreamPreparationHttpTransport(url, endpoint, headers))
            streamPreparationSession = session
            setLoadingStatus("server_preparation", context.getString(R.string.player_preparation_source), showOverlay = true)
            val readyUrl = session.awaitReady { status ->
                setLoadingStatus("server_preparation", preparationMessage(status), status.progress, showOverlay = true)
            }
            ensureActive()
            preparedServerPlaybackUrl = readyUrl
            currentStreamUrl = readyUrl
            _uiState.update { it.copy(currentStreamUrl = readyUrl, isServerPreparing = false) }
            onReady(readyUrl)
        } catch (_: TimeoutCancellationException) {
            showServerPreparationError(R.string.player_preparation_timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No server messages, URLs, credentials or exception details enter logs/UI.
            showServerPreparationError(R.string.player_preparation_failed)
        }
    }
    return true
}

internal fun PlayerRuntimeController.cancelServerStreamPreparation() {
    streamPreparationJob?.cancel()
    streamPreparationJob = null
    streamPreparationSession?.close()
    streamPreparationSession = null
    preparedServerPlaybackUrl = null
    _uiState.update { it.copy(isServerPreparing = false) }
}

private fun PlayerRuntimeController.showServerPreparationError(message: Int) {
    _uiState.update { it.copy(error = context.getString(message), showLoadingOverlay = false, loadingProgress = null, isBuffering = false, isServerPreparing = false) }
}

private fun PlayerRuntimeController.preparationMessage(status: StreamPreparationStatus): String {
    val label = context.getString(when (status.stage) {
        "metadata" -> R.string.player_preparation_metadata
        "buffering" -> R.string.player_preparation_buffering
        "ready" -> R.string.player_preparation_ready
        else -> R.string.player_preparation_source
    })
    val details = buildList {
        status.progress?.let { add("${(it * 100).toInt()}%") }
        status.downloadedBytes?.let { bytes ->
            val amount = String.format(Locale.getDefault(), "%.1f", bytes / 1_048_576.0)
            val target = status.targetBytes?.let { String.format(Locale.getDefault(), "%.1f", it / 1_048_576.0) }
            add(if (target == null) "$amount MiB" else "$amount/$target MiB")
        }
        status.downloadSpeedBytesPerSecond?.let { add(String.format(Locale.getDefault(), "%.1f MiB/s", it / 1_048_576.0)) }
        status.peers?.let { add(context.getString(R.string.player_preparation_peers, it)) }
        status.seeders?.let { add(context.getString(R.string.player_preparation_seeders, it)) }
        status.etaSeconds?.let { add(context.getString(R.string.player_preparation_eta, kotlin.math.ceil(it).toLong())) }
    }
    return if (details.isEmpty()) label else "$label\n${details.joinToString(" · ")}"
}
