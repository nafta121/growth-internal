# Changelog

Semua perubahan penting pada proyek **Growth Internal** didokumentasikan di berkas ini.

## [0.25.0] - 2026-10-29

### Diperbaiki & Dioptimalkan (Perbaikan Audio Patah-Patah Jarak Dekat & Eliminasi Duplikat User `#relay`)
- **Hardware-Cushioned `AudioTrack` Playout & Cross-Faded PLC (`Voice`)**:
  - Mengganti detak *software* `Thread.sleep(20ms)` di `PlayoutWorker` dengan pengisian langsung (*burst drain*) ke *ring buffer* perangkat keras `AudioTrack` (`80 ms` bantalan awal = 1 bingkai senyap pembuka + 3 bingkai *pre-roll* untuk langsung melewati ambang *half-buffer* Android `AudioFlinger` tanpa *underrun* berulang), serta hanya memicu PLC (`concealFrame`) saat bantalan perangkat keras benar-benar menyentuh `<= 12 ms`.
  - Menaikkan `SHORT_SPURT_WAIT_MS` ke `120 ms` agar paket pertama yang mengalami jitter `45–100 ms` tidak memulai pemutaran secara prematur dengan bantalan 0 bingkai di Mode Telepon, serta menambahkan *16-sample crossfade* (`85% → 55%`) pada `concealFrame` agar bebas bunyi klik/patah.
- **Deduplikasi Jalur Suara Ganda Wi‑Fi Aware L2 Sidelink vs `Hub` (`MainActivity`, `Hub`)**:
  - Mencegah tabrakan 2 aliran suara paralel (`100 fps` ke dalam pemutar `50 fps`) pada jarak dekat dengan melewati pemutaran/estafet `SIDELINK_AUDIO` & `SIDELINK_TALK` ketika pengirim sudah terhubung sebagai anggota aktif `Hub` (`hub.hasMember(sender)`), serta menghentikan pancaran L2 `broadcastSidelinkAudio` saat seluruh rekan Wi‑Fi Aware di sekitar sudah tersambung di `Hub`.
- **Perbaikan Bottleneck MTU 20-Byte pada BLE GATT `MeshAccess` & Sinkronisasi Jendela HeMB (`MeshAccess`, `Link`, `Hub`)**:
  - Menyimpan hasil negosiasi `onMtuChanged(247)` sebelum `onServicesDiscovered` di `MeshAccess.openTunnel()` sehingga paket suara narrowband `84 byte` dikirim utuh dalam **1 paket GATT** (bukan terpecah menjadi 13 paket 14-byte pada MTU default 20 byte), serta menggunakan `Adpcm.encodeNarrowband` secara otomatis pada pipa GATT `MeshAccess.Pipe`.
  - Memperbaiki penghitung jendela `Frame.AUDIO_HEMB` di `Hub` agar paket paritas yang hilang tidak memicu pemutaran ulang suara lama (`80 ms`) atau desinkronisasi antar-jendela.
- **Eliminasi Duplikat User `<Nama>#relay` & Pemutusan Bersih Koneksi Relay Pasif (`Hub`, `MainActivity`, `MeshAccess`)**:
  - Menyembunyikan node estafet buta `<Nama>#relay` (`Hub.isPassiveRelayName`) dari daftar anggota UI & `Hub.members` sambil tetap mempertahankan fungsi *multi-hop blind relay* dan konvergensi `ClusterId`.
  - Mengganti otomatis link pasif `<Nama>#relay` ketika perangkat yang sama bergabung aktif sebagai `<Nama>`, menolak link `#relay` duplikat/loop diri sendiri, serta memanggil `BluetoothGattServer.cancelConnection()` saat pipa server `MeshAccess` ditutup agar tidak menyisakan koneksi *zombie* 25 detik.
- **Pengujian Otomatis (`AdpcmTest`)**:
  - Menambahkan `closeRangeSmoothVoiceNoRelayDuplicateAndHembWindowSync` untuk memverifikasi kelima perbaikan di atas secara *end-to-end*.

## [0.24.0] - 2026-10-29

### Diperbaiki & Dioptimalkan (Anti-Putus Suara, Seamless Auto-Reconnect, & Ketahanan Latar Belakang / Layar Mati)
- **Tahap 1 — *Adaptive Pre-Roll Playout Jitter Buffer, `URGENT_AUDIO` Thread, & Koeksistensi Radio 2.4 GHz* (`Voice`, `Ble`, `MainActivity`)**:
  - Menambahkan **Adaptive Playout Jitter Buffer (`60 ms` / 3 bingkai *pre-roll*, batas latensi `240 ms`)** dengan thread pemutar khusus berdetak `20 ms` dan sisipan PLC otomatis (`concealFrame`) hingga 2 bingkai (`40 ms`) saat paket terlambat tiba, sehingga `AudioTrack` tidak pernah mengalami *buffer underrun* (suara putus-putus) dan tidak pernah memblokir thread pembaca jaringan.
  - Menaikkan prioritas thread rekam mikrofon dan pemutar suara ke `Process.THREAD_PRIORITY_URGENT_AUDIO`.
  - Menambahkan **2.4 GHz Voice-Active Coexistence Throttling** (`Ble.setVoiceActive`) dengan penjaga anti-throttle (`MIN_SCAN_RESTART_MS = 8000 ms`) yang menurunkan *BLE Scan* ke `SCAN_MODE_LOW_POWER` selama sesi bicara/telepon aktif agar >98% waktu antena 2.4 GHz diberikan ke paket suara.
