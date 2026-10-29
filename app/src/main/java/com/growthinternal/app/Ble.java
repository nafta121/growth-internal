package com.growthinternal.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Bluetooth Low Energy (BLE) connectionless beacon + multi-hop flood mesh.
 *
 * 1. SERVICE_UUID (0xFD91): Broadcasts member short name, Presence status (including
 *    PRIORITAS/SOS), battery, GPS coordinates, group state, and BLE L2CAP PSM so
 *    nearby Growth Internal phones can open a zero-popup L2CAP stream or join the mesh.
 * 2. MESH_UUID (0xFD92): Multi-hop connectionless BLE flood mesh with TTL (up to 5 hops)
 *    and 16-bit packet deduplication for relaying SOS/Presence, chat, and instructions
 *    across phones even without an active Wi-Fi or Bluetooth socket.
 */
final class Ble {
    static final ParcelUuid SERVICE_UUID =
            ParcelUuid.fromString("0000fd91-0000-1000-8000-00805f9b34fb");
    static final ParcelUuid MESH_UUID =
            ParcelUuid.fromString("0000fd92-0000-1000-8000-00805f9b34fb");
    static final ParcelUuid MESH_ACCESS_UUID =
            ParcelUuid.fromString("0000fe12-0000-1000-8000-00805f9b34fb");
    static final int MIN_AUTO_RSSI = -82;

    static final byte MESH_PRESENCE = 1;
    static final byte MESH_TEXT = 2;
    static final byte MESH_ANNOUNCE = 3;
    static final int DEFAULT_TTL = 15;

    private static final int HEADER_LEN = 10;
    private static final int HEADER_PSM_LEN = 12;
    private static final int MAX_NAME_BYTES = 10;
    private static final int MAX_MESH_SENDER = 8;
    private static final int MAX_MESH_TOTAL = 22;
    private static final int MAX_SEEN_IDS = 256;
    static final byte SMAZ_MAGIC = (byte) 0x1E;
    static final byte FRAG_MAGIC = (byte) 0x1F;
    static final int MAX_FRAG_PARTS = 7;
    static final int MAX_FRAG_TEXT_BYTES = 56;
    private static final String[] SMAZ_DICT = {
        "Rotasi ", "rotasi ", "dimulai ", "menit ", "menuju ", "Main Hall", "Pos ", "pos ",
        "Aman", "aman", "Tim ", "tim ", "Butuh ", "butuh ", "medis ", "bantuan ",
        "segera", "darurat", "jalur ", "evakuasi", "sektor ", "lokasi ", "baterai ", "siap ",
        "lagi ", "dari ", "untuk ", "dengan ", "sudah ", "belum ", "tidak ", "ada ",
        "di ", "ke ", "dan ", "yang ", "ini ", "itu ", "10 ", "15 ",
        "an", "ar", "at", "en", "er", "in", "it", "ka",
        "ke", "la", "ma", "me", "ng", "ny", "pa", "pe",
        "ra", "re", "sa", "se", "ta", "te", "ti", "tu"
    };
    private static final int MAX_MESH_QUEUE = 16;
    private static final long MESH_ADV_WINDOW_MS = 450L;

    private static final class FragSlot {
        final byte[][] parts;
        int received;
        FragSlot(int total) { parts = new byte[total][]; }
    }

    private static final class QueuedMesh {
        final int msgId;
        final byte[] raw;
        int retries;
        QueuedMesh(int msgId, byte[] raw, int retries) {
            this.msgId = msgId;
            this.raw = raw;
            this.retries = retries;
        }
    }

    static final class Beacon {
        final String name;
        final Presence presence;
        final boolean inGroup;
        final int rssi;
        final int psm;
        final int clusterId;

        Beacon(String name, Presence presence, boolean inGroup, int rssi) {
            this(name, presence, inGroup, rssi, 0, 0);
        }

