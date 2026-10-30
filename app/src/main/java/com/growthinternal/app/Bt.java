package com.growthinternal.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Zero-popup Bluetooth transport exclusively for verified Growth Internal peers
 * discovered via BLE beacons. Prefers BLE L2CAP Connection-oriented Channels
 * (API 29+, works directly on BLE random addresses without pairing or popups),
 * upgrades BLE links with High Connection Priority + 2M/Coded PHY, and falls back
 * to Insecure RFCOMM. Audio is compressed 4:1 via IMA ADPCM.
 */
final class Bt {
    static final UUID SERVICE_UUID = UUID.fromString("8f6ef5b0-9491-4d3a-b6e0-9f5c1a2e3d01");
    private static final String SERVICE_NAME = "GrowthInternal";
    // PHY_LE_1M_MASK (1) | PHY_LE_2M_MASK (2) | PHY_LE_CODED_MASK (4)
    private static final int PREFERRED_PHY_MASK = 1 | 2 | 4;

    interface Listener {
        void onIncoming(BluetoothSocket socket);
        void onIncomingTunnel(MeshAccess.Pipe pipe, byte tunnelType, int fmKeyId);
        void onL2capReady(int psm);
    }

    private final Context ctx;
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private final MeshAccess meshAccess;
    private final List<BluetoothGatt> activeGatts = new CopyOnWriteArrayList<BluetoothGatt>();
    private volatile BluetoothServerSocket rfcommServer;
    private volatile BluetoothServerSocket l2capServer;
    private volatile int l2capPsm;
    private volatile boolean serverRunning;

    Bt(Context ctx, final Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        BluetoothAdapter a = null;
        try {
            a = BluetoothAdapter.getDefaultAdapter();
        } catch (RuntimeException ignored) {
        }
        this.adapter = a;
        this.meshAccess = new MeshAccess(ctx, new MeshAccess.IncomingListener() {
            @Override public void onIncomingTunnel(MeshAccess.Pipe pipe, byte tunnelType, int fmKeyId) {
                if (listener != null) listener.onIncomingTunnel(pipe, tunnelType, fmKeyId);
            }
        });
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
        meshAccess.startServer();
        if (Build.VERSION.SDK_INT >= 29) {
            Thread l2 = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BluetoothServerSocket ss = (BluetoothServerSocket) BluetoothAdapter.class
                                .getMethod("listenUsingInsecureL2capChannel")
                                .invoke(adapter);
                        if (!serverRunning) {
                            ss.close();
                            return;
                        }
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
                BluetoothServerSocket ss = null;
                try {
                    ss = adapter.listenUsingInsecureRfcommWithServiceRecord(
                            SERVICE_NAME, SERVICE_UUID);
                    if (!serverRunning) {
                        ss.close();
                        return;
                    }
                    rfcommServer = ss;
                    acceptLoop(ss);
                } catch (IOException ignored) {
                } catch (SecurityException ignored) {
                } finally {
                    if (ss == null || rfcommServer == ss) {
                        serverRunning = false;
                    }
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
                if (ss == l2capServer) {
                    optimizeBleLink(sock.getRemoteDevice());
                }
                listener.onIncoming(sock);
            }
        } catch (IOException ignored) {
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * MeshLive-inspired BLE link booster: opens a companion GATT handle on the L2CAP peer to
     * request CONNECTION_PRIORITY_HIGH (7.5-15 ms interval), MTU 517, and 2M/Coded PHY (API 26+).
     */
    // shortcut: uses reflection for setPreferredPhy because build.sh compiles against android-23; call directly when compileSdk >= 26
    private void optimizeBleLink(BluetoothDevice device) {
        if (device == null || ctx == null) return;
        try {
            BluetoothGattCallback cb = new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                            gatt.requestMtu(517);
                            if (Build.VERSION.SDK_INT >= 26) {
                                BluetoothGatt.class.getMethod("setPreferredPhy", int.class, int.class, int.class)
                                        .invoke(gatt, PREFERRED_PHY_MASK, PREFERRED_PHY_MASK, 0);
                            }
                        } catch (Exception ignored) {
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        activeGatts.remove(gatt);
                        try { gatt.close(); } catch (RuntimeException ignored) {}
                    }
                }
            };
            BluetoothGatt gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
            if (gatt != null) activeGatts.add(gatt);
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
                MeshAccess.Pipe tunnel = null;
                boolean usedL2cap = false;
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
                            usedL2cap = true;
                        } catch (Exception e) {
                            if (sock != null) {
                                try { sock.close(); } catch (IOException ignored) {}
                                sock = null;
                            }
                        }
                    }
                    if (sock == null) {
                        tunnel = meshAccess.openTunnel(
                                device, MeshAccess.FM_KEY_NETWORK, MeshAccess.TUNNEL_REMOTE_MESH);
                        if (tunnel != null) {
                            hub.addStreamLink(tunnel.in, tunnel.out, tunnel, true);
                            if (onConnected != null) onConnected.run();
                            return;
                        }
                        sock = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID);
                        sock.connect();
                    }
                    if (usedL2cap) optimizeBleLink(device);
                    hub.addStreamLink(sock.getInputStream(), sock.getOutputStream(), sock, true);
                    if (onConnected != null) onConnected.run();
                } catch (IOException | RuntimeException e) {
                    if (tunnel != null) {
                        try { tunnel.close(); } catch (IOException ignored) {}
                    }
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
        meshAccess.stopServer();
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
        for (BluetoothGatt g : activeGatts) {
            try { g.disconnect(); } catch (RuntimeException ignored) {}
            try { g.close(); } catch (RuntimeException ignored) {}
        }
        activeGatts.clear();
    }

    void stop() {
        stopServer();
    }
}