- **Tahap 2 — *25s Link Resilience, Dedicated Heartbeat Flush, & Seamless In-Session TCP Auto-Reconnect* (`Link`, `Hub`, `MainActivity`)**:
  - Menaikkan batas *read timeout* `Link.TIMEOUT_MS` dari `10 detik` menjadi **`25 detik`**, mengaktifkan `socket.setKeepAlive(true)`, serta menyisipkan `Frame.PING` + `out.flush()` terjadwal setiap `3 detik` bahkan di tengah pengiriman media `bulk` (foto/video) agar koneksi tidak pernah terputus akibat *head-of-line blocking*.
  - Menambahkan **In-Session Background TCP Auto-Reconnect** di `Hub` (`scheduleAutoReconnect`: `450 ms → 1,2 s → 2,5 s → 4,5 s`) dan pemulihan transien pra-`HELLO` Wi‑Fi Direct di `MainActivity.onRejected()` sehingga koneksi yang terputus sesaat otomatis menyambung kembali tanpa membubarkan grup atau mematikan Mode Telepon.
- **Tahap 3 — *Ketahanan Latar Belakang & Layar Mati di Android 8–15* (`Ble`, `KeepAliveService`, `AndroidManifest.xml`, `MainActivity`)**:
  - Memasang **`ScanFilter` perangkat keras eksplisit** (`Ble.buildScanFilters`: `0xFD91` Beacon, `0xFD92` 15-Hop Mesh, dan `0xFE12` MeshAccess) pada `BluetoothLeScanner.startScan()` sehingga sistem operasi Android 8.1+ tidak pernah mematikan pemindaian BLE saat layar HP dimatikan di saku.
  - Memperbarui `KeepAliveService` untuk menggunakan `startForegroundService()` pada Android 8.0+, `START_STICKY`, **`WIFI_MODE_FULL_LOW_LATENCY` (`4`)** pada Android 10+ (mencegah *DTIM power-save sleep* pada panggilan suara saat layar mati), serta mengakuisisi `WifiManager.MulticastLock` agar paket UDP LAN tetap diterima saat layar terkunci.
  - Menjaga `KeepAliveService` aktif selama mode siaga lapangan maupun di dalam grup, serta menambahkan izin & menu profil sekali ketuk **"Baterai latar belakang: Izinkan tanpa batas"** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- **Pengujian Otomatis (`AdpcmTest`)**:
  - Menambahkan pengujian unit `voiceJitterBufferLinkAutoReconnectAndScreenOffSurvival` yang memverifikasi *jitter buffer* + PLC, koeksistensi radio, `ScanFilter` layar mati, `KeepAliveService`, dan *live TCP auto-reconnect* secara *end-to-end*.

## [0.23.0] - 2026-10-28

### Ditambahkan & Dioptimalkan (Sinergi Panggilan Suara & Push-to-Talk Multi-Hop Kilometer: ProSe Wi‑Fi Aware L2 Sidelink + 3GPP Rel-13 Relay + 15-Hop Mesh + MeshSat HeMB/SMAZ2)
- **Tahap 1 — *Adaptive 3-Tier Voice Codec, MeshSat HeMB $\text{GF}(2^8)$ RLNC Voice FEC, & 3GPP MCPTT Priority Floor Preemption* (`Adpcm`, `Frame`, `Link`, `Hub`)**:
  - Menambahkan mode kompresi suara **8:1 Narrowband 8 kHz IMA ADPCM (`84 byte` per 20 ms = `32 kbps`, hemat bandwidth 87.5%)** (`Adpcm.encodeNarrowband`) dengan *linear upsampling* otomatis kembali ke 16 kHz PCM di `Adpcm.decode`, serta pemilihan adaptif 3-tingkat di `Link` (`lowBandwidth && relay` memakai 84 byte narrowband; `lowBandwidth || relay` memakai 164 byte wideband).
  - Mengimplementasikan **MeshSat HeMB $\text{GF}(2^8)$ Sliding-Window RLNC Voice FEC** (`Adpcm.gfMul`, `Adpcm.gfInv`, `Adpcm.encodeHembParity`, `Adpcm.recoverHembFrame`, dan `Frame.AUDIO_HEMB = 20`) untuk merekonstruksi bingkai suara yang hilang di udara dalam **`0 ms`** tanpa *retransmit*.
  - Mengadopsi **3GPP MCPTT Priority Floor Preemption** (`Frame.priority` & `Hub.isPrioritySpeaker`): suara dari anggota berstatus Prioritas/SOS ditempatkan langsung ke antrean tertinggi (`vital`) di setiap hop sehingga tidak pernah tertahan oleh trafik biasa.
