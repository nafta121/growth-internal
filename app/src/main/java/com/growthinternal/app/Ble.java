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
import android.os.ParcelUuid;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * Bluetooth Low Energy (BLE) connectionless beacon. Broadcasts and scans compact
 * 20-byte Service Data frames carrying member short name, Presence status (including
 * PRIORITAS/SOS), battery, and GPS coordinates — even before any Wi-Fi Direct,
 * Hotspot, or Bluetooth socket connection is established.
 */
final class Ble {
    static final ParcelUuid SERVICE_UUID =
            ParcelUuid.fromString("0000fd91-0000-1000-8000-00805f9b34fb");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int HEADER_LEN = 10;
    private static final int MAX_NAME_BYTES = 10;

    static final class Beacon {
        final String name;
        final Presence presence;

        Beacon(String name, Presence presence) {
            this.name = name;
            this.presence = presence;
        }
    }

    interface Listener {
        void onBeacon(Beacon beacon, BluetoothDevice device);
    }

    private final Listener listener;
    private final BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private boolean advertising;
    private boolean scanning;

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
        }
        @Override public void onStartFailure(int errorCode) {
            advertising = false;
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null) return;
            ScanRecord rec = result.getScanRecord();
            if (rec == null) return;
            byte[] data = rec.getServiceData(SERVICE_UUID);
            if (data == null) return;
            Beacon b = unpack(data);
            if (b != null && listener != null) {
                listener.onBeacon(b, result.getDevice());
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

    void startScan() {
        if (scanning || adapter == null) return;
        try {
            if (!adapter.isEnabled()) return;
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
        } catch (SecurityException | RuntimeException ignored) {
            scanning = false;
        }
    }

    void updateAdvertise(String name, Presence p) {
        if (adapter == null || name == null || name.trim().isEmpty()) return;
        try {
            if (!adapter.isEnabled()) return;
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) return;
            if (advertising) {
                try { advertiser.stopAdvertising(advertiseCallback); } catch (RuntimeException ignored) {}
                advertising = false;
            }
            byte[] payload = pack(name, p != null ? p : new Presence());
            boolean urgent = p != null && p.status == Presence.SOS;
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(urgent
                            ? AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                            : AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceData(SERVICE_UUID, payload)
                    .build();
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException | RuntimeException ignored) {
            advertising = false;
        }
    }

    void stop() {
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) {}
        }
        scanning = false;
        if (advertising && advertiser != null) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (RuntimeException ignored) {}
        }
        advertising = false;
    }

    static byte[] pack(String name, Presence p) {
        Presence pr = p != null ? p : new Presence();
        byte[] rawName = (name != null ? name.trim() : "").getBytes(UTF8);
        int nameLen = Math.min(rawName.length, MAX_NAME_BYTES);
        // Avoid splitting a multi-byte UTF-8 character at the boundary
        if (nameLen < rawName.length && (rawName[nameLen] & 0xC0) == 0x80) {
            while (nameLen > 0 && (rawName[nameLen] & 0xC0) == 0x80) nameLen--;
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + nameLen);
        int flags = (pr.status & 0x0F)
                | (pr.hasLocation ? 0x10 : 0)
                | (pr.charging ? 0x20 : 0);
        buf.put((byte) flags);
        buf.put((byte) pr.battery);
        buf.putFloat(pr.hasLocation ? (float) pr.lat : 0f);
        buf.putFloat(pr.hasLocation ? (float) pr.lon : 0f);
        buf.put(rawName, 0, nameLen);
        return buf.array();
    }

    static Beacon unpack(byte[] data) {
        if (data == null || data.length <= HEADER_LEN) return null;
        ByteBuffer buf = ByteBuffer.wrap(data);
        int flags = buf.get() & 0xFF;
        int battery = buf.get();
        float lat = buf.getFloat();
        float lon = buf.getFloat();
        String name = new String(data, HEADER_LEN, data.length - HEADER_LEN, UTF8).trim();
        if (name.isEmpty()) return null;
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
        return new Beacon(name, p);
    }
}
