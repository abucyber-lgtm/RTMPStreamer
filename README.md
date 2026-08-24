# RTMP Streamer (Android, Kotlin, RootEncoder)

Aplikasi Android untuk live streaming ke banyak tujuan RTMP sekaligus (YouTube Live, Facebook Live, server sendiri, dll), menggunakan [RootEncoder](https://github.com/pedroSG94/RootEncoder) v2.8.0.

## Fitur

| Fitur | Keterangan |
|---|---|
| **Multi-streaming asli** | Kamera di-encode **sekali**, hasilnya dikirim paralel ke semua tujuan RTMP yang aktif — pakai `MultiCamera1` bawaan RootEncoder, bukan trik. Hemat baterai/CPU dibanding encode berkali-kali. |
| **Profil RTMP berlabel** | Simpan banyak tujuan dengan nama (misal "YouTube - Kajian Subuh", "Server Cadangan"), tersimpan permanen via Room database. |
| **On/off per tujuan** | Matikan salah satu tujuan RTMP saat live tanpa menghentikan yang lain (misal server cadangan lagi tidak dipakai). |
| **Reconnect otomatis** | Kalau salah satu koneksi RTMP putus, otomatis coba sambung ulang sampai 5x sebelum menyerah — tiap tujuan independen. |
| **Monitoring real-time** | Durasi live, bitrate, dan jumlah frame yang di-drop, ditampilkan di status pill atas layar. |
| **Bitrate adaptif** | Opsional — otomatis turun/naikkan bitrate video mengikuti kondisi jaringan (pakai `BitrateAdapter` bawaan library). |
| **Watermark teks** | Tambahkan label teks (nama acara/masjid) yang benar-benar masuk ke video terkirim, bukan cuma tampil di layar HP. |
| **Rekam lokal** | Simpan rekaman ke penyimpanan HP bersamaan dengan streaming. |
| **Live di background** | Foreground service menjaga streaming tetap jalan walau app diminimize. |
| **Jadwal otomatis** | Atur jam mulai streaming otomatis (misal tiap Jumat jam 11:45 untuk khutbah). |
| **Ganti kamera** | Depan/belakang, bisa saat live. |

## Cara membuka project

1. Buka **Android Studio** (disarankan Hedgehog/2023.1 ke atas).
2. **Open** → arahkan ke folder `RTMPStreamer`.
3. Klik **Sync Now**. Android Studio otomatis generate `gradlew` dan unduh dependency (RootEncoder dari JitPack, Room, dll) — pastikan koneksi internet aktif dan JitPack (`jitpack.io`) tidak diblokir firewall/proxy.
4. Sambungkan HP Android (kamera fisik dibutuhkan, emulator kurang ideal) → **Run**.

## Build APK online tanpa install Android Studio (GitHub Actions)

Project ini sudah dilengkapi `.github/workflows/build.yml` yang otomatis build APK di server GitHub setiap kali kamu push kode. Caranya:

1. Buat repository baru di [github.com](https://github.com/new) (boleh private).
2. Upload/push isi folder `RTMPStreamer` ini ke repo tersebut. Cara paling mudah tanpa install Git di laptop:
   - Buka repo yang baru dibuat → **Add file → Upload files**
   - Drag & drop semua isi folder `RTMPStreamer` (bukan file zip-nya, tapi isinya)
   - Commit langsung ke branch `main`
3. Buka tab **Actions** di repo tersebut — workflow "Build APK" akan otomatis berjalan (±5-10 menit untuk build pertama kali).
4. Setelah selesai (centang hijau ✅), klik run tersebut → scroll ke bagian **Artifacts** → download `rtmp-streamer-debug-apk.zip`.
5. Extract zip itu → dapat file `.apk` → pindahkan ke HP Android → install (aktifkan "Install dari sumber tidak dikenal" kalau diminta).

**Catatan:**
- APK yang dihasilkan adalah **debug build** (belum ditandatangani untuk rilis Play Store, tapi bisa langsung diinstall dan dites di HP).
- Tiap kali kamu edit kode dan push lagi ke `main`, APK baru otomatis ter-build — tidak perlu ulangi setup apa pun.
- Workflow ini bisa juga dipicu manual: tab **Actions** → pilih workflow **Build APK** → tombol **Run workflow**.
- Free tier GitHub Actions cukup untuk kebutuhan ini (2.000 menit/bulan gratis untuk repo private, unlimited untuk repo public).

## Cara pakai

### Streaming dasar
1. Buka app → izinkan kamera, mikrofon, notifikasi.
2. Ketuk **+** di panel bawah untuk menambah tujuan RTMP (label + URL).
3. Pastikan switch di sebelah tujuan yang mau dipakai dalam posisi **ON**.
4. Ketuk **Mulai Streaming** — semua tujuan yang ON akan mulai terhubung bersamaan.
5. Titik hijau di sebelah kiri label = tujuan itu sedang terhubung.

### On/off tujuan saat live
- Geser switch di list tujuan kapan saja, termasuk saat sedang live — tujuan itu akan connect/disconnect tanpa mengganggu tujuan lain.
- **Catatan teknis**: tujuan yang ditambahkan **setelah** streaming dimulai baru akan ikut aktif setelah kamu **stop lalu mulai lagi** (karena engine multi-stream perlu tahu daftar lengkap tujuan sejak awal sesi). Tujuan yang sudah ada sebelum sesi dimulai bisa di-on/off-kan bebas kapan saja.

### Watermark
Ketuk ikon pensil di kanan atas → isi teks (misal nama masjid) → tampil di pojok kanan bawah video, ikut terkirim ke semua platform.

### Rekam lokal
Ketuk ikon kamera-video di kanan (butuh streaming sedang aktif) untuk mulai rekam; ketuk lagi untuk stop. File tersimpan di folder Movies aplikasi.

### Bitrate adaptif
Ketuk ikon gear di kanan untuk toggle ON/OFF. Saat ON, bitrate video otomatis menyesuaikan kondisi jaringan berdasarkan tujuan RTMP pertama yang aktif.

### Jadwal otomatis
Ketuk ikon kalender → pilih jam → app akan membuka diri sendiri dan mulai streaming otomatis di jam tersebut (tujuan yang berstatus ON saat dijadwalkan yang akan dipakai). Android 12+ mungkin minta izin "Alarm & pengingat" di pengaturan sistem — app akan mengarahkan otomatis.

## Konfigurasi kualitas streaming

Ada di `MainActivity.kt` bagian atas class:

```kotlin
private val videoWidth = 1280
private val videoHeight = 720
private val videoFps = 30
private val videoBitrate = 2 * 1024 * 1024  // 2 Mbps
private val audioBitrate = 128 * 1024
private val audioSampleRate = 44100
```

Rekomendasi bitrate video (acuan umum platform seperti YouTube):
- 720p30 → 1.5–4 Mbps
- 1080p30 → 3–6 Mbps
- 480p30 → 0.5–1.5 Mbps

Multi-streaming ke banyak tujuan sekaligus tetap memakai SATU bitrate/resolusi ini untuk semua tujuan (karena encode sekali) — pertimbangkan tujuan dengan koneksi paling lemah saat menentukan angka ini.

## Struktur project

```
RTMPStreamer/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/rtmpstreamer/
│       │   ├── MainActivity.kt                    # UI + orkestrasi semua fitur
│       │   ├── data/
│       │   │   ├── StreamProfile.kt                # entity: label, url, enabled
│       │   │   ├── StreamProfileDao.kt
│       │   │   └── AppDatabase.kt
│       │   ├── streaming/
│       │   │   └── ProfileConnectChecker.kt        # status koneksi per tujuan RTMP
│       │   ├── ui/
│       │   │   └── ProfileAdapter.kt               # RecyclerView daftar tujuan
│       │   ├── service/
│       │   │   └── StreamingForegroundService.kt   # live tetap jalan di background
│       │   └── scheduler/
│       │       ├── StreamSchedule.kt               # AlarmManager
│       │       └── ScheduleReceiver.kt
│       └── res/
│           ├── layout/ (activity_main, item_profile, dialog_add_profile)
│           └── values/
├── build.gradle.kts
└── settings.gradle.kts
```

## Catatan penting

- **Multi-streaming & bandwidth**: mengirim ke N tujuan RTMP sekaligus tetap butuh upload bandwidth N × bitrate secara bersamaan (misal 3 tujuan @ 2 Mbps = butuh ~6 Mbps upload stabil). Ini bukan keterbatasan aplikasi — encode-nya memang cuma sekali (hemat CPU/baterai), tapi tiap tujuan tetap butuh koneksi jaringan terpisah.
- **Alternatif untuk banyak tujuan dengan bandwidth terbatas**: kalau koneksi internet di lokasi terbatas, pertimbangkan restream server-side — kirim SATU stream dari HP ke server relay (misal `nginx-rtmp` di Mini PC/VPS kamu), lalu server itu yang meneruskan ke YouTube/Facebook/dll. HP cuma perlu upload sekali, server yang tanggung fan-out ke N platform. Ini pola yang dipakai kebanyakan software restream profesional.
- **RTMPS**: tinggal ganti skema URL jadi `rtmps://`, tidak perlu ubah kode.
- **Android 14+**: foreground service butuh tipe `camera` + `microphone` yang sudah ditangani di kode; pastikan user tidak menolak izin notifikasi karena dibutuhkan untuk foreground service berjalan mulus.
- Kalau build gagal karena tidak menemukan dependency, pastikan `jitpack.io` tidak diblokir jaringan/firewall kampus-kantor.
