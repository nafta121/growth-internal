# Changelog

Semua perubahan penting pada proyek **Growth Internal** didokumentasikan di berkas ini.

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
