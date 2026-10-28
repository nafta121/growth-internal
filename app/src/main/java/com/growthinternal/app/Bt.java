package com.growthinternal.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.util.UUID;

/**
 * Zero-popup Bluetooth transport exclusively for verified Growth Internal peers
 * discovered via BLE beacons. Prefers BLE L2CAP Connection-oriented Channels
 * (API 29+, works directly on BLE random addresses without pairing or popups)
 * and falls back to Insecure RFCOMM. Audio is compressed 4:1 via IMA ADPCM.
 */
final class Bt {
    static final UUID SERVICE_UUID = UUID.fromString("8f6ef5b0-9491-4d3a-b6e0-9f5c1a2e3d01");
    private static final String SERVICE_NAME = "GrowthInternal";

    interface Listener {
        void onIncoming(BluetoothSocket socket);
        void onL2capReady(int psm);
    }

    private final Listener listener;
    private final BluetoothAdapter adapter;
    private volatile BluetoothServerSocket rfcommServer;
    private volatile BluetoothServerSocket l2capServer;
    private volatile int l2capPsm;
    private volatile boolean serverRunning;

    Bt(Context ctx, Listener listener) {
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

    int l2capPsm() {
        return l2capPsm;
    }

    void startServer() {
        if (serverRunning || !isAvailable()) return;
        serverRunning = true;
        if (Build.VERSION.SDK_INT >= 29) {
            Thread l2 = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BluetoothServerSocket ss = (BluetoothServerSocket) BluetoothAdapter.class
                                .getMethod("listenUsingInsecureL2capChannel")
                                .invoke(adapter);
                        l2capServer = ss;
                        int psm = (Integer) BluetoothServerSocket.class
                                .getMethod("getPsm")
                                .invoke(ss);
                        l2capPsm = psm;
                        if (listener != null) listener.onL2capReady(psm);
                        acceptLoop(ss);
                    } catch (Exception ignored) {
                    }
                }
            }, "bt-l2cap-server");
            l2.setDaemon(true);
            l2.start();
        }
        Thread rf = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BluetoothServerSocket ss = adapter.listenUsingInsecureRfcommWithServiceRecord(
                            SERVICE_NAME, SERVICE_UUID);
                    rfcommServer = ss;
                    acceptLoop(ss);
                } catch (IOException ignored) {
                } catch (SecurityException ignored) {
                } finally {
                    serverRunning = false;
                }
            }
        }, "bt-rfcomm-server");
        rf.setDaemon(true);
        rf.start();
    }

    private void acceptLoop(BluetoothServerSocket ss) {
        try {
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
        } catch (RuntimeException ignored) {
        }
    }

    void connect(final BluetoothDevice device, final int psm, final Hub hub,
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
                    if (psm > 0 && Build.VERSION.SDK_INT >= 29) {
                        try {
                            sock = (BluetoothSocket) BluetoothDevice.class
                                    .getMethod("createInsecureL2capChannel", int.class)
                                    .invoke(device, psm);
                            sock.connect();
                        } catch (Exception e) {
                            if (sock != null) {
                                try { sock.close(); } catch (IOException ignored) {}
                                sock = null;
                            }
                        }
                    }
                    if (sock == null) {
                        sock = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID);
                        sock.connect();
                    }
                    hub.addStreamLink(sock.getInputStream(), sock.getOutputStream(), sock, true);
                    if (onConnected != null) onConnected.run();
                } catch (IOException | RuntimeException e) {
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
        l2capPsm = 0;
        BluetoothServerSocket l2 = l2capServer;
        l2capServer = null;
        if (l2 != null) {
            try { l2.close(); } catch (IOException ignored) {}
        }
        BluetoothServerSocket rf = rfcommServer;
        rfcommServer = null;
        if (rf != null) {
            try { rf.close(); } catch (IOException ignored) {}
        }
    }

    void stop() {
        stopServer();
    }
}