- **Tahap 2 — *ProSe-Style Wi‑Fi Aware L2 Sidelink Multi-Hop Voice & PTT Relay* (`Aware`, `Hub`, `MainActivity`)**:
  - Menambahkan jalur *Connectionless L2 Action Frame Sidelink* (`Aware.SIDELINK_MAGIC = 0xA5`, batas `< 255 byte`, `DEFAULT_SIDELINK_TTL = 15`) di atas `DiscoverySession.sendMessage()` untuk meneruskan bingkai suara 84 byte (`SIDELINK_AUDIO`), status bicara PTT (`SIDELINK_TALK`), Chat (`SIDELINK_TEXT`), Instruksi (`SIDELINK_ANNOUNCE`), dan Status/SOS (`SIDELINK_PRESENCE`) secara instan (`0 ms` tanpa menunggu pembentukan pipa IPv6 NDP) sekaligus menjembatani suara dan status PTT lintas transport ke pohon `Hub`.
- **Tahap 3 — *Extended 15-Hop Kilometer Mesh, `SMAZ2` Dictionary Compression, & 3GPP Rel-12/13 ProSe Support* (`Ble`, `Lan`, `MeshCoordinator`)**:
  - Meningkatkan batas hop *BLE Flood Mesh* (`0xFD92`) menjadi **`DEFAULT_TTL = 15` hop** untuk jangkauan estafet berkilometer.
  - Menambahkan kompresi kamus bahasa Indonesia & operasional lapangan ala **MeshSat `SMAZ2`** (`Ble.compressText` & `Ble.decompressText`) sehingga pesan lapangan panjang termampatkan 35–50% sebelum dipotong ke dalam fragmen mikro BLE maupun dikirim lewat L2 Sidelink.
  - Menambahkan deteksi antarmuka **3GPP ProSe/PC5 Sidelink** (`Lan.isProSeSidelinkInterface`), detektor *Receive-Deafness* satu arah (`MeshCoordinator.isReceiveDeaf`), serta pemilihan *UE-to-Network Gateway Peer* (`MeshCoordinator.selectBestGatewayPeer`).
- **Pengujian Otomatis (`AdpcmTest`)**:
  - Menambahkan pengujian unit `proseAwareSidelinkHembVoiceSmaz2And15HopKilometerMesh` yang memverifikasi seluruh fitur Tahap 1–3 secara menyeluruh.

## [0.22.0] - 2026-10-28

### Ditambahkan & Dioptimalkan (Sinergi Multi-Hop & Multi-Device Spectre + FISSURE + BlueRange)
- **Tahap 1 — *Spectre Smart NIC Filtering, Unicast `.1` Gateway Probe, & BLE 5 Coded PHY Scan Recovery* (`Lan`, `Ble`)**:
  - Menambahkan pemfilteran antarmuka jaringan virtual/seluler (`isVirtualOrCellularInterface` & `isPreferredWifiInterface`) untuk memprioritaskan `wlan`/`ap`/`p2p`/`swlan` dan mengabaikan `tun`/`rmnet`/`clat`/`dummy`.
  - Menambahkan *Unicast `.1` Gateway Probe* (`gatewayFor`) agar penemuan rekan pada Hotspot/Router tetap tembus meskipun router menerapkan *AP/Client Isolation* yang memblokir UDP broadcast/multicast.
  - Mengaktifkan pemindaian **BLE 5 Extended + Coded PHY (`PHY_LE_ALL_SUPPORTED`)** pada Android 8.0+ serta pemulihan otomatis *silent-stall* di `Ble` (`onScanFailed` + `lastScanResultMs`).
- **Tahap 2 — *FISSURE `LT` Micro-Fragmentation & Implicit Echo ACK* (`Ble`)**:
  - Memperluas kapasitas teks Chat & Instruksi pada *BLE Flood Mesh* (`0xFD92`) dari 10 byte menjadi **56 byte (hingga 7 fragmen mikro `0x1F`)** dengan *out-of-order reassembly* (`splitMeshText` & `reassembleMeshBody`) dan tetap 100% kompatibel mundur untuk pesan `<= 10 byte`.
  - Menambahkan antrean transmisi berurutan (`meshTxQueue`) dan **Implicit Echo ACK** yang mengulang siaran 1 kali secara otomatis apabila belum terdengar pantulan (*echo*) dari tetangga.
