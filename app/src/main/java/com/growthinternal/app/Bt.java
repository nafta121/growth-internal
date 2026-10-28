package com.growthinternal.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth Classic (RFCOMM) fallback transport. Carries the exact same Frame
 * protocol as Wi-Fi Direct/LAN over an insecure RFCOMM socket, with 4:1 IMA ADPCM
 * audio compression enabled on the Link so push-to-talk voice stays smooth.
 */
final class Bt {
    static final UUID SERVICE_UUID = UUID.fromString("8f6ef5b0-9491-4d3a-b6e0-9f5c1a2e3d01");
    private static final String SERVICE_NAME = "GrowthInternal";

    interface Listener {
        void onDevice(BluetoothDevice device, String name);
        void onIncoming(BluetoothSocket socket);
    }

    private final Context ctx;
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private volatile BluetoothServerSocket serverSocket;
    private volatile boolean serverRunning;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            reportDevice(dev);
        }
    };

    Bt(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        BluetoothAdapter a = null;
        try {
            a = BluetoothAdapter.getDefaultAdapter();
        } catch (RuntimeException ignored) {
        }
        this.adapter = a;
    }

    boolean isAvailable() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    void discover() {
        if (!isAvailable()) return;
        registerReceiverIfNeeded();
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice dev : bonded) {
                    reportDevice(dev);
                }
            }
        } catch (SecurityException ignored) {
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (adapter.isDiscovering()) adapter.cancelDiscovery();
                    adapter.startDiscovery();
                } catch (SecurityException ignored) {
                } catch (RuntimeException ignored) {
                }
            }
        }, "bt-discover").start();
    }

    void startServer() {
        if (serverRunning || !isAvailable()) return;
        serverRunning = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BluetoothServerSocket ss = adapter.listenUsingInsecureRfcommWithServiceRecord(
                            SERVICE_NAME, SERVICE_UUID);
                    serverSocket = ss;
                    while (serverRunning && ss != null) {
                        BluetoothSocket sock = ss.accept();
                        if (sock == null) continue;
                        if (!serverRunning || listener == null) {
                            try { sock.close(); } catch (IOException ignored) {}
                            break;
                        }
                        listener.onIncoming(sock);
                    }
                } catch (IOException ignored) {
                } catch (SecurityException ignored) {
                } finally {
                    serverRunning = false;
                }
            }
        }, "bt-server");
        t.setDaemon(true);
        t.start();
    }

    void connect(final BluetoothDevice device, final Hub hub,
            final Runnable onConnected, final Runnable onFail) {
        if (device == null || hub == null) {
            if (onFail != null) onFail.run();
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                BluetoothSocket sock = null;
                try {
                    if (adapter != null) {
                        try { adapter.cancelDiscovery(); } catch (SecurityException ignored) {}
                    }
                    sock = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID);
                    sock.connect();
                    hub.addStreamLink(sock.getInputStream(), sock.getOutputStream(), sock, true);
                    if (onConnected != null) onConnected.run();
                } catch (IOException | SecurityException | RuntimeException e) {
                    if (sock != null) {
                        try { sock.close(); } catch (IOException ignored) {}
                    }
                    if (onFail != null) onFail.run();
                }
            }
        }, "bt-client").start();
    }

    void stopServer() {
        serverRunning = false;
        BluetoothServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) {}
        }
    }

    void stop() {
        stopServer();
        if (receiverRegistered) {
            receiverRegistered = false;
            try { ctx.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
        }
        if (adapter != null) {
            try {
                if (adapter.isDiscovering()) adapter.cancelDiscovery();
            } catch (SecurityException ignored) {
            }
        }
    }

    private void registerReceiverIfNeeded() {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, f);
        }
        receiverRegistered = true;
    }

    private void reportDevice(BluetoothDevice dev) {
        if (dev == null || listener == null) return;
        try {
            String name = dev.getName();
            if (name != null && !name.trim().isEmpty()) {
                listener.onDevice(dev, name.trim());
            }
        } catch (SecurityException ignored) {
        }
    }
}
