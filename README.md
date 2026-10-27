<p align="center">
  <img src="docs/brand/logo.png" alt="Logo Saling Jaga" width="180">
</p>

<h1 align="center">Saling Jaga</h1>
<p align="center"><b>Connecting in Crisis</b>: telepon, walkie-talkie, chat, dan SOS antar HP Android tanpa internet dan tanpa sinyal.</p>

---

Saat bencana datang, BTS sering mati dan internet hilang tepat ketika orang paling membutuhkannya. Hal yang sama terjadi di gunung, hutan, atau laut. **Saling Jaga** menghubungkan HP secara langsung lewat radio Wi‑Fi di dalam HP (Wi‑Fi Direct). Tidak butuh kartu SIM, router, server, atau Google Play Services. Orang dalam satu rombongan tetap bisa bicara, saling tahu kondisi masing-masing, dan menemukan satu sama lain.

Gratis untuk warga. Kebutuhan tim dan institusi seperti posko, SAR, sekolah, dan perusahaan menjadi dasar model bisnisnya (lihat [Model keberlanjutan](#model-keberlanjutan)).

> **Status:** MVP v0.6.0. Semua fitur sudah ter-build dan protokolnya teruji otomatis, tapi belum diuji lapangan di HP asli. Masukan dari uji coba sangat dibutuhkan.

## Tampilan

| Splash | Cari perangkat | Grup & chat |
| --- | --- | --- |
| <img src="docs/screenshots/0-splash.png" width="240"> | <img src="docs/screenshots/1-cari-perangkat.png" width="240"> | <img src="docs/screenshots/2-grup-dan-chat.png" width="240"> |

| SOS masuk | SOS sendiri & anggota terputus | Kompas |
| --- | --- | --- |
| <img src="docs/screenshots/3-sos-masuk.png" width="240"> | <img src="docs/screenshots/4-sos-sendiri-dan-terputus.png" width="240"> | <img src="docs/screenshots/5-kompas.png" width="240"> |

Screenshot dirender otomatis dari kode, bukan mockup (lihat [Pengujian](#pengujian)). Nama dan pesan di dalamnya hanya contoh.

## Fitur

**Komunikasi**
- **Tahan untuk bicara**: walkie-talkie ke seluruh anggota grup.
- **Mode telepon**: suara dua arah terus-menerus, dengan peredam gema dan derau bawaan HP.
- **Chat teks** dan **Pengumuman**. Pengumuman berupa kartu kuning yang berbunyi di semua HP, cocok untuk info titik kumpul dari posko atau ketua rombongan.
- **Grup lebih dari 2 HP**. Satu HP menjadi pemilik grup dan meneruskan suara serta pesan ke semua anggota.

**Keselamatan**
- **SOS**: semua HP membunyikan alarm keras dan bergetar. Muncul juga kartu merah berisi jarak dan arah ke pengirim, misalnya "259 m ke arah barat laut". SOS tetap aktif sampai pengirim membatalkannya, dan anggota yang baru bergabung tetap melihatnya.
- **Status diri**: Aman / Butuh bantuan / Terluka. Status ini tampil di bawah foto tiap anggota bersama **level baterai**.
- **Kompas**: radar yang menunjukkan posisi tiap anggota sesuai arah aslinya dan ikut berputar saat HP diputar. GPS bekerja tanpa internet.
- **Peringatan anggota terputus**: kalau ada HP yang keluar jangkauan atau mati, semua anggota tahu dalam sekitar 10 detik lewat getar, bunyi, dan banner.

**Kenyamanan**
- Tetap tersambung saat layar mati. SOS, pesan, dan peringatan muncul sebagai notifikasi di layar kunci.
- Foto profil dan nama tampil di daftar anggota dan saat seseorang bicara.
- Tombol **Bagikan lokasi** di menu profil, default nyala. Saat dimatikan, GPS ikut berhenti supaya hemat baterai.
- Ukuran APK sekitar 200 KB dan berjalan di Android 6.0 ke atas.

## Cara pakai

1. Pasang APK di setiap HP. APK debug tersedia di tab **Actions** → build terbaru → artefak `saling-jaga-debug-apk`.
2. Nyalakan **Wi‑Fi** dan **Lokasi** di setiap HP. Wi‑Fi tidak perlu tersambung ke jaringan mana pun dan tidak perlu menyalakan hotspot. Data seluler boleh mati.
3. Buka aplikasi, lalu beri izin Lokasi/Perangkat sekitar, Mikrofon, dan Notifikasi.
4. Ketuk ikon cari di semua HP. Ketuk nama HP lain, lalu HP tersebut menekan **Terima** pada undangan.
5. Setelah status hijau "Terhubung", tahan tombol mikrofon untuk bicara, atau gunakan chip SOS / Kompas / Status / Pengumuman.

Jangkauan Wi‑Fi Direct biasanya 50–100 m di area terbuka dan lebih pendek di dalam gedung. Kunci GPS pertama tanpa internet bisa butuh 1–3 menit di tempat terbuka.

## Cara kerja

```
 HP anggota ──┐
 HP anggota ──┼── Wi‑Fi Direct (WPA2) ── HP pemilik grup ── meneruskan ke semua
 HP anggota ──┘                           (server TCP di 192.168.49.1:8988)
```

- **Penemuan dan koneksi**: memakai `WifiP2pManager` bawaan Android, tanpa library pihak ketiga.
- **Protokol**: frame biner sederhana di atas TCP, berisi `tipe · pengirim · panjang · isi`. Tipenya `HELLO` (nama + foto), `TEXT`, `AUDIO` (PCM 16 kHz mono, potongan 20 ms), `TALK_START/END`, `PRESENCE` (status, baterai, lokasi), `ANNOUNCE`, `LEAVE`, `PING`, dan `REJECT`.
- **Relay**: pemilik grup meneruskan setiap frame ke anggota lain. Ia juga mengirim ulang daftar anggota dan status terakhir, termasuk SOS yang masih aktif, ke HP yang baru bergabung.
- **Deteksi putus**: setiap koneksi mengirim `PING` tiap 3 detik. Koneksi yang diam lebih dari 10 detik dianggap putus.
- **Latar belakang**: foreground service menahan CPU dan Wi‑Fi tetap aktif selama berada di grup.

## Keamanan

Yang sudah diterapkan:
- Server hanya mendengarkan di alamat grup Wi‑Fi Direct, sehingga perangkat di Wi‑Fi kafe atau hotspot yang sama tidak bisa masuk.
- Lalu lintas di udara terenkripsi WPA2 milik Wi‑Fi Direct, dan anggota harus disetujui lewat undangan.
- Pemilik grup menolak frame yang mengaku dari anggota lain, sehingga tidak ada SOS atau pengumuman palsu atas nama orang lain. Nama ganda dalam satu grup juga ditolak.
- Ukuran setiap jenis pesan dan dimensi foto dibatasi, sehingga satu HP tidak bisa membuat HP lain crash.

Belum ada (direncanakan):
- **Identitas terverifikasi**: kunci digital per HP, kode pendek `SJ-XXXX-XXXX` + QR untuk pendataan, dan label "Posko terverifikasi" untuk pengumuman resmi.
- **Enkripsi ujung ke ujung** dengan kunci grup.
- Kunci rilis permanen. Saat ini APK masih ditandatangani dengan kunci debug.

## Batasan saat ini

- Jika aplikasi ditutup paksa (diusap dari daftar aplikasi terbaru), koneksi grup ikut berhenti.
- Suara dikirim mentah sekitar 256 kbps. Cukup untuk Wi‑Fi Direct, tapi belum hemat baterai.
- Kebanyakan HP hanya bisa ikut satu grup Wi‑Fi Direct dalam satu waktu, sehingga belum ada multihop.
- Riwayat chat belum disimpan, dan belum ada sambung ulang otomatis.

## Peta jalan

1. Uji lapangan di 2–5 HP dari berbagai merek.
2. Identitas terverifikasi dan enkripsi.
3. Multihop untuk pesan, SOS, lokasi, dan voice note.
4. Integrasi LoRa (perangkat Meshtastic lewat Bluetooth) untuk jangkauan 2–10 km.
5. Peta offline, absen/hitung kepala, senter strobo SOS, kartu medis, mode hemat baterai, dan panduan P3K.
6. Dasbor posko berbasis web di laptop yang membaca alat LoRa lewat USB/Bluetooth.
7. Rilis di Google Play dengan verifikasi developer Android.

## Build

Aplikasi ini sengaja dibangun **tanpa Gradle/AGP dan tanpa dependensi**, cukup dengan paket Android SDK dari Ubuntu/Debian:

```bash
sudo apt-get install android-sdk-platform-23 aapt dalvik-exchange apksigner zipalign
./build.sh            # hasil: build/saling-jaga-debug.apk
```

Kodenya ditulis dalam Java 8 dengan UI yang dibangun langsung di kode (tanpa XML layout dan tanpa AndroidX). APK dikompilasi terhadap API 23 dengan `targetSdkVersion` 33. API Android 8+ seperti notification channel dipanggil lewat reflection.

## Pengujian

```bash
./build.sh
cd tools/screenshot-tests && gradle test
```

- `HubTest` menjalankan tiga hub di atas socket sungguhan. Yang diuji: relay pesan, teks, dan suara; pengiriman ulang SOS ke anggota baru; penolakan penyamaran; batas ukuran pesan; penolakan nama ganda; dan deteksi anggota keluar.
- `ScreenshotTest` merender layar aplikasi dengan Robolectric (native graphics) ke `tools/screenshot-tests/out/`. Gambar di README berasal dari tes ini.

GitHub Actions (`.github/workflows/build.yml`) menjalankan build dan kedua tes di setiap push, lalu mengunggah APK dan screenshot sebagai artefak.

## Struktur proyek

```
app/src/main/
  AndroidManifest.xml
  java/com/salingjaga/app/
    MainActivity.java      UI, Wi‑Fi Direct, mode bencana, kompas
    Hub.java               grup: server/klien, relay, anggota, anti-penyamaran
    Link.java              satu koneksi TCP: thread baca/tulis, ping, batas ukuran
    Frame.java             tipe pesan dan batas ukurannya
    Presence.java          status, baterai, lokasi
    Voice.java             rekam & putar suara (AudioRecord/AudioTrack)
    RadarView.java         radar kompas
    Avatars.java           foto profil bulat / inisial
    Alerts.java            notifikasi sistem
    KeepAliveService.java  tetap tersambung saat layar mati
  res/                     ikon, logo, splash, ikon vektor
build.sh                   build APK tanpa Gradle
tools/screenshot-tests/    tes protokol + render screenshot
docs/                      logo, splash, screenshot
```

## Model keberlanjutan

- **Warga**: gratis selamanya.
- **Edisi Tim / Posko** (berbayar): dasbor koordinator, peta offline, absen massal, laporan pasca-kejadian, banyak grup, dan kanal terenkripsi.
- **Institusi**: BPBD, Basarnas, PMI, sekolah, dan perusahaan tambang/migas, berupa lisensi, kustomisasi, dan pelatihan.
- **Perangkat keras**: paket node LoRa untuk posko dan desa rawan bencana.

## Lisensi

Belum ditentukan.
