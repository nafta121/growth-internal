package com.growthinternal.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashSet;
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
    static final int MIN_AUTO_RSSI = -82;

    static final byte MESH_PRESENCE = 1;
    static final byte MESH_TEXT = 2;
    static final byte MESH_ANNOUNCE = 3;
    static final int DEFAULT_TTL = 5;

    private static final int HEADER_LEN = 10;
    private static final int HEADER_PSM_LEN = 12;
    private static final int MAX_NAME_BYTES = 10;
    private static final int MAX_MESH_SENDER = 8;
    private static final int MAX_MESH_TOTAL = 22;
    private static final int MAX_SEEN_IDS = 256;

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
    private final LinkedHashSet<Integer> seenMeshIds = new LinkedHashSet<Integer>();
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private boolean advertising;
    private boolean meshAdvertising;
    private boolean scanning;
    private boolean connectedMode;
    private String lastAdvName;
    private Presence lastAdvPresence;
    private boolean lastAdvInGroup;
    private int lastAdvPsm;
    private int lastAdvClusterId;
    private static final long WATCHDOG_MS = 25000L;

    private final Runnable watchdogRunnable = new Runnable() {
        @Override public void run() {
            if (adapter != null && adapter.isEnabled()) {
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
            if (!advertising && lastAdvName != null) {
                updateAdvertise(lastAdvName, lastAdvPresence, lastAdvInGroup, lastAdvPsm, lastAdvClusterId);
            }
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null) return;
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
                MeshPacket pkt = unpackMesh(meshData);
                if (pkt != null && recordSeenMesh(pkt.msgId)) {
                    if (listener != null) listener.onMesh(pkt);
                    if (pkt.ttl > 1) {
                        byte[] relayed = packMesh(pkt.msgId, pkt.ttl - 1, pkt.type, pkt.sender, pkt.body);
                        advertiseMeshRaw(relayed);
                    }
                }
            }
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

    synchronized boolean recordSeenMesh(int msgId) {
        int id = msgId & 0xFFFF;
        if (seenMeshIds.contains(id)) return false;
        if (seenMeshIds.size() >= MAX_SEEN_IDS) {
            Iterator<Integer> it = seenMeshIds.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        seenMeshIds.add(id);
        return true;
    }

    /** Scales BLE scan duty-cycle down while connected so 2.4 GHz airtime stays free for Wi-Fi/L2CAP voice. */
    void setConnectedMode(boolean connected) {
        if (this.connectedMode == connected) return;
        this.connectedMode = connected;
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) {}
            scanning = false;
            startScan();
        }
    }

    void startScan() {
        if (scanning || adapter == null) return;
        try {
            if (!adapter.isEnabled()) return;
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(connectedMode
                            ? ScanSettings.SCAN_MODE_BALANCED
                            : ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            handler.removeCallbacks(watchdogRunnable);
            handler.postDelayed(watchdogRunnable, WATCHDOG_MS);
        } catch (RuntimeException ignored) {
            scanning = false;
        }
    }

    void updateAdvertise(String name, Presence p, boolean inGroup) {
        updateAdvertise(name, p, inGroup, lastAdvPsm, lastAdvClusterId);
    }

    void updateAdvertise(String name, Presence p, boolean inGroup, int psm) {
        updateAdvertise(name, p, inGroup, psm, lastAdvClusterId);
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
        sendMesh(MESH_TEXT, sender, trimUtf8(text, 10));
    }

    void broadcastMeshAnnounce(String sender, String text) {
        if (text == null || text.isEmpty()) return;
        sendMesh(MESH_ANNOUNCE, sender, trimUtf8(text, 10));
    }

    void sendMesh(byte type, String sender, byte[] body) {
        if (sender == null || sender.trim().isEmpty() || body == null) return;
        int msgId = (random.nextInt(0xFFFE) + 1) & 0xFFFF;
        recordSeenMesh(msgId);
        byte[] raw = packMesh(msgId, DEFAULT_TTL, type, sender, body);
        if (raw != null) advertiseMeshRaw(raw);
    }

    private void advertiseMeshRaw(byte[] raw) {
        if (raw == null || adapter == null) return;
        try {
            if (!adapter.isEnabled()) return;
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) return;
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
            handler.postDelayed(stopMeshRunnable, 900);
        } catch (RuntimeException ignored) {
            meshAdvertising = false;
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
