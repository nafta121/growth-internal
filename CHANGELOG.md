# Changelog

Semua perubahan penting pada proyek **Growth Internal** didokumentasikan di berkas ini.

## [0.13.0] - 2026-10-28

### Ditambahkan & Dioptimalkan (Arsitektur Multi-Transport Seamless Offline)
- **Abstraksi Transport Agnistik & Kompresi Suara 4:1 IMA ADPCM (`Link`, `Hub`, `Adpcm`, `Frame`)**:
  - Memisahkan `Link` dari ketergantungan eksklusif `java.net.Socket` menjadi berbasis `InputStream`, `OutputStream`, dan `Closeable` sehingga protokol `Frame` yang sama berjalan identik di atas Wi-Fi Direct TCP, Hotspot/LAN TCP, maupun Bluetooth Classic RFCOMM.
  - Menambahkan codec suara **IMA ADPCM 4:1** (`Adpcm.java`, `Frame.AUDIO_ADPCM = 15`) yang secara transparan memampatkan aliran suara *Push-to-Talk* dari `256 kbps` (640 byte/20 ms) menjadi `64 kbps` (160 byte/20 ms) pada kanal *low-bandwidth* (Bluetooth RFCOMM) dan mendekodenya kembali di `Hub` untuk diteruskan lintas transport.
- **Penemuan & Koneksi Otomatis Hotspot / Wi-Fi Lokal (`Lan`)**:
  - Menambahkan penemuan perangkat *zero-configuration* melalui paket *broadcast* UDP port `8989` (`GI1`) pada jaringan Hotspot HP atau router Wi-Fi lokal tanpa internet, sehingga seluruh anggota Tim di satu Hotspot dapat langsung bergabung ke `Hub` TCP port `8988` tanpa dialog undangan sistem.
- **Transport Bluetooth Classic RFCOMM (`Bt`)**:
  - Menambahkan server & klien soket RFCOMM tanpa *pairing* paksa (`listenUsingInsecureRfcommWithServiceRecord` / `createInsecureRfcommSocketToServiceRecord`) agar HP Tim dapat saling berbicara (*Push-to-Talk*), berkirim teks, status, dan foto meskipun Wi-Fi Direct sedang sibuk atau tidak didukung perangkat.
- **Beacon Darurat & Kehadiran via Bluetooth Low Energy (`Ble`)**:
  - Menambahkan pemindaian dan pemancaran *Service Data* BLE 20-byte hemat daya yang membawa nama singkat, status `Presence` (termasuk **PRIORITAS/SOS**), persentase baterai, dan koordinat GPS (`lat`/`lon`) secara *connectionless*, sehingga sinyal **PRIORITAS** dari rekan Tim di sekitar langsung memicu dering peringatan, arah kompas, dan titik peta offline bahkan sebelum koneksi soket terbentuk.
- **Jembatan Lintas Transport & Pengujian Otomatis (`MainActivity`, `AdpcmTest`)**:
  - Menggabungkan daftar perangkat Wi-Fi Direct, Hotspot/Wi-Fi Lokal, dan Bluetooth dalam satu antarmuka terpadu di `MainActivity` serta memungkinkan pemilik grup (`Hub`) menjembatani anggota Wi-Fi Direct, Hotspot, dan Bluetooth dalam satu saluran pembicaraan yang sama.
  - Menambahkan suite pengujian unit dan integrasi `AdpcmTest` untuk verifikasi *round-trip* codec ADPCM 4:1, pemaketan beacon BLE, paket penemuan LAN, dan *relay* lintas transport TCP <-> Bluetooth.

## [0.12.0] - 2026-10-28

### Diperbaiki & Dioptimalkan (Android Skills Audit)
- **Eliminasi Risiko ANR pada Main Thread (`MainActivity`)**:
  - Memindahkan pemrosesan foto profil (`Avatars.fromUri`) pada `onActivityResult` (`REQ_PHOTO`) ke thread latar `"avatar-prepare"`.
  - Memindahkan decode gambar chat (`Photos.decode(jpeg, 720)`) dan tampilan layar penuh (`Photos.decode(jpeg, 2048)`) ke thread latar sebelum memperbarui UI.
  - Memindahkan ekstraksi thumbnail video (`ThumbnailUtils.createVideoThumbnail`) pada pengiriman dan penerimaan video ke thread latar.
  - Memindahkan penghapusan berkas video sementara (`clearClips`) saat `onCreate` dan penghitungan jumlah ubin peta (`Tiles.count`) saat `refreshMapStatus` ke thread latar.
- **Optimasi Alokasi Memori Render Peta (`MapView`)**:
  - Mengganti alokasi objek `new Rect(...)` dan `new RectF(...)` berulang di dalam `onDraw()`, `drawTile()`, dan `drawTarget()` dengan field `src` dan `dst` yang dialokasikan satu kali.