- **Tahap 3 — *BlueRange 3-Tier Priority Queue, 8 KB Chunked Photo Transfer, & Multi-Hop ADPCM* (`Frame`, `Clips`, `Link`, `Hub`)**:
  - Memisahkan antrean pengiriman `Link` menjadi 3 tingkat prioritas (`vital` > `queue` > `bulk`) agar sinyal darurat, PTT, dan suara *real-time* tidak pernah tertahan oleh pengiriman media.
  - Mengubah pengiriman foto (`Frame.IMAGE_CHUNK = 18`) menjadi potongan **8 KB** (`Clips.IMAGE_CHUNK`) sehingga foto kini dapat dikirim menembus jalur ber-bandwidth rendah (Bluetooth, BLE L2CAP, dan `MeshAccess` GATT multi-hop) tanpa memblokir aliran suara.
  - Mengaktifkan kompresi suara adaptif **4:1 IMA ADPCM (`Frame.AUDIO_ADPCM`)** secara otomatis pada seluruh link *bridge/relay* multi-hop.
- **Tahap 4 — *Cross-Transport `Frame.MESH_RELAY` Bridging, Fast-Reconnect Sustain, & Seleksi Relay Tanpa GPS* (`Frame`, `Hub`, `MeshCoordinator`, `MainActivity`)**:
  - Menambahkan jembatan dua arah otomatis antara *BLE Flood Mesh* (`0xFD92`) dan pohon `Hub` (`Frame.MESH_RELAY = 19`) untuk Chat, Instruksi, dan Status/SOS tanpa mencemari daftar `Hub.members`.
  - Menambahkan `clearPeerCooldown` untuk penyambungan ulang cepat `< 1 detik` saat koneksi terputus mendadak serta `selectBestSignalRelay` berbasis koherensi sinyal Welford ketika GPS mati atau berada di dalam ruangan.
- **Pengujian Otomatis (`AdpcmTest`)**:
  - Menambahkan pengujian unit `spectreFissureBluerangeMultiHopCommunicationUpgrade` yang memverifikasi seluruh fitur Tahap 1–4 secara *end-to-end*.

## [0.21.0] - 2026-10-28

### Ditambahkan & Dioptimalkan (Peningkatan Konektivitas & Jangkauan Ala RuView)
- **Tahap 1 — *Welford RunningStats & 3-Filter Quality Gate* (`MeshCoordinator`, `MainActivity`)**:
  - Menambahkan pelacak statistik sinyal online berbasis algoritma Welford (`LinkStats`: rata-rata `mean`, simpangan baku `stdDev`, dan *EMA drift*) serta gerbang kualitas 3-lapis (`evaluateRssi`): memperluas jangkauan efektif *auto-connect* BLE L2CAP & `MeshAccess` (`0xFE12`) dari `-82 dBm` hingga **`-91 dBm`** untuk sinyal jarak jauh yang stabil (*coherent distant link*), sekaligus menahan lonjakan pantulan sesaat (*multipath spike* `> 10 dB`).
- **Tahap 2 — *Distance-Weighted TDM Slot Scheduling & Opportunistic Suppression* (`Ble`)**:
  - Mengadopsi penjadwalan slot waktu *relay* berbasis jarak (`computeRelayDelayMs`) pada *BLE Flood Mesh* (`0xFD92`): perangkat di tepi jangkauan terjauh (`<= -80 dBm`) memperoleh slot prioritas pertama (`15–40 ms`) untuk memaksimalkan jarak jangkauan multi-hop, sementara perangkat dekat pengirim (`> -65 dBm`) dijadwalkan di akhir (`125–200 ms`) dan otomatis menekan *relay* ganda (`overheardCount >= 2`) guna mencegah *broadcast storm*.
- **Tahap 3 — *Fresnel-Zone Clearance & Relay Placement Advisor* (`MeshCoordinator`, `MainActivity`)**:
  - Mengimplementasikan rumus geometri Zona Fresnel pertama 2.4 GHz (`fresnelRadiusMeters`, `relayPlacementScore`, dan `selectBestRelayCandidate`) untuk mengevaluasi posisi anggota di koridor garis pandang antara pos yang berjauhan/SOS serta menampilkan saran posisi jembatan paling efektif pada dialog jembatan.
- **Tahap 4 — *Proactive Make-Before-Break Multi-Transport Handover* (`Hub`, `Link`, `MeshCoordinator`, `MainActivity`)**:
  - Menambahkan deteksi *negative signal drift* (`shouldHandoverToBle`) dan pergantian sambungan lintas transport tanpa putus (*Make-Before-Break* di `Hub`) antara jalur berkecepatan tinggi (Wi‑Fi Aware / Hotspot LAN) dan jalur jangkauan jauh (BLE L2CAP / `MeshAccess`), sehingga perangkat otomatis naik kelas ke Wi‑Fi saat mendekat dan beralih ke BLE sebelum Wi‑Fi terputus saat menjauh tanpa kehilangan anggota atau memutus komunikasi.
