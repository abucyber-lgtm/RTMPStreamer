package com.example.rtmpstreamer

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.rtmpstreamer.data.AppDatabase
import com.example.rtmpstreamer.data.StreamProfile
import com.example.rtmpstreamer.databinding.ActivityMainBinding
import com.example.rtmpstreamer.databinding.DialogAddProfileBinding
import com.example.rtmpstreamer.scheduler.StreamSchedule
import com.example.rtmpstreamer.service.StreamingForegroundService
import com.example.rtmpstreamer.streaming.ProfileConnectChecker
import com.example.rtmpstreamer.ui.ProfileAdapter
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.multiple.MultiCamera1
import com.pedro.library.multiple.MultiType
import com.pedro.library.util.BitrateAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * MainActivity
 *
 * Live streaming RTMP dengan dukungan multi-tujuan sekaligus (encode sekali,
 * dikirim ke banyak server RTMP secara paralel) menggunakan MultiCamera1 dari
 * RootEncoder. Fitur: profil RTMP berlabel, on/off per tujuan, reconnect
 * otomatis, monitoring (durasi/bitrate/dropped frame), watermark teks,
 * rekam lokal, bitrate adaptif, dan jadwal mulai otomatis.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase
    private lateinit var profileAdapter: ProfileAdapter

    // Dibangun ulang setiap kali daftar profil (tambah/hapus) berubah saat tidak live
    private var multiCamera: MultiCamera1? = null
    private var lastProfileIds: List<Long> = emptyList()
    private var sessionProfiles: List<StreamProfile> = emptyList()
    private val profileIndexMap = mutableMapOf<Long, Int>()

    private var isStreaming = false
    private var isRecording = false
    private var isOverlayVisible = false
    private var isAdaptiveBitrateEnabled = false

    private val connectedIds = mutableSetOf<Long>()
    private val retryCounts = mutableMapOf<Long, Int>()
    private val maxRetries = 5

    private var streamStartTime = 0L
    private var latestBitrateBps = 0L
    private var primaryProfileId: Long? = null

    private var textFilter: TextObjectFilterRender? = null
    private var bitrateAdapter: BitrateAdapter? = null

    private val monitorHandler = Handler(Looper.getMainLooper())
    private var monitorRunnable: Runnable? = null

    // Parameter encoding
    private val videoWidth = 1280
    private val videoHeight = 720
    private val videoFps = 30
    private val videoBitrate = 2 * 1024 * 1024
    private val audioBitrate = 128 * 1024
    private val audioSampleRate = 44100
    private val audioStereo = true

    private val requiredPermissions: Array<String>
        get() {
            val base = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                base.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return base.toTypedArray()
        }
    private val permissionRequestCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = AppDatabase.getInstance(this)

        setupProfileList()
        setupListeners()
        checkPermissions()

        db.streamProfileDao().observeAll().observe(this) { profiles ->
            profileAdapter.submitList(profiles)
            binding.tvNoProfiles.visibility = if (profiles.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            rebuildMultiCameraIfNeeded(profiles)

            if (intent.getBooleanExtra(EXTRA_AUTO_START, false) && !isStreaming && profiles.any { it.enabled }) {
                intent.removeExtra(EXTRA_AUTO_START)
                binding.root.postDelayed({ startStreamingAll() }, 800)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Profile list (RecyclerView) + CRUD
    // ---------------------------------------------------------------------

    private fun setupProfileList() {
        profileAdapter = ProfileAdapter(
            onToggle = { profile, checked -> onProfileToggled(profile, checked) },
            onDelete = { profile -> confirmDeleteProfile(profile) }
        )
        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = profileAdapter
    }

    private fun onProfileToggled(profile: StreamProfile, checked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            db.streamProfileDao().setEnabled(profile.id, checked)
        }
        if (isStreaming) {
            val idx = profileIndexMap[profile.id]
            val camera = multiCamera
            if (idx == null || camera == null) {
                Toast.makeText(this, "Restart streaming untuk menerapkan tujuan baru", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                if (checked) {
                    camera.startStream(MultiType.RTMP, idx, profile.url)
                } else {
                    camera.stopStream(MultiType.RTMP, idx)
                    connectedIds.remove(profile.id)
                    profileAdapter.activeConnections = connectedIds.toSet()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal ubah status: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteProfile(profile: StreamProfile) {
        if (isStreaming && profileIndexMap.containsKey(profile.id)) {
            Toast.makeText(this, "Stop streaming dulu sebelum menghapus tujuan ini", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Hapus tujuan?")
            .setMessage("\"${profile.label}\" akan dihapus dari daftar.")
            .setPositiveButton("Hapus") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch { db.streamProfileDao().delete(profile) }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAddProfileDialog() {
        val dialogBinding = DialogAddProfileBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle("Tambah Tujuan RTMP")
            .setView(dialogBinding.root)
            .setPositiveButton("Simpan") { _, _ ->
                val label = dialogBinding.etLabel.text?.toString()?.trim().orEmpty()
                val url = dialogBinding.etUrl.text?.toString()?.trim().orEmpty()
                if (label.isEmpty() || url.isEmpty()) {
                    Toast.makeText(this, "Label dan URL wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                CoroutineScope(Dispatchers.IO).launch {
                    db.streamProfileDao().insert(StreamProfile(label = label, url = url, enabled = true))
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // MultiCamera1 lifecycle (dibangun ulang saat daftar profil berubah)
    // ---------------------------------------------------------------------

    private fun rebuildMultiCameraIfNeeded(profiles: List<StreamProfile>) {
        if (isStreaming) return // jangan ganggu sesi yang sedang live
        val newIds = profiles.map { it.id }
        if (newIds == lastProfileIds && multiCamera != null) return

        multiCamera?.let { if (it.isOnPreview) it.stopPreview() }
        lastProfileIds = newIds

        if (profiles.isEmpty()) {
            multiCamera = null
            sessionProfiles = emptyList()
            profileIndexMap.clear()
            return
        }

        val checkers: Array<ConnectChecker> = profiles.map { buildChecker(it) }.toTypedArray()
        profileIndexMap.clear()
        profiles.forEachIndexed { idx, p -> profileIndexMap[p.id] = idx }
        sessionProfiles = profiles

        multiCamera = MultiCamera1(binding.openGlView, checkers)
        if (hasPermissions()) {
            multiCamera?.startPreview()
        }
    }

    private fun buildChecker(profile: StreamProfile): ProfileConnectChecker {
        return ProfileConnectChecker(
            profileId = profile.id,
            label = profile.label,
            onSuccess = { id ->
                runOnUiThread {
                    connectedIds.add(id)
                    retryCounts[id] = 0
                    profileAdapter.activeConnections = connectedIds.toSet()
                    binding.tvStatus.text = getString(R.string.status_streaming)
                }
            },
            onFailed = { id, reason -> runOnUiThread { handleConnectionFailed(id, reason) } },
            onDisconnect = { id ->
                runOnUiThread {
                    connectedIds.remove(id)
                    profileAdapter.activeConnections = connectedIds.toSet()
                }
            },
            onAuthError = { id ->
                runOnUiThread {
                    val label = sessionProfiles.find { it.id == id }?.label ?: "tujuan"
                    Toast.makeText(this, "Autentikasi gagal untuk $label", Toast.LENGTH_SHORT).show()
                }
            },
            onBitrate = { id, bitrate -> handleBitrateUpdate(id, bitrate) }
        )
    }

    private fun handleConnectionFailed(profileId: Long, reason: String) {
        val label = sessionProfiles.find { it.id == profileId }?.label ?: "tujuan"
        val count = retryCounts.getOrDefault(profileId, 0)
        val idx = profileIndexMap[profileId]
        val camera = multiCamera

        if (idx != null && camera != null && count < maxRetries && isStreaming) {
            retryCounts[profileId] = count + 1
            Toast.makeText(this, "Menghubungkan ulang ke $label (${count + 1}/$maxRetries)", Toast.LENGTH_SHORT).show()
            camera.getStreamClient(MultiType.RTMP, idx).reTry(3000, reason)
        } else {
            connectedIds.remove(profileId)
            profileAdapter.activeConnections = connectedIds.toSet()
            Toast.makeText(this, "Gagal konek ke $label: $reason", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleBitrateUpdate(profileId: Long, bitrate: Long) {
        if (profileId == primaryProfileId) {
            latestBitrateBps = bitrate
            if (isAdaptiveBitrateEnabled) {
                val idx = profileIndexMap[profileId]
                val camera = multiCamera
                if (idx != null && camera != null) {
                    val hasCongestion = camera.getStreamClient(MultiType.RTMP, idx).hasCongestion()
                    bitrateAdapter?.adaptBitrate(bitrate, hasCongestion)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Start / stop streaming ke semua tujuan yang aktif
    // ---------------------------------------------------------------------

    private fun setupListeners() {
        binding.btnStartStop.setOnClickListener {
            if (isStreaming) stopStreamingAll() else startStreamingAll()
        }
        binding.btnAddProfile.setOnClickListener { showAddProfileDialog() }
        binding.btnSwitchCamera.setOnClickListener {
            try {
                multiCamera?.switchCamera()
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal ganti kamera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnToggleOverlay.setOnClickListener { toggleOverlay() }
        binding.btnToggleRecord.setOnClickListener { toggleRecording() }
        binding.btnAdaptiveBitrate.setOnClickListener { toggleAdaptiveBitrate() }
        binding.btnSchedule.setOnClickListener { showScheduleDialog() }
    }

    private fun startStreamingAll() {
        val camera = multiCamera
        if (camera == null) {
            Toast.makeText(this, "Tambahkan minimal satu tujuan RTMP dulu", Toast.LENGTH_SHORT).show()
            return
        }
        val enabledProfiles = sessionProfiles.filter { it.enabled }
        if (enabledProfiles.isEmpty()) {
            Toast.makeText(this, "Aktifkan (on) minimal satu tujuan RTMP", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasPermissions()) {
            requestPermissions()
            return
        }

        binding.tvStatus.text = getString(R.string.status_connecting)
        try {
            val prepared = camera.prepareVideo(videoWidth, videoHeight, videoFps, videoBitrate, 0) &&
                camera.prepareAudio(audioBitrate, audioSampleRate, audioStereo)
            if (!prepared) {
                Toast.makeText(this, "Gagal mempersiapkan encoder", Toast.LENGTH_SHORT).show()
                binding.tvStatus.text = getString(R.string.status_idle)
                return
            }

            primaryProfileId = enabledProfiles.first().id
            bitrateAdapter = BitrateAdapter { newBitrate -> camera.setVideoBitrateOnFly(newBitrate) }.apply {
                setMaxBitrate(videoBitrate)
            }

            enabledProfiles.forEach { profile ->
                val idx = profileIndexMap[profile.id] ?: return@forEach
                camera.getStreamClient(MultiType.RTMP, idx).setReTries(maxRetries)
                camera.startStream(MultiType.RTMP, idx, profile.url)
            }

            isStreaming = true
            streamStartTime = System.currentTimeMillis()
            updateButtonState()
            startMonitoring()
            StreamingForegroundService.start(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            binding.tvStatus.text = getString(R.string.status_idle)
        }
    }

    private fun stopStreamingAll() {
        val camera = multiCamera
        profileIndexMap.forEach { (_, idx) ->
            try {
                camera?.stopStream(MultiType.RTMP, idx)
            } catch (_: Exception) {
            }
        }
        if (isRecording) stopRecordingInternal()
        isStreaming = false
        connectedIds.clear()
        profileAdapter.activeConnections = emptySet()
        updateButtonState()
        stopMonitoring()
        binding.tvStatus.text = getString(R.string.status_stopped)
        StreamingForegroundService.stop(this)
    }

    private fun updateButtonState() {
        if (isStreaming) {
            binding.btnStartStop.text = getString(R.string.btn_stop)
            binding.btnStartStop.setBackgroundColor(getColor(R.color.accent_red))
        } else {
            binding.btnStartStop.text = getString(R.string.btn_start)
            binding.btnStartStop.setBackgroundColor(getColor(R.color.accent_green))
        }
    }

    // ---------------------------------------------------------------------
    // Monitoring: durasi, bitrate, dropped frame
    // ---------------------------------------------------------------------

    private fun startMonitoring() {
        binding.tvMonitoring.visibility = android.view.View.VISIBLE
        monitorRunnable = object : Runnable {
            override fun run() {
                val elapsedSec = (System.currentTimeMillis() - streamStartTime) / 1000
                val mm = (elapsedSec / 60).toString().padStart(2, '0')
                val ss = (elapsedSec % 60).toString().padStart(2, '0')
                val kbps = latestBitrateBps / 1000

                var dropped = 0L
                val camera = multiCamera
                if (camera != null) {
                    profileIndexMap.values.forEach { idx ->
                        try {
                            val client = camera.getStreamClient(MultiType.RTMP, idx)
                            dropped += client.getDroppedVideoFrames() + client.getDroppedAudioFrames()
                        } catch (_: Exception) {
                        }
                    }
                }

                binding.tvMonitoring.text = "$mm:$ss  •  $kbps kbps  •  $dropped drop"
                monitorHandler.postDelayed(this, 1000)
            }
        }
        monitorHandler.post(monitorRunnable!!)
    }

    private fun stopMonitoring() {
        monitorRunnable?.let { monitorHandler.removeCallbacks(it) }
        binding.tvMonitoring.visibility = android.view.View.GONE
    }

    // ---------------------------------------------------------------------
    // Watermark / label overlay (masuk ke video, bukan cuma tampilan lokal)
    // ---------------------------------------------------------------------

    private fun toggleOverlay() {
        val camera = multiCamera
        if (camera == null) {
            Toast.makeText(this, "Tambahkan tujuan RTMP dulu", Toast.LENGTH_SHORT).show()
            return
        }
        if (isOverlayVisible) {
            textFilter?.let { camera.glInterface.removeFilter(it) }
            textFilter = null
            isOverlayVisible = false
            return
        }

        val input = android.widget.EditText(this).apply {
            hint = "Contoh: LIVE - Masjid Kendal Mengaji"
        }
        AlertDialog.Builder(this)
            .setTitle("Teks Watermark")
            .setView(input)
            .setPositiveButton("Tampilkan") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty().ifEmpty { "LIVE" }
                val filter = TextObjectFilterRender()
                camera.glInterface.addFilter(filter)
                filter.setText(text, 32f, Color.WHITE, Color.parseColor("#80000000"))
                filter.setScale(40f, 15f)
                filter.setPosition(TranslateTo.BOTTOM_RIGHT)
                textFilter = filter
                isOverlayVisible = true
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Rekam lokal bersamaan dengan streaming
    // ---------------------------------------------------------------------

    private fun toggleRecording() {
        val camera = multiCamera ?: run {
            Toast.makeText(this, "Tambahkan tujuan RTMP dulu", Toast.LENGTH_SHORT).show()
            return
        }
        if (isRecording) {
            stopRecordingInternal()
            return
        }
        if (!isStreaming) {
            Toast.makeText(this, "Mulai streaming dulu sebelum merekam (encoder butuh sesi aktif)", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val fileName = "stream_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.mp4"
            val path = "${moviesDir?.absolutePath}/$fileName"
            camera.startRecord(path)
            isRecording = true
            Toast.makeText(this, "Merekam ke: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mulai rekam: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingInternal() {
        try {
            multiCamera?.stopRecord()
        } catch (_: Exception) {
        }
        isRecording = false
        Toast.makeText(this, "Rekaman disimpan", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------
    // Bitrate adaptif
    // ---------------------------------------------------------------------

    private fun toggleAdaptiveBitrate() {
        isAdaptiveBitrateEnabled = !isAdaptiveBitrateEnabled
        Toast.makeText(
            this,
            if (isAdaptiveBitrateEnabled) "Bitrate adaptif: ON" else "Bitrate adaptif: OFF",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ---------------------------------------------------------------------
    // Jadwal mulai streaming otomatis
    // ---------------------------------------------------------------------

    private fun showScheduleDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(this, "Izinkan \"Alarm & pengingat\" di pengaturan sistem dulu", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                return
            }
        }
        val now = Calendar.getInstance()
        TimePickerDialog(this, { _, hour, minute ->
            val trigger = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
            StreamSchedule.scheduleNext(this, trigger.timeInMillis)
            val fmt = SimpleDateFormat("HH:mm, dd MMM", Locale("id", "ID")).format(trigger.time)
            Toast.makeText(this, "Streaming otomatis dijadwalkan: $fmt", Toast.LENGTH_LONG).show()
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
    }

    // ---------------------------------------------------------------------
    // Lifecycle & permissions
    // ---------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        val camera = multiCamera
        if (camera != null && hasPermissions() && !camera.isOnPreview && !isStreaming) {
            camera.startPreview()
        }
    }

    override fun onPause() {
        super.onPause()
        // Streaming & recording tetap berjalan di background berkat StreamingForegroundService.
        // Preview kamera lokal saja yang dihentikan saat tidak live, demi hemat baterai.
        if (!isStreaming) {
            multiCamera?.let { if (it.isOnPreview) it.stopPreview() }
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissions() {
        if (!hasPermissions()) requestPermissions()
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, permissionRequestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            if (hasPermissions()) {
                multiCamera?.let { if (!it.isOnPreview) it.startPreview() }
            } else {
                Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_AUTO_START = "extra_auto_start"
    }
}