- **Keamanan Intent, Izin Runtime, & Kepatuhan Kebijakan (`MainActivity`, `KeepAliveService`)**:
  - Mendaftarkan `BroadcastReceiver` Wi-Fi Direct dengan flag `RECEIVER_NOT_EXPORTED` (`0x4`) pada Android 13+ (API 33+) serta menambahkan validasi `intent != null` di `onP2pEvent`.
  - Menunda permintaan izin lokasi & mikrofon hingga pengguna berinteraksi dengan fitur terkait (Cari, Bicara, Kompas, Peta) alih-alih memintanya secara paksa di atas splash screen saat *cold start*.
  - Menambahkan deteksi penolakan izin permanen (`!shouldShowRequestPermissionRationale`) beserta dialog pintasan menuju Pengaturan Aplikasi (`ACTION_APPLICATION_DETAILS_SETTINGS`).
- **Kesiapan Edge-to-Edge (`MainActivity`)**:
  - Menambahkan `OnApplyWindowInsetsListener` pada kontainer utama agar *padding* layar menyesuaikan *system window insets* (status bar, navigation bar, dan keyboard/IME).
- **Pengujian Unit Baru (`PhotosTest`)**:
  - Menambahkan pengujian otomatis untuk cache `Avatars.circle()` dan validasi keamanan `Photos.decode()`.

## [0.11.0] - 2026-10-28

### Diperbaiki & Dioptimalkan (Ponytail Audit)
- **Keamanan Thread Registrasi Anggota (`Hub`)**: Menyatukan pemeriksaan nama duplikat (`nameTaken`) dan pendaftaran anggota (`members.put`) pada frame `HELLO` di dalam satu blok sinkronisasi `synchronized (members)` untuk mencegah *race condition* ketika dua perangkat bergabung bersamaan dengan nama yang sama.
- **Penanganan Putus Koneksi Sebelum Handshake (`Hub`)**: Melaporkan kegagalan sambungan (`cb.onRejected("Koneksi ke pemilik grup terputus")`) apabila soket ditutup oleh pemilik grup sebelum pertukaran `HELLO` selesai (misalnya saat grup penuh `MAX_LINKS = 16`), sehingga antarmuka klien tidak menggantung pada status *"Menyambung ke pemilik grup..."*.
- **Manajemen Memori & Sinkronisasi Audio (`Voice`, `MainActivity`)**:
  - Menambahkan sinkronisasi pembuatan `AudioTrack` per pengirim di `Voice.play()` serta validasi `AudioTrack.STATE_INITIALIZED` sebelum memanggil `play()`.
  - Menambahkan `Voice.drop(String from)` yang dipanggil otomatis dari `MainActivity.onMemberLeft()` agar `AudioTrack` anggota yang keluar grup langsung dihentikan dan dibebaskan dari memori native.
- **Optimasi Render Kompas/Radar (`RadarView`)**: Menghilangkan alokasi objek `Bitmap.createScaledBitmap` pada setiap *frame* `onDraw()` dengan menggambar langsung ke `RectF` yang telah dialokasikan sebelumnya.
- **Deduplikasi & Caching Avatar (`Avatars`)**:
  - Menyederhanakan `Avatars.fromUri()` agar menggunakan `Photos.decode(Photos.prepare(...))` sehingga orientasi EXIF kamera otomatis dikoreksi dan pengecekan `InputStream == null` terjamin.
  - Menambahkan `LruCache<String, Bitmap>` pada `Avatars.circle()` agar pembaruan daftar anggota saat *push-to-talk* tidak mendekode ulang JPEG avatar di *UI thread*.
- **Kapasitas Cache Peta Offline (`MapView`)**: Menaikkan kapasitas `LruCache` ubin peta dari `48` menjadi `120` ubin untuk mencegah *cache thrashing* saat *zoom* dan geser peta pada layar beresolusi tinggi.
- **Pembersihan Skrip Usang**: Menghapus berkas `tools/render-social.sh` dan `tools/social-card.html` yang tidak lagi digunakan oleh pipeline aset.

## [0.10.0] - 2026-10-28

### Ditambahkan & Diubah
- **Rebranding Penuh ke Growth Internal (`com.growthinternal.app`)**:
  - Mengubah identitas aplikasi menjadi **Growth Internal** dengan palet warna resmi Navy (`#131F37`) dan Crimson Red (`#D61E20`) beserta ikon adaptif, splash screen, kartu sosial, dan poster peluncuran baru.
- **Terminologi Operasional Outbound & Training Tim Growth Indonesia**:
  - Mengganti istilah kebencanaan/darurat/SOS menjadi terminologi lapangan Outbound & Training: **Tim**, **PIC**, **Basecamp**, **Standby**, **Butuh Backup**, **Prioritas**, **Instruksi**, **Kendala**, dan **supporting/backup**.
  - Memperbarui kanal notifikasi, format SMS Koordinasi Lapangan, simulasi grup di pengaturan, dokumentasi (`README.md`, `docs/index.html`), dan seluruh 18 tangkapan layar otomatis.