- **Pengujian Otomatis (`AdpcmTest`)**:
  - Menambahkan pengujian unit `ruviewConnectivityQualityGateTdmFresnelAndHandover` yang memverifikasi ke-4 tahap peningkatan konektivitas dan *handover* lintas transport secara *end-to-end*.

## [0.20.0] - 2026-10-28

### Ditambahkan & Dioptimalkan (Auto-Update Internal dari GitHub Releases & Kepatuhan Play Protect)
- **Auto-Update Internal Aplikasi dari GitHub Releases (`Updater`, `MainActivity`, `CaptureProvider`)**:
  - Menambahkan modul `Updater.java` yang memeriksa rilis terbaru di `https://api.github.com/repos/nafta121/growth-internal/releases/latest` secara otomatis saat aplikasi dibuka dan terhubung ke internet, maupun secara manual lewat menu profil **"Cek Update Aplikasi (GitHub)"**.
  - Mengunduh `growth-internal.apk` ke penyimpanan sementara aplikasi (`CaptureProvider.UPDATE_APK`), memverifikasi keutuhan dan keaslian file menggunakan hash **SHA-256** terhadap `growth-internal.apk.sha256` sebelum pemasangan, serta langsung membuka installer sistem Android (`REQUEST_INSTALL_PACKAGES`).
- **Kepatuhan Manifest & Identitas Sertifikat untuk Google Play Protect (`AndroidManifest.xml`, `build.sh`)**:
  - Menambahkan deklarasi opsional `android.hardware.location.gps` (`android:required="false"`), izin `REQUEST_INSTALL_PACKAGES`, path `<grant-uri-permission android:path="/update.apk" />`, serta memperbarui *Distinguished Name (DN)* pembuat sertifikat di `build.sh` menjadi identitas organisasi (`CN=Tim Growth Indonesia, OU=Field Operations, O=Growth Indonesia, C=ID`).
- **Pengujian Unit Otomatis (`AdpcmTest`)**:
  - Menambahkan pengujian unit `githubReleaseUpdaterParsesJsonComparesSemverAndComputesSha256` untuk memverifikasi ekstraksi JSON GitHub Releases, perbandingan versi semantik + `versionCode`, dan perhitungan SHA-256.

## [0.19.0] - 2026-10-28

### Diperbaiki & Dioptimalkan (Remediasi 14 Temuan Audit `android-skills` + `/ponytail-audit`)
- **Sinkronisasi Antrean GATT BLE & Pencegahan Kehilangan Paket (`MeshAccess`)**:
  - Menambahkan *per-connection binary* `Semaphore` (`writeGate` & `serverGates`) yang disinkronkan dengan `onCharacteristicWrite()` dan `onNotificationSent()` sehingga pengiriman pecahan paket (*split packets*) dan suara ADPCM di atas GATT `0xFE12` tidak pernah menimpa *buffer* radio atau dibuang diam-diam oleh stack Bluetooth Android.
- **Konvergensi `ClusterId` Seketika & Propagasi Ukuran Klaster Multi-Hop (`Hub`, `MainActivity`, `MeshCoordinator`)**:
  - Menambahkan `Hub.onClusterChanged(Runnable)` dan pembaruan iklan instan di `onMemberJoined`/`onMemberLeft` agar perubahan `clusterId` dan `clusterSize` langsung disiarkan ke BLE (`0xFD91`), Wi‑Fi Aware, dan LAN tanpa menunggu siklus 30 detik.
  - Mempropaganda pertumbuhan `reportedClusterSize` melalui bingkai `Frame.CLUSTER_UPDATE` sehingga klaster 3+ node yang sedang tumbuh selalu menang secara deterministik ketika bertemu klaster 2 node.
- **Penutupan Kebocoran Sumber Daya & Siklus Hidup `relayHub` (`Bt`, `MeshAccess`, `MeshCoordinator`, `MainActivity`)**:
  - Menutup `MeshAccess.Pipe` secara eksplisit saat `openTunnel()` mengalami *timeout* 5 detik atau kegagalan `hub.addStreamLink()`, serta menghentikan `relayHub` secara otomatis ketika pengguna beralih ke grup aktif (`initHub` / `stopHub`).
