package com.example.rtmpstreamer.streaming

import com.pedro.common.ConnectChecker

/**
 * Satu [ConnectChecker] mewakili status koneksi ke SATU tujuan RTMP.
 * MultiCamera1 dari RootEncoder butuh satu ConnectChecker per tujuan agar
 * status connect/gagal/putus masing-masing bisa dipantau terpisah
 * (ini yang memungkinkan on/off per RTMP tanpa mengganggu yang lain).
 */
class ProfileConnectChecker(
    val profileId: Long,
    val label: String,
    private val onStarted: (Long) -> Unit = {},
    private val onSuccess: (Long) -> Unit,
    private val onFailed: (Long, String) -> Unit,
    private val onDisconnect: (Long) -> Unit,
    private val onAuthError: (Long) -> Unit = {},
    private val onAuthSuccess: (Long) -> Unit = {},
    private val onBitrate: (Long, Long) -> Unit = { _, _ -> }
) : ConnectChecker {

    override fun onConnectionStarted(url: String) {
        onStarted(profileId)
    }

    override fun onConnectionSuccess() {
        onSuccess(profileId)
    }

    override fun onConnectionFailed(reason: String) {
        onFailed(profileId, reason)
    }

    override fun onDisconnect() {
        onDisconnect(profileId)
    }

    override fun onAuthError() {
        onAuthError(profileId)
    }

    override fun onAuthSuccess() {
        onAuthSuccess(profileId)
    }

    override fun onNewBitrate(bitrate: Long) {
        onBitrate(profileId, bitrate)
    }
}