        Beacon(String name, Presence presence, boolean inGroup, int rssi, int psm) {
            this(name, presence, inGroup, rssi, psm, 0);
        }

        Beacon(String name, Presence presence, boolean inGroup, int rssi, int psm, int clusterId) {
            this.name = name;
            this.presence = presence;
            this.inGroup = inGroup;
            this.rssi = rssi;
            this.psm = psm;
            this.clusterId = clusterId & 0xFFFF;
        }
    }

    static final class MeshPacket {
        final int msgId;
        final int ttl;
        final byte type;
        final String sender;
        final byte[] body;

        MeshPacket(int msgId, int ttl, byte type, String sender, byte[] body) {
            this.msgId = msgId & 0xFFFF;
            this.ttl = ttl;
            this.type = type;
            this.sender = sender;
            this.body = body;
        }
    }

    interface Listener {
        void onBeacon(Beacon beacon, BluetoothDevice device);
        void onMesh(MeshPacket packet);
    }

    private final Listener listener;
    private final BluetoothAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Map<Integer, Integer> seenMeshCounts = new LinkedHashMap<Integer, Integer>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
            return size() > MAX_SEEN_IDS;
        }
    };
    private final Map<String, FragSlot> fragSlots = new LinkedHashMap<String, FragSlot>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, FragSlot> eldest) {
            return size() > 32;
        }
    };
    private final List<QueuedMesh> meshTxQueue = new ArrayList<QueuedMesh>();
    private QueuedMesh activeMeshTx;
    private volatile long lastScanResultMs;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private boolean advertising;
    private boolean meshAdvertising;
    private boolean scanning;
    private boolean connectedMode;
    private boolean voiceActive;
    private int activeScanMode = -1;
    private long lastScanStartMs;
    private String lastAdvName;
    private Presence lastAdvPresence;
    private boolean lastAdvInGroup;
    private int lastAdvPsm;
    private int lastAdvClusterId;
    private static final long WATCHDOG_MS = 25000L;
    private static final long STALL_RESTART_MS = 60000L;
    static final long MIN_SCAN_RESTART_MS = 8000L;

    private final Runnable applyScanModeRunnable = new Runnable() {
        @Override public void run() {
            restartScanIfModeChanged(System.currentTimeMillis());
        }
    };

    private final Runnable watchdogRunnable = new Runnable() {
        @Override public void run() {
            if (adapter != null && adapter.isEnabled()) {
                long now = System.currentTimeMillis();
                // Spectre silent-stall recovery: restart BLE scanner if no callbacks arrived for STALL_RESTART_MS
                if (scanning && lastScanResultMs > 0 && now - lastScanResultMs > STALL_RESTART_MS
                        && now - lastScanStartMs >= MIN_SCAN_RESTART_MS) {
                    if (scanner != null) {
                        try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) {}
                    }
                    scanning = false;
                }
                if (!scanning) startScan();
                if (!advertising && !meshAdvertising && lastAdvName != null) {
                    updateAdvertise(lastAdvName, lastAdvPresence, lastAdvInGroup, lastAdvPsm, lastAdvClusterId);
                }
            }
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
        }
        @Override public void onStartFailure(int errorCode) {
            advertising = false;
        }
    };

    private final AdvertiseCallback meshAdvertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            meshAdvertising = true;
        }
        @Override public void onStartFailure(int errorCode) {
            meshAdvertising = false;
        }
    };

    private final Runnable stopMeshRunnable = new Runnable() {
        @Override public void run() {
            stopMeshAdvertise();
            // FISSURE Implicit Echo ACK: if origin packet was not echoed by any neighbor, retry once
            if (activeMeshTx != null && activeMeshTx.retries > 0
                    && overheardCount(activeMeshTx.msgId) < 2
                    && meshTxQueue.size() < MAX_MESH_QUEUE) {
                activeMeshTx.retries--;
                meshTxQueue.add(activeMeshTx);
            }
            activeMeshTx = null;
            if (!meshTxQueue.isEmpty()) {
                pumpMeshTx();
            } else if (!advertising && lastAdvName != null) {
                updateAdvertise(lastAdvName, lastAdvPresence, lastAdvInGroup, lastAdvPsm, lastAdvClusterId);
            }
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null) return;
            lastScanResultMs = System.currentTimeMillis();
            ScanRecord rec = result.getScanRecord();
            if (rec == null) return;
            byte[] data = rec.getServiceData(SERVICE_UUID);
            if (data != null) {
                Beacon b = unpack(data, result.getRssi());
                if (b != null && listener != null) {
                    listener.onBeacon(b, result.getDevice());
                }
            }
            byte[] meshData = rec.getServiceData(MESH_UUID);
            if (meshData != null) {
                final MeshPacket pkt = unpackMesh(meshData);
                final int rssi = result.getRssi();
                if (pkt != null && recordSeenMesh(pkt.msgId)) {
                    byte[] assembled = reassembleMeshBody(pkt.sender, pkt.type, pkt.body);
                    if (assembled != null && listener != null) {
                        listener.onMesh(assembled == pkt.body
                                ? pkt
                                : new MeshPacket(pkt.msgId, pkt.ttl, pkt.type, pkt.sender, assembled));
                    }
                    if (pkt.ttl > 1) {
                        final byte[] relayed = packMesh(pkt.msgId, pkt.ttl - 1, pkt.type, pkt.sender, pkt.body);
                        int delayMs = computeRelayDelayMs(rssi, random.nextInt(26));
                        handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                // Opportunistic suppression: near nodes skip relay if neighbors already forwarded it
                                if (rssi > -65 && overheardCount(pkt.msgId) >= 2) return;
                                enqueueMeshRaw(pkt.msgId, relayed, 0);
                            }
                        }, delayMs);
                    }
                }
            }
        }

        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            handler.removeCallbacks(watchdogRunnable);
            handler.postDelayed(watchdogRunnable, 2000L);
        }
    };

    Ble(Listener listener) {
        this.listener = listener;
        BluetoothAdapter a = null;
        try {
            a = BluetoothAdapter.getDefaultAdapter();
        } catch (RuntimeException ignored) {
        }
        this.adapter = a;
    }

    /**
     * RuView ADR-029 Distance-Weighted TDM slot assignment:
     * Outer-edge nodes (<= -80 dBm) get Slot 0 (15..40 ms) to maximize geographic hop reach;
     * mid-range nodes (-79..-65 dBm) get Slot 1 (55..105 ms);
     * near nodes (> -65 dBm) get Slot 2 (125..200 ms) and suppress if already overheard.
     */
    static int computeRelayDelayMs(int rssi, int jitter) {
        int j = Math.abs(jitter) % 26;
        if (rssi <= -80) return 15 + j;
        if (rssi <= -65) return 55 + j * 2;
        return 125 + j * 3;
    }

    synchronized boolean recordSeenMesh(int msgId) {
        int id = msgId & 0xFFFF;
        Integer prev = seenMeshCounts.get(id);
        if (prev != null) {
            seenMeshCounts.put(id, prev + 1);
            return false;
        }
        seenMeshCounts.put(id, 1);
        return true;
    }

    synchronized int overheardCount(int msgId) {
        Integer c = seenMeshCounts.get(msgId & 0xFFFF);
        return c != null ? c : 0;
    }

    /**
     * Explicit hardware-offloaded ScanFilters so Android 8.1+ never blocks BLE discovery,
     * 15-hop flood mesh, or BlueRange MeshAccess while the screen is turned off.
     */
    static List<ScanFilter> buildScanFilters() {
        List<ScanFilter> filters = new ArrayList<ScanFilter>(3);
        filters.add(new ScanFilter.Builder()
                .setServiceData(SERVICE_UUID, new byte[0], new byte[0])
                .build());
        filters.add(new ScanFilter.Builder()
                .setServiceData(MESH_UUID, new byte[0], new byte[0])
                .build());
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(MESH_ACCESS_UUID)
                .build());
        return filters;
    }

    int effectiveScanMode() {
        if (voiceActive) return ScanSettings.SCAN_MODE_LOW_POWER;
        if (connectedMode) return ScanSettings.SCAN_MODE_BALANCED;
        return ScanSettings.SCAN_MODE_LOW_LATENCY;
    }

    /** Scales BLE scan duty-cycle down while connected so 2.4 GHz airtime stays free for Wi-Fi/L2CAP voice. */
    void setConnectedMode(boolean connected) {
        if (this.connectedMode == connected) return;
        this.connectedMode = connected;
        restartScanIfModeChanged(System.currentTimeMillis());
    }

    /** Throttles 2.4 GHz BLE scanning to LOW_POWER during live PTT/Call voice to eliminate antenna contention. */
    void setVoiceActive(boolean active) {
        if (this.voiceActive == active) return;
        this.voiceActive = active;
        restartScanIfModeChanged(System.currentTimeMillis());
    }

    boolean canRestartScanNow(long nowMs) {
        return lastScanStartMs == 0L || (nowMs - lastScanStartMs) >= MIN_SCAN_RESTART_MS;
    }

    private void restartScanIfModeChanged(long nowMs) {
        handler.removeCallbacks(applyScanModeRunnable);
        if (!scanning || scanner == null || effectiveScanMode() == activeScanMode) return;
        if (!canRestartScanNow(nowMs)) {
            long waitMs = Math.max(250L, MIN_SCAN_RESTART_MS - (nowMs - lastScanStartMs));
            handler.postDelayed(applyScanModeRunnable, waitMs);
            return;
        }
        try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) {}
        scanning = false;
        startScan();
    }

    // shortcut: uses reflection for BLE 5 Coded PHY scan settings because build.sh compiles against android-23; call directly when compileSdk >= 26
    void startScan() {
        if (scanning || adapter == null) return;
        try {
            if (!adapter.isEnabled()) return;
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            int mode = effectiveScanMode();
            ScanSettings.Builder sb = new ScanSettings.Builder()
                    .setScanMode(mode);
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    ScanSettings.Builder.class.getMethod("setLegacy", boolean.class).invoke(sb, false);
                    ScanSettings.Builder.class.getMethod("setPhy", int.class).invoke(sb, 255 /* PHY_LE_ALL_SUPPORTED */);
                } catch (Exception ignored) {
                }
            }
            scanner.startScan(buildScanFilters(), sb.build(), scanCallback);
            scanning = true;
            activeScanMode = mode;
            long now = System.currentTimeMillis();
            lastScanStartMs = now;
            lastScanResultMs = now;
            handler.removeCallbacks(watchdogRunnable);
            handler.postDelayed(watchdogRunnable, WATCHDOG_MS);
        } catch (RuntimeException ignored) {
            scanning = false;
        }
    }

    void updateAdvertise(String name, Presence p, boolean inGroup, int psm, int clusterId) {
        lastAdvName = name;
        lastAdvPresence = p;
        lastAdvInGroup = inGroup;
        lastAdvPsm = psm;
        lastAdvClusterId = clusterId & 0xFFFF;
        if (adapter == null || name == null || name.trim().isEmpty()) return;
        try {
            if (!adapter.isEnabled()) return;
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) return;
            if (advertising) {
                try { advertiser.stopAdvertising(advertiseCallback); } catch (RuntimeException ignored) {}
                advertising = false;
            }
            byte[] payload = pack(name, p != null ? p : new Presence(), inGroup, psm, lastAdvClusterId);
            boolean urgent = p != null && p.status == Presence.SOS;
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(urgent
                            ? AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                            : AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setTimeout(0)
                    .setConnectable(true)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceData(SERVICE_UUID, payload)
                    .build();
            advertiser.startAdvertising(settings, data, advertiseCallback);
            handler.removeCallbacks(watchdogRunnable);
            handler.postDelayed(watchdogRunnable, WATCHDOG_MS);
        } catch (RuntimeException ignored) {
            advertising = false;
        }
    }

    void broadcastMeshPresence(String sender, Presence p) {
        sendMesh(MESH_PRESENCE, sender, packMeshPresence(p));
    }

    void broadcastMeshText(String sender, String text) {
        if (text == null || text.isEmpty()) return;
        int gid = ((sender != null ? sender.hashCode() : 0) ^ text.hashCode()) & 0x03;
        for (byte[] part : splitMeshText(text, gid)) {
            sendMesh(MESH_TEXT, sender, part);
        }
    }

    void broadcastMeshAnnounce(String sender, String text) {
        if (text == null || text.isEmpty()) return;
        int gid = ((sender != null ? sender.hashCode() : 0) ^ text.hashCode()) & 0x03;
        for (byte[] part : splitMeshText(text, gid)) {
            sendMesh(MESH_ANNOUNCE, sender, part);
        }
    }

    void sendMesh(byte type, String sender, byte[] body) {
        if (sender == null || sender.trim().isEmpty() || body == null) return;
        int msgId = (random.nextInt(0xFFFE) + 1) & 0xFFFF;
        recordSeenMesh(msgId);
        byte[] raw = packMesh(msgId, DEFAULT_TTL, type, sender, body);
        if (raw != null) enqueueMeshRaw(msgId, raw, 1);
    }

    private void enqueueMeshRaw(int msgId, byte[] raw, int retries) {
        if (raw == null || meshTxQueue.size() >= MAX_MESH_QUEUE) return;
        meshTxQueue.add(new QueuedMesh(msgId, raw, retries));
        if (activeMeshTx == null) pumpMeshTx();
    }

    private void pumpMeshTx() {
        if (meshTxQueue.isEmpty()) return;
        activeMeshTx = meshTxQueue.remove(0);
        if (!advertiseMeshRaw(activeMeshTx.raw)) {
            activeMeshTx = null;
        }
    }

    private boolean advertiseMeshRaw(byte[] raw) {
        if (raw == null || adapter == null) return false;
        try {
            if (!adapter.isEnabled()) return false;
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) return false;
            handler.removeCallbacks(stopMeshRunnable);
            stopMeshAdvertise();
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceData(MESH_UUID, raw)
                    .build();
            advertiser.startAdvertising(settings, data, meshAdvertiseCallback);
            handler.postDelayed(stopMeshRunnable, MESH_ADV_WINDOW_MS);
            return true;
        } catch (RuntimeException ignored) {
            meshAdvertising = false;
            return false;
        }
    }

    private void stopMeshAdvertise() {
        if (meshAdvertising && advertiser != null) {
            try { advertiser.stopAdvertising(meshAdvertiseCallback); } catch (RuntimeException ignored) {}
        }
        meshAdvertising = false;
    }

    void stop() {
        handler.removeCallbacks(watchdogRunnable);
        handler.removeCallbacks(stopMeshRunnable);
        handler.removeCallbacks(applyScanModeRunnable);
        meshTxQueue.clear();
        activeMeshTx = null;
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) {}
        }
        scanning = false;
        stopMeshAdvertise();
        if (advertising && advertiser != null) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (RuntimeException ignored) {}
        }
        advertising = false;
    }

    /**
     * MeshSat SMAZ2-inspired dictionary compression for short field messages (< 1 ms).
     * Emits SMAZ_MAGIC (0x1E) prefix only when compressed bytes are strictly shorter than UTF-8.
     */
    static byte[] compressText(String text) {
        if (text == null || text.isEmpty()) return new byte[0];
        String trimmed = text.trim();
        byte[] raw = trimmed.getBytes(Hub.UTF8);
        if (raw.length <= 4) return raw;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(raw.length);
        bos.write(SMAZ_MAGIC);
        int i = 0;
        while (i < raw.length) {
            int matchedIdx = -1;
            int matchedLen = 0;
            for (int d = 0; d < SMAZ_DICT.length; d++) {
                String entry = SMAZ_DICT[d];
                int eLen = entry.length();
                if (eLen > matchedLen && i + eLen <= raw.length) {
                    boolean ok = true;
                    for (int k = 0; k < eLen; k++) {
                        if (raw[i + k] != (byte) entry.charAt(k)) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        matchedIdx = d;
                        matchedLen = eLen;
                    }
                }
            }
            if (matchedIdx >= 0) {
                bos.write(0x80 | matchedIdx);
                i += matchedLen;
            } else {
                int b = raw[i++] & 0xFF;
                if (b >= 0x80 || b == (SMAZ_MAGIC & 0xFF) || b == (FRAG_MAGIC & 0xFF)) {
                    bos.write(0xFF);
                }
                bos.write(b);
            }
        }
        byte[] comp = bos.toByteArray();
        return comp.length < raw.length ? comp : raw;
    }

    static String decompressText(byte[] data) {
        if (data == null || data.length == 0) return "";
        if (data[0] != SMAZ_MAGIC) return new String(data, Hub.UTF8);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(data.length * 2);
        int i = 1;
        while (i < data.length) {
            int b = data[i++] & 0xFF;
            if (b == 0xFF) {
                if (i < data.length) bos.write(data[i++] & 0xFF);
            } else if ((b & 0x80) != 0) {
                int idx = b & 0x7F;
                if (idx < SMAZ_DICT.length) {
                    String entry = SMAZ_DICT[idx];
                    for (int k = 0; k < entry.length(); k++) bos.write(entry.charAt(k));
                }
            } else {
                bos.write(b);
            }
        }
        return new String(bos.toByteArray(), Hub.UTF8);
    }

    static List<byte[]> splitMeshText(String text, int groupId) {
        String trimmed = text != null ? text.trim() : "";
        byte[] utf8 = trimmed.getBytes(Hub.UTF8);
        List<byte[]> out = new ArrayList<byte[]>();
        if (utf8.length == 0) return out;
        if (utf8.length <= 10) {
            out.add(utf8);
            return out;
        }
        byte[] full = compressText(trimmed);
        if (full.length > MAX_FRAG_TEXT_BYTES) {
            full = trimUtf8(trimmed, MAX_FRAG_TEXT_BYTES);
        }
        if (full.length <= 10) {
            out.add(full);
            return out;
        }
        int chunkCap = 8;
        int total = Math.min(MAX_FRAG_PARTS, (full.length + chunkCap - 1) / chunkCap);
        int gid = groupId & 0x03;
        for (int i = 0; i < total; i++) {
            int off = i * chunkCap;
            int len = Math.min(chunkCap, full.length - off);
            byte[] frag = new byte[2 + len];
            frag[0] = FRAG_MAGIC;
            frag[1] = (byte) ((gid << 6) | ((i & 0x07) << 3) | (total & 0x07));
            System.arraycopy(full, off, frag, 2, len);
            out.add(frag);
        }
        return out;
    }

    synchronized byte[] reassembleMeshBody(String sender, byte type, byte[] body) {
        if (body == null || body.length < 2 || body[0] != FRAG_MAGIC) {
            if (body != null && body.length > 0 && body[0] == SMAZ_MAGIC) {
                return decompressText(body).getBytes(Hub.UTF8);
            }
            return body;
        }
        if (body.length == 2) return null;
        int meta = body[1] & 0xFF;
        int gid = (meta >>> 6) & 0x03;
        int idx = (meta >>> 3) & 0x07;
        int total = meta & 0x07;
        if (total <= 1 || total > MAX_FRAG_PARTS || idx >= total) return null;
        String key = sender + ":" + type + ":" + gid;
        FragSlot slot = fragSlots.get(key);
        if (slot == null || slot.parts.length != total) {
            slot = new FragSlot(total);
            fragSlots.put(key, slot);
        }
        if (slot.parts[idx] == null) {
            slot.parts[idx] = Arrays.copyOfRange(body, 2, body.length);
            slot.received++;
        }
        if (slot.received < total) return null;
        fragSlots.remove(key);
        int totalLen = 0;
        for (byte[] p : slot.parts) totalLen += p.length;
        byte[] assembled = new byte[totalLen];
        int pos = 0;
        for (byte[] p : slot.parts) {
            System.arraycopy(p, 0, assembled, pos, p.length);
            pos += p.length;
        }
        if (assembled.length > 0 && assembled[0] == SMAZ_MAGIC) {
            return decompressText(assembled).getBytes(Hub.UTF8);
        }
        return assembled;
    }

    private static byte[] trimUtf8(String s, int maxBytes) {
        byte[] raw = (s != null ? s.trim() : "").getBytes(Hub.UTF8);
        int len = Math.min(raw.length, maxBytes);
        if (len < raw.length && (raw[len] & 0xC0) == 0x80) {
            while (len > 0 && (raw[len] & 0xC0) == 0x80) len--;
        }
        byte[] out = new byte[len];
        System.arraycopy(raw, 0, out, 0, len);
        return out;
    }

    static byte[] pack(String name, Presence p) {
        return pack(name, p, false, 0, 0);
    }

    static byte[] pack(String name, Presence p, boolean inGroup) {
        return pack(name, p, inGroup, 0, 0);
    }

    static byte[] pack(String name, Presence p, boolean inGroup, int psm) {
        return pack(name, p, inGroup, psm, 0);
    }

    static byte[] pack(String name, Presence p, boolean inGroup, int psm, int clusterId) {
        Presence pr = p != null ? p : new Presence();
        boolean hasPsm = psm > 0 && psm <= 0xFFFF;
        boolean hasCluster = (clusterId & 0xFFFF) != 0;
        int hdr = (hasPsm ? HEADER_PSM_LEN : HEADER_LEN) + (hasCluster ? 2 : 0);
        byte[] nameBytes = trimUtf8(name, hasCluster ? (MAX_NAME_BYTES - 2) : MAX_NAME_BYTES);
        ByteBuffer buf = ByteBuffer.allocate(hdr + nameBytes.length);
        int flags = (pr.status & 0x07)
                | (hasCluster ? 0x08 : 0)
                | (pr.hasLocation ? 0x10 : 0)
                | (pr.charging ? 0x20 : 0)
                | (inGroup ? 0x40 : 0)
                | (hasPsm ? 0x80 : 0);
        buf.put((byte) flags);
        buf.put((byte) pr.battery);
        buf.putFloat(pr.hasLocation ? (float) pr.lat : 0f);
        buf.putFloat(pr.hasLocation ? (float) pr.lon : 0f);
        if (hasPsm) buf.putShort((short) psm);
        if (hasCluster) buf.putShort((short) clusterId);
        buf.put(nameBytes);
        return buf.array();
    }

    static Beacon unpack(byte[] data) {
        return unpack(data, -50);
    }

    static Beacon unpack(byte[] data, int rssi) {
        if (data == null || data.length <= HEADER_LEN) return null;
        ByteBuffer buf = ByteBuffer.wrap(data);
        int flags = buf.get() & 0xFF;
        boolean hasCluster = (flags & 0x08) != 0;
        boolean hasPsm = (flags & 0x80) != 0;
        int hdr = (hasPsm ? HEADER_PSM_LEN : HEADER_LEN) + (hasCluster ? 2 : 0);
        if (data.length <= hdr) return null;
        int battery = buf.get();
        float lat = buf.getFloat();
        float lon = buf.getFloat();
        int psm = hasPsm ? (buf.getShort() & 0xFFFF) : 0;
        int clusterId = hasCluster ? (buf.getShort() & 0xFFFF) : 0;
        String name = new String(data, hdr, data.length - hdr, Hub.UTF8).trim();
        if (name.isEmpty()) return null;
        Presence p = new Presence();
        p.status = flags & 0x07;
        p.hasLocation = (flags & 0x10) != 0;
        p.charging = (flags & 0x20) != 0;
        boolean inGroup = (flags & 0x40) != 0;
        p.battery = battery;
        if (p.hasLocation) {
            p.lat = lat;
            p.lon = lon;
            p.accuracy = 15f;
        }
        return new Beacon(name, p, inGroup, rssi, psm, clusterId);
    }

    static byte[] packMeshPresence(Presence p) {
        Presence pr = p != null ? p : new Presence();
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN);
        int flags = (pr.status & 0x0F)
                | (pr.hasLocation ? 0x10 : 0)
                | (pr.charging ? 0x20 : 0);
        buf.put((byte) flags);
        buf.put((byte) pr.battery);
        buf.putFloat(pr.hasLocation ? (float) pr.lat : 0f);
        buf.putFloat(pr.hasLocation ? (float) pr.lon : 0f);
        return buf.array();
    }

    static Presence unpackMeshPresence(byte[] body) {
        if (body == null || body.length < HEADER_LEN) return null;
        ByteBuffer buf = ByteBuffer.wrap(body);
        int flags = buf.get() & 0xFF;
        int battery = buf.get();
        float lat = buf.getFloat();
        float lon = buf.getFloat();
        Presence p = new Presence();
        p.status = flags & 0x0F;
        p.hasLocation = (flags & 0x10) != 0;
        p.charging = (flags & 0x20) != 0;
        p.battery = battery;
        if (p.hasLocation) {
            p.lat = lat;
            p.lon = lon;
            p.accuracy = 15f;
        }
        return p;
    }

    static byte[] packMesh(int msgId, int ttl, byte type, String sender, byte[] body) {
        if (ttl <= 0 || body == null) return null;
        byte[] senderBytes = trimUtf8(sender, MAX_MESH_SENDER);
        if (senderBytes.length == 0) return null;
        int maxBody = MAX_MESH_TOTAL - 4 - senderBytes.length;
        int bodyLen = Math.min(body.length, Math.max(0, maxBody));
        ByteBuffer buf = ByteBuffer.allocate(4 + senderBytes.length + bodyLen);
        int typeAndTtl = ((type & 0x0F) << 4) | (Math.min(ttl, 15) & 0x0F);
        buf.put((byte) typeAndTtl);
        buf.putShort((short) msgId);
        buf.put((byte) senderBytes.length);
        buf.put(senderBytes);
        buf.put(body, 0, bodyLen);
        return buf.array();
    }

    static MeshPacket unpackMesh(byte[] data) {
        if (data == null || data.length < 5) return null;
        ByteBuffer buf = ByteBuffer.wrap(data);
        int typeAndTtl = buf.get() & 0xFF;
        byte type = (byte) ((typeAndTtl >>> 4) & 0x0F);
        int ttl = typeAndTtl & 0x0F;
        if (ttl <= 0 || type <= 0) return null;
        int msgId = buf.getShort() & 0xFFFF;
        int senderLen = buf.get() & 0xFF;
        if (senderLen <= 0 || 4 + senderLen > data.length) return null;
        String sender = new String(data, 4, senderLen, Hub.UTF8).trim();
        if (sender.isEmpty()) return null;
        int bodyLen = data.length - 4 - senderLen;
        byte[] body = new byte[bodyLen];
        if (bodyLen > 0) System.arraycopy(data, 4 + senderLen, body, 0, bodyLen);
        return new MeshPacket(msgId, ttl, type, sender, body);
    }
}