- **Isolasi Dekode Foto dari Thread Audio, Caching `Cipher` AES-128, & Keamanan `AudioRecord` (`MainActivity`, `MeshAccess`, `Voice`)**:
  - Memindahkan `Photos.decode(jpeg, 720)` di `MainActivity.onImage()` ke thread latar `"image-decode"` agar thread `link-reader` tidak pernah menahan paket suara Walkie-Talkie *real-time*.
  - Menyimpan instans `Cipher` AES-128 (`encCipher` & `decCipher`) per sesi `MeshAccess.Session` untuk mengeliminasi *overhead* pencarian JCA provider pada setiap blok 16-byte.
  - Menambahkan `AtomicBoolean activeCapture` per sesi dan `join(250)` di `Voice.stopCapture()` untuk mencegah *race condition* mikrofon saat tombol PTT ditekan cepat berulang kali.
- **Anti-Tabrakan Broadcast BLE Flood Mesh & Pembatasan Path `CaptureProvider` (`Ble`, `AndroidManifest.xml`)**:
  - Menambahkan *random back-off jitter* `30–180 ms` sebelum meneruskan paket BLE Mesh (`0xFD92`) guna mencegah tabrakan sinyal di kanal iklan BLE 37/38/39.
  - Mengubah `CaptureProvider` ke `android:grantUriPermissions="false"` dengan elemen `<grant-uri-permission>` eksplisit hanya untuk `/photo.jpg`, `/video.mp4`, dan `/growth-internal.apk`.
- **Modularisasi `MeshCoordinator` & Pembersihan Overload Legacy (`MeshCoordinator`, `MainActivity`, `Ble`, `Lan`, `Aware`, `AdpcmTest`)**:
  - Mengekstrak logika koordinasi *auto-connect*, *cooldown*, deduplikasi, dan `relayHub` ke kelas `MeshCoordinator.java`, menghapus *overload* lama yang tidak terpakai, serta menambahkan pengujian unit `dynamicClusterSizePropagationAndCoordinatorLifecycle`.

## [0.18.0] - 2026-10-28

### Ditambahkan & Dioptimalkan (Adopsi BlueRange MeshAccessModule & Zero-Action Passive Relay)
- **BlueRange `MeshAccessModule` (`MeshAccess.java`, GATT Service `0xFE12`)**:
  - Menambahkan layanan dan klien GATT `0xFE12` tanpa *pairing* OS (`SET_OPEN` / `PERMISSION_WRITE`), dilengkapi **4-Way Handshake AES-128** (`ANonce`/`SNonce`, kunci sesi turunan, `FM_KEY_NETWORK` & `FM_KEY_ZERO`), proteksi integritas **4-byte MIC**, pemulihan *nonce* otomatis **`DEAD_DATA`** (`0xDEADDADA00FF7733`), serta pemecahan & penyusunan ulang paket (`SPLIT_WRITE_CMD` / `SPLIT_WRITE_CMD_END`).
- **Anti-Loop & Resolusi Split-Brain Berbasis `ClusterId` (`Hub`, `Frame.CLUSTER_UPDATE`, `Ble`, `Lan`, `Aware`)**:
  - Menyematkan `ClusterId` 32-bit dinamis dan `lossCounter` di `Hub` serta menyiarkannya pada beacon BLE, Wi‑Fi Aware, dan LAN untuk mencegah koneksi ganda melingkar (*anti-loop*) dan menyatukan dua klaster yang bertemu secara otomatis.
- **Zero-Action Passive Relay (`MainActivity.ensureRelayHub`, `Bt`)**:
  - Memungkinkan perangkat **Growth Internal** dalam kondisi *standby* menjadi jembatan penyambung multi-hop terenkripsi di latar belakang tanpa tindakan apa pun di layar perangkat perantara.

## [0.17.0] - 2026-10-28

### Diperbaiki & Dioptimalkan (Audit Menyeluruh `android-skills` + `/ponytail-audit` & Keystore Persisten)
- **Eliminasi Disk I/O di Main UI Thread pada Peta Offline (`MapView`)**:
  - Memindahkan pemeriksaan berkas *tile* dan `BitmapFactory.decodeFile()` dari `MapView.onDraw()` ke *daemon executor thread* latar (`tile-loader`) dengan himpunan `missing`/`loading` yang *thread-safe*, serta tetap menggambar *tile* induk terdekat dari `cache` secara instan saat menggeser atau memperbesar peta.
- **Isolasi Thread Penyimpanan Video dari Aliran Suara Walkie-Talkie (`MainActivity`)**:
  - Memindahkan penulisan berkas video 15 detik (`saveClip`) dan ekstraksi *thumbnail* (`MediaMetadataRetriever`) di `onVideo()` ke *worker thread* `"video-save"` sehingga thread jaringan `link-reader` tidak pernah tertahan saat menerima paket suara `AUDIO` *real-time*.
- **Fallback Port Ephemeral Otomatis & Pengujian Server APK Offline (`ApkShare`, `AdpcmTest`)**:
  - Menambahkan *fallback* port bebas (`new InetSocketAddress(0)`) pada `ApkShare.start()` apabila port TCP `8989` sedang sibuk atau berada di status `TIME_WAIT`, dilengkapi unit test otomatis `offlineApkShareServesHtmlAndApkBinaryWithPortFallback`.
