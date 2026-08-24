package com.example.rtmpstreamer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Satu tujuan RTMP yang disimpan user, contoh:
 *  label = "YouTube - Kajian Subuh", url = "rtmp://a.rtmp.youtube.com/live2/xxxx"
 *
 * [enabled] = true berarti profil ini ikut dipush saat "Mulai Streaming" ditekan.
 * Bisa dimatikan sementara tanpa menghapus datanya (misal server backup lagi tidak dipakai).
 */
@Entity(tableName = "stream_profiles")
data class StreamProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val label: String,
    val url: String,
    val enabled: Boolean = true,
    val isPrimary: Boolean = false, // profil utama = sumber kamera/mic (lihat catatan multi-stream di README)
    val createdAt: Long = System.currentTimeMillis()
)