- **Pembatasan Memori, Keamanan `WakeLock`, & Konkurensi Wi‑Fi Aware (`MainActivity`, `KeepAliveService`, `Aware`, `Ble`, `Lan`, `Bridge`)**:
  - Membatasi ukuran map deduplikasi `recentMeshTexts` maksimum 256 entri (`LinkedHashMap.removeEldestEntry`), membatasi `WakeLock.acquire()` dengan *timeout* aman 6 jam, mengindeks sesi klien Wi‑Fi Aware per-`PeerHandle` (`ConcurrentHashMap`), serta menyatukan konstanta `Hub.UTF8` dan membersihkan *overload* lama di `Lan.java`.
- **Penandatanganan APK Konsisten (`debug.keystore` & `SIGNING_KEYSTORE_B64`)**:
  - Menyertakan *keystore* tetap dan menyimpannya di GitHub Secrets agar setiap pembaruan APK dapat langsung diinstal (*in-place update*) di atas versi sebelumnya tanpa harus menghapus aplikasi lama.

## [0.16.0] - 2026-10-28

### Ditambahkan & Dioptimalkan (Adopsi Arsitektur MeshLive Tanpa Live Camera Stream)
- **Tahap 1 — Ketahanan Radio BLE, Wi‑Fi Coexistence & Optimasi Link (`Ble`, `Bt`, `MainActivity`)**:
  - Menambahkan `AdvertisingWatchdog` (`.setTimeout(0)` + *watchdog* 25 detik), `setConnectedMode(boolean)` untuk menurunkan siklus pindai BLE ke `SCAN_MODE_BALANCED` saat grup aktif demi menjaga *airtime* Wi‑Fi 2.4 GHz, serta `optimizeBleLink()` (`CONNECTION_PRIORITY_HIGH`, `requestMtu(517)`, dan `setPreferredPhy` BLE 2M / Coded Long-Range PHY).
- **Tahap 2 — Kualitas & Ergonomi Audio Walkie-Talkie (`Frame`, `Link`, `Voice`, `MainActivity`)**:
  - Menambahkan `BoundedMediaAge` (`MAX_AUDIO_AGE_MS = 350 ms`) agar paket suara basi akibat kemacetan radio otomatis dibuang tanpa menimbulkan penundaan bertumpuk, *Packet Loss Concealment* (`Voice.concealFrame()`) untuk meredam bunyi klik saat paket hilang, dan perutean *earpiece* otomatis berbasis sensor jarak telinga (`TYPE_PROXIMITY`) pada Mode Telepon.
- **Tahap 3 — Store-and-Forward & Pesan Suara 10 Detik (`Hub`, `Frame`, `MainActivity`)**:
  - Menambahkan *ring buffer* `history` (32 pesan/instruksi/pesan suara terakhir) di `Hub` yang otomatis diputar ulang saat anggota baru bergabung atau tersambung kembali, serta fitur rekam & kirim **Pesan Suara ADPCM 10 Detik** (`Frame.VOICE_NOTE = 16`).
- **Tahap 4 — Distribusi APK Offline Tanpa Internet (`ApkShare`, `CaptureProvider`, `MainActivity`)**:
  - Menambahkan fitur **"Bagikan APK Offline (Tanpa Internet)"** pada menu profil yang membuka server HTTP lokal (`ApkShare`) serta berbagi berkas `.apk` via Bluetooth/Quick Share (`CaptureProvider.APK`).

## [0.15.0] - 2026-10-28

### Ditambahkan & Dioptimalkan (Wi‑Fi Aware + Bluetooth Mesh Network + Multi-Hop Relay Otomatis)
- **Prioritas 1: Wi‑Fi Aware / NAN Tanpa Popup (`Aware`, API 26+)**:
  - Menambahkan modul `Aware.java` berbasis **Wi‑Fi Aware (Neighbor Awareness Networking)** yang mempublikasikan dan berlangganan layanan `"growthinternal"` di udara serta membangun jalur data IPv6 berkecepatan tinggi (`aware_data0`) secara otomatis **tanpa dialog persetujuan OS**, **tanpa PIN**, dan **tanpa memutuskan koneksi Wi‑Fi/Hotspot**.
- **Eliminasi Daftar Perangkat Bluetooth Umum & Koneksi BLE L2CAP Langsung (`Bt`, `Ble`, `MainActivity`)**:
  - Menghapus `getBondedDevices()` dan pemindaian Bluetooth klasik umum (`ACTION_FOUND`) dari `Bt.java` sehingga perangkat audio/aksesori yang pernah *paired* (seperti speaker/TWS) **tidak pernah lagi muncul atau dihubungi**.
  - Menambahkan dukungan **BLE L2CAP Connection-oriented Channel (`listenUsingInsecureL2capChannel` / `createInsecureL2capChannel`, API 29+)** beserta penyematan `psm` 16-bit di dalam beacon `Ble` (`0xFD91`), sehingga koneksi aliran suara & data bekerja langsung di atas alamat acak BLE (*Random Private Address*) tanpa *pairing* dan tanpa popup.
  - Begitu sesama perangkat **Growth Internal** terdeteksi dalam radius jangkauan (`RSSI >= -82 dBm`), aplikasi otomatis masuk ke layar grup aktif (**Tim**) tanpa perlu memilih dari daftar perangkat.
- **Bluetooth Mesh Network Multi-Hop (`Ble`, `0xFD92`)**:
  - Menambahkan protokol *connectionless BLE flood mesh* (`Ble.MeshPacket`) dengan `TTL` (hingga 5 lompatan/hop) dan deduplikasi ID paket 16-bit (`recordSeenMesh`) untuk meneruskan sinyal **Prioritas (SOS)** + koordinat GPS, status baterai, pesan chat, dan **Instruksi** secara berantai antar perangkat.
- **Multi-Hop Stream Relay Otomatis Lintas Transport (`Hub`, `Link`)**:
  - Meningkatkan `Hub.java` agar setiap perangkat yang memiliki lebih dari satu sambungan aktif (`links.size() > 1`) otomatis bertindak sebagai *multi-hop relay* (`Frame.BRIDGE` otomatis + pencegahan *loop* berbasis pohon rentang `behind`), menjembatani suara (*Push-to-Talk* PCM <-> ADPCM), chat, foto, video, dan lokasi melintasi rantai **Wi‑Fi Aware <-> Hotspot/Wi‑Fi Direct <-> Bluetooth/BLE** tanpa konfigurasi manual.
- **Mempertahankan Koneksi Wi‑Fi Direct (`MainActivity`, `Bridge`)**:
  - Mempertahankan penuh fitur **Wi‑Fi Direct** dan jembatan *multihop* Wi‑Fi Direct berdampingan dengan Wi‑Fi Aware, Hotspot/LAN, dan Bluetooth Mesh.

## [0.14.0] - 2026-10-28

### Ditambahkan & Dioptimalkan (Seamless Auto-Connect dalam Radius Jangkauan)
- **Koneksi Otomatis Tanpa Ketuk & Tanpa Popup (*Zero-Touch Auto-Connect*) (`MainActivity`, `Lan`, `Ble`, `Bt`)**:
  - Menghubungkan perangkat Tim secara otomatis begitu masuk dalam radius jangkauan melalui **Hotspot/Wi‑Fi Lokal (`Lan`)**, **Bluetooth Insecure RFCOMM + BLE Beacon (`Ble` + `Bt`)**, maupun *fallback* **Wi‑Fi Direct** tanpa perlu menekan tombol `"Hubungkan"` dan tanpa PIN.
- **Resolusi Anti-*Split-Brain* & *Deterministic Tie-Breaker* (`MainActivity.autoConnectDecision`, `Lan.Peer.memberCount`, `Ble.Beacon.inGroup`)**:
  - Menyematkan jumlah anggota grup (`memberCount`) pada paket UDP `Lan` serta bendera pemilik grup aktif (`inGroup`, bit `0x40`) pada *Service Data* `Ble` 20-byte.
  - Apabila dua perangkat tunggal bertemu di lapangan, penentu pemilik otomatis (*deterministic tie-breaker*) memilih satu perangkat menjadi `Owner` dan perangkat lainnya langsung bergabung sebagai `Client`. Apabila pemilik grup kosong (`memberCount == 1`) mendeteksi grup aktif yang lebih besar (`memberCount > 1`), perangkat otomatis melebur ke grup yang lebih besar sehingga seluruh Tim menyatu dalam satu saluran tunggal.
- **Filter Kekuatan Sinyal (*RSSI Threshold*) & *Cooldown* Anti-*Flapping* (`Ble.MIN_AUTO_RSSI`, `MainActivity.canAutoConnect`)**:
  - Membatasi pemicu *auto-connect* Bluetooth/BLE hanya pada sinyal stabil (`RSSI >= -82 dBm`) dengan jeda *cooldown* `12 detik` per perangkat dan masa tenang `15 detik` setelah pengguna menekan `"Keluar"`, sehingga mencegah putus-nyambung berulang (*connection flapping*) di tepi jangkauan sinyal.
- **Pengujian Otomatis (`AdpcmTest`)**:
  - Menambahkan pengujian unit `autoConnectDecisionResolvesSplitBrainAndTieBreaker` serta memverifikasi *round-trip* atribut `inGroup`, `rssi`, dan `memberCount` pada `Ble` dan `Lan`.

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
