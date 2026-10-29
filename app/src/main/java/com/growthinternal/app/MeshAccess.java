package com.growthinternal.app;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * BlueRange-Mesh inspired MeshAccessModule (0xFE12) & MeshAccessConnection:
 * - Custom 4-Way AES-128 Handshake over unencrypted BLE GATT (SET_OPEN, zero OS pairing popups)
 *   supporting both FM_KEY_NETWORK (team key) and FM_KEY_ZERO (zero-action passive/blind relay).
 * - 4-byte Message Integrity Check (MIC) + DEAD_DATA (0xDEADDADA00FF7733) automatic nonce resync.
 * - Packet Splitting & Reassembly (SPLIT_WRITE_CMD / SPLIT_WRITE_CMD_END) into a standard Stream.
 */
final class MeshAccess {
    static final UUID SERVICE_UUID = UUID.fromString("0000fe12-0000-1000-8000-00805f9b34fb");
    static final UUID RX_CHAR_UUID = UUID.fromString("00000002-acce-423c-93fd-0c07a0051858");
    static final UUID TX_CHAR_UUID = UUID.fromString("00000003-acce-423c-93fd-0c07a0051858");
    static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // BlueRange MessageType constants (BaseConnection / MeshAccessConnection)
    static final byte SPLIT_WRITE_CMD = 16;
    static final byte SPLIT_WRITE_CMD_END = 17;
    static final byte MSG_ENCRYPT_CUSTOM_START = 25;
    static final byte MSG_ENCRYPT_CUSTOM_ANONCE = 26;
    static final byte MSG_ENCRYPT_CUSTOM_SNONCE = 27;
    static final byte MSG_ENCRYPT_CUSTOM_DONE = 28;
    static final byte MSG_DEAD_DATA = 0x3D; // 61

    // BlueRange FmKeyId constants
    static final int FM_KEY_ZERO = 0;
    static final int FM_KEY_NODE = 1;
    static final int FM_KEY_NETWORK = 2;

    // BlueRange MeshAccessTunnelType constants
    static final byte TUNNEL_PEER_TO_PEER = 0;
    static final byte TUNNEL_REMOTE_MESH = 1;
    static final byte TUNNEL_LOCAL_MESH = 2;

    // Encryption state machine (MeshAccessConnection.h)
    static final int STATE_NOT_ENCRYPTED = 0;
    static final int STATE_WAITING_FOR_ANONCE = 1;
    static final int STATE_WAITING_FOR_SNONCE = 2;
    static final int STATE_WAITING_FOR_DONE = 3;
    static final int STATE_ENCRYPTED = 4;

    static final int MIC_LEN = 4;
    static final int NONCE_LEN = 8;
    static final int DEFAULT_MTU_PAYLOAD = 20;
    private static final int MAX_REASSEMBLY_BYTES = Photos.MAX_BYTES + 4096;

    static final byte[] DEAD_DATA_MAGIC = new byte[] {
        (byte) 0xDE, (byte) 0xAD, (byte) 0xDA, (byte) 0xDA,
        (byte) 0x00, (byte) 0xFF, (byte) 0x77, (byte) 0x33
    };

    private static final byte[] KEY_ZERO = new byte[16];
    private static final byte[] KEY_NETWORK = new byte[] {
        0x47, 0x72, 0x6F, 0x77, 0x74, 0x68, 0x49, 0x6E,
        0x74, 0x4D, 0x65, 0x73, 0x68, 0x4B, 0x65, 0x79
    };

    private static final SecureRandom RNG = new SecureRandom();
    private static final AtomicInteger NEXT_VIRTUAL_ID = new AtomicInteger(2000);

    interface PacketSender {
        void sendPacket(byte[] packet);
    }

    interface IncomingListener {
        void onIncomingTunnel(Pipe pipe, byte tunnelType, int fmKeyId);
    }

    static byte[] keyForId(int fmKeyId) {
        if (fmKeyId == FM_KEY_ZERO) return KEY_ZERO.clone();
        return KEY_NETWORK.clone();
    }

    /**
     * BlueRange MeshAccessConnection::DeriveKey:
     * Plaintext = [centralNodeId (2B LE) || nonce (8B) || zeros (6B)], encrypted with AES-128 ECB.
     */
    static byte[] deriveSessionKey(byte[] baseKey, short centralNodeId, byte[] nonce8) {
        byte[] pt = new byte[16];
        pt[0] = (byte) (centralNodeId & 0xFF);
        pt[1] = (byte) ((centralNodeId >>> 8) & 0xFF);
        if (nonce8 != null) {
            System.arraycopy(nonce8, 0, pt, 2, Math.min(NONCE_LEN, nonce8.length));
        }
        return aes128Block(baseKey, pt);
    }

    static Cipher initCipher(byte[] key16) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key16, "AES"));
            return c;
        } catch (Exception e) {
            throw new IllegalStateException("AES-128 unavailable", e);
        }
    }

    static byte[] aes128Block(byte[] key16, byte[] block16) {
        return aes128Block(initCipher(key16), block16);
    }

    static byte[] aes128Block(Cipher cipher, byte[] block16) {
        try {
            return cipher.doFinal(block16);
        } catch (Exception e) {
            throw new IllegalStateException("AES-128 unavailable", e);
        }
    }

    /**
     * Stateful MeshAccessConnection cryptographic session & packet splitter/reassembler.
     */
    static final class Session {
        final boolean isCentral;
        final short myNodeId;
        short partnerId;
        final short virtualPartnerId;
        int fmKeyId;
        byte tunnelType;
        volatile int state = STATE_NOT_ENCRYPTED;

        private byte[] baseKey;
        private byte[] sessionEncKey;
        private byte[] sessionDecKey;
        private Cipher encCipher;
        private Cipher decCipher;
        private final byte[] encNonce = new byte[NONCE_LEN];
        private final byte[] decNonce = new byte[NONCE_LEN];
        private int txCounter;
        private int rxCounter;
        private final ByteArrayOutputStream reassembly = new ByteArrayOutputStream();

        Session(boolean isCentral, short myNodeId, int fmKeyId, byte tunnelType) {
            this.isCentral = isCentral;
            this.myNodeId = myNodeId;
            this.fmKeyId = fmKeyId;
            this.tunnelType = tunnelType;
            this.baseKey = keyForId(fmKeyId);
            this.virtualPartnerId = (short) (NEXT_VIRTUAL_ID.getAndIncrement() & 0x7FFF);
        }

        synchronized byte[] startHandshake() {
            state = STATE_WAITING_FOR_ANONCE;
            ByteBuffer b = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);
            b.put(MSG_ENCRYPT_CUSTOM_START);
            b.putShort(myNodeId);
            b.putShort((short) 0);
            b.put((byte) 1); // version = 1
            b.putInt(fmKeyId);
            b.put((byte) ((tunnelType & 0x03) << 1));
            return b.array();
        }

        /**
         * Processes a handshake or DEAD_DATA control packet.
         * Returns a response packet to send back to the peer, or null if none needed.
         */
        synchronized byte[] onHandshakePacket(byte[] pkt) {
            if (pkt == null || pkt.length < 5) return null;
            if (!isCentral && state == STATE_NOT_ENCRYPTED && pkt[0] == MSG_ENCRYPT_CUSTOM_START) {
                if (pkt.length < 11) return null;
                ByteBuffer b = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN);
                b.get(); // type
                partnerId = b.getShort();
                b.getShort(); // receiver
                int ver = b.get() & 0xFF;
                if (ver != 1) return null;
                fmKeyId = b.getInt();
                int flags = b.get() & 0xFF;
                tunnelType = (byte) ((flags >>> 1) & 0x03);
                baseKey = keyForId(fmKeyId);

                RNG.nextBytes(decNonce);
                sessionDecKey = deriveSessionKey(baseKey, partnerId, decNonce);
                decCipher = initCipher(sessionDecKey);
                rxCounter = 0;
                state = STATE_WAITING_FOR_SNONCE;

                ByteBuffer out = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
                out.put(MSG_ENCRYPT_CUSTOM_ANONCE);
                out.putShort(myNodeId);
                out.putShort(virtualPartnerId);
                out.put(decNonce);
                return out.array();
            }

            if (isCentral && state == STATE_WAITING_FOR_ANONCE && pkt[0] == MSG_ENCRYPT_CUSTOM_ANONCE) {
                if (pkt.length < 13) return null;
                ByteBuffer b = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN);
                b.get(); // type
                partnerId = b.getShort();
                b.getShort(); // virtualPartnerId assigned by peripheral
                b.get(encNonce);
                sessionEncKey = deriveSessionKey(baseKey, myNodeId, encNonce);
                encCipher = initCipher(sessionEncKey);
                txCounter = 0;

                RNG.nextBytes(decNonce);
                sessionDecKey = deriveSessionKey(baseKey, myNodeId, decNonce);
                decCipher = initCipher(sessionDecKey);
                rxCounter = 0;
                state = STATE_WAITING_FOR_DONE;

                ByteBuffer sNoncePlain = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
                sNoncePlain.put(MSG_ENCRYPT_CUSTOM_SNONCE);
                sNoncePlain.putShort(myNodeId);
                sNoncePlain.putShort(partnerId);
                sNoncePlain.put(decNonce);
                return encryptPacket(sNoncePlain.array());
            }

            if (!isCentral && state == STATE_WAITING_FOR_SNONCE) {
                byte[] plain = decryptPacket(pkt);
                if (plain == null || plain.length < 13 || plain[0] != MSG_ENCRYPT_CUSTOM_SNONCE) {
                    return buildDeadDataPacket();
                }
                ByteBuffer b = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN);
                b.get(); // type
                short sender = b.getShort();
                if (sender != partnerId) return buildDeadDataPacket();
                b.getShort(); // receiver
                b.get(encNonce);
                sessionEncKey = deriveSessionKey(baseKey, partnerId, encNonce);
                encCipher = initCipher(sessionEncKey);
                txCounter = 0;
                state = STATE_ENCRYPTED;

                ByteBuffer donePlain = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
                donePlain.put(MSG_ENCRYPT_CUSTOM_DONE);
                donePlain.putShort(myNodeId);
                donePlain.putShort(partnerId);
                donePlain.put((byte) 0); // status OK
                return encryptPacket(donePlain.array());
            }

            if (isCentral && state == STATE_WAITING_FOR_DONE) {
                byte[] plain = decryptPacket(pkt);
                if (plain == null || plain.length < 6 || plain[0] != MSG_ENCRYPT_CUSTOM_DONE) {
                    return buildDeadDataPacket();
                }
                state = STATE_ENCRYPTED;
                return null;
            }
            return null;
        }

        synchronized byte[] encryptPacket(byte[] plain) {
            if (plain == null || encCipher == null) return null;
            int seq = txCounter++;
            byte[] cipher = xorKeystream(encCipher, encNonce, seq, plain, 0, plain.length);
            byte[] mic = computeMic(encCipher, encNonce, seq, cipher, 0, cipher.length);
            byte[] out = new byte[cipher.length + MIC_LEN];
            System.arraycopy(cipher, 0, out, 0, cipher.length);
            System.arraycopy(mic, 0, out, cipher.length, MIC_LEN);
            return out;
        }

        synchronized byte[] decryptPacket(byte[] packet) {
            if (packet == null || packet.length <= MIC_LEN || decCipher == null) return null;
            int cipherLen = packet.length - MIC_LEN;
            // Allow resync within a small forward window if a BLE notification was dropped
            for (int delta = 0; delta < 8; delta++) {
                int candidateSeq = rxCounter + delta;
                byte[] expectedMic = computeMic(decCipher, decNonce, candidateSeq, packet, 0, cipherLen);
                boolean match = true;
                for (int i = 0; i < MIC_LEN; i++) {
                    if (expectedMic[i] != packet[cipherLen + i]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    rxCounter = candidateSeq + 1;
                    return xorKeystream(decCipher, decNonce, candidateSeq, packet, 0, cipherLen);
                }
            }
            return null;
        }

        synchronized byte[] buildDeadDataPacket() {
            ByteBuffer b = ByteBuffer.allocate(5 + DEAD_DATA_MAGIC.length).order(ByteOrder.LITTLE_ENDIAN);
            b.put(MSG_DEAD_DATA);
            b.putShort(myNodeId);
            b.putShort(partnerId);
            b.put(DEAD_DATA_MAGIC);
            return b.array();
        }

        static boolean isDeadDataPacket(byte[] pkt) {
            if (pkt == null || pkt.length != 5 + DEAD_DATA_MAGIC.length || pkt[0] != MSG_DEAD_DATA) {
                return false;
            }
            for (int i = 0; i < DEAD_DATA_MAGIC.length; i++) {
                if (pkt[5 + i] != DEAD_DATA_MAGIC[i]) return false;
            }
            return true;
        }

        synchronized void onDeadDataReceived() {
            // Reset stream reassembly buffer so the next frame starts cleanly
            reassembly.reset();
        }

        static byte[][] splitPayload(byte[] data, int mtuPayload) {
            if (data == null || data.length == 0) return new byte[0][];
            int maxBody = Math.max(4, mtuPayload - MIC_LEN - 2);
            int count = (data.length + maxBody - 1) / maxBody;
            byte[][] chunks = new byte[count][];
            int offset = 0;
            for (int i = 0; i < count; i++) {
                int len = Math.min(maxBody, data.length - offset);
                boolean last = (i == count - 1);
                byte[] chunk = new byte[2 + len];
                chunk[0] = last ? SPLIT_WRITE_CMD_END : SPLIT_WRITE_CMD;
                chunk[1] = (byte) (i & 0xFF);
                System.arraycopy(data, offset, chunk, 2, len);
                chunks[i] = chunk;
                offset += len;
            }
            return chunks;
        }

        synchronized byte[] reassembleChunk(byte[] plainChunk) {
            if (plainChunk == null || plainChunk.length < 2) return null;
            byte type = plainChunk[0];
            if (type != SPLIT_WRITE_CMD && type != SPLIT_WRITE_CMD_END) {
                return plainChunk;
            }
            if (reassembly.size() + (plainChunk.length - 2) > MAX_REASSEMBLY_BYTES) {
                reassembly.reset();
                return null;
            }
            reassembly.write(plainChunk, 2, plainChunk.length - 2);
            if (type == SPLIT_WRITE_CMD_END) {
                byte[] full = reassembly.toByteArray();
                reassembly.reset();
                return full;
            }
            return null;
        }

        private static byte[] xorKeystream(Cipher cipher, byte[] nonce8, int seq, byte[] in, int off, int len) {
            byte[] out = new byte[len];
            byte[] block = new byte[16];
            int blockIdx = 0;
            for (int pos = 0; pos < len; pos += 16) {
                System.arraycopy(nonce8, 0, block, 0, NONCE_LEN);
                block[8] = (byte) (seq & 0xFF);
                block[9] = (byte) ((seq >>> 8) & 0xFF);
                block[10] = (byte) ((seq >>> 16) & 0xFF);
                block[11] = (byte) ((seq >>> 24) & 0xFF);
                block[12] = (byte) (blockIdx & 0xFF);
                block[13] = (byte) ((blockIdx >>> 8) & 0xFF);
                block[14] = 0;
                block[15] = 1;
                byte[] ks = aes128Block(cipher, block);
                int rem = Math.min(16, len - pos);
                for (int i = 0; i < rem; i++) {
                    out[pos + i] = (byte) (in[off + pos + i] ^ ks[i]);
                }
                blockIdx++;
            }
            return out;
        }

        private static byte[] computeMic(Cipher cipher, byte[] nonce8, int seq, byte[] cipherText, int off, int len) {
            byte[] mac = new byte[16];
            System.arraycopy(nonce8, 0, mac, 0, NONCE_LEN);
            mac[8] = (byte) (seq & 0xFF);
            mac[9] = (byte) ((seq >>> 8) & 0xFF);
            mac[10] = (byte) ((seq >>> 16) & 0xFF);
            mac[11] = (byte) ((seq >>> 24) & 0xFF);
            mac[12] = (byte) (len & 0xFF);
            mac[13] = (byte) ((len >>> 8) & 0xFF);
            mac[14] = 0x4D;
            mac[15] = 0x41;
            mac = aes128Block(cipher, mac);
            for (int pos = 0; pos < len; pos += 16) {
                int rem = Math.min(16, len - pos);
                for (int i = 0; i < rem; i++) {
                    mac[i] ^= cipherText[off + pos + i];
                }
                mac = aes128Block(cipher, mac);
            }
            return Arrays.copyOf(mac, MIC_LEN);
        }
    }

    /**
     * Adapts a packet-based MeshAccess.Session into an InputStream/OutputStream pair for Hub.addStreamLink().
     */
    static final class Pipe implements Closeable {
        final Session session;
        final InputStream in;
        final OutputStream out;
        private final PipedOutputStream inboundSink;
        private final PacketSender sender;
        private final Closeable rawClose;
        private volatile int mtuPayload = DEFAULT_MTU_PAYLOAD;
        private volatile boolean closed;

        Pipe(final Session session, final PacketSender sender, Closeable rawClose) throws IOException {
            this.session = session;
            this.sender = sender;
            this.rawClose = rawClose;
            this.inboundSink = new PipedOutputStream();
            this.in = new PipedInputStream(inboundSink, 65536);
            this.out = new OutputStream() {
                @Override public void write(int b) throws IOException {
                    write(new byte[] { (byte) b }, 0, 1);
                }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (closed) throw new IOException("MeshAccess pipe closed");
                    if (len <= 0) return;
                    byte[] slice = (off == 0 && len == b.length) ? b : Arrays.copyOfRange(b, off, off + len);
                    byte[][] chunks = Session.splitPayload(slice, mtuPayload);
                    for (byte[] chunk : chunks) {
                        byte[] enc = session.encryptPacket(chunk);
                        if (enc != null) sender.sendPacket(enc);
                    }
                }
                @Override public void close() throws IOException {
                    Pipe.this.close();
                }
            };
        }

        void setMtuPayload(int mtu) {
            this.mtuPayload = Math.max(DEFAULT_MTU_PAYLOAD, Math.min(512, mtu - 3));
        }

        int mtuPayload() {
            return mtuPayload;
        }

        void onIncomingPacket(byte[] packet, Runnable onHandshakeComplete) {
            if (closed || packet == null) return;
            if (Session.isDeadDataPacket(packet)) {
                session.onDeadDataReceived();
                return;
            }
            if (session.state != STATE_ENCRYPTED) {
                byte[] reply = session.onHandshakePacket(packet);
                if (reply != null) sender.sendPacket(reply);
                if (session.state == STATE_ENCRYPTED && onHandshakeComplete != null) {
                    onHandshakeComplete.run();
                }
                return;
            }
            byte[] plain = session.decryptPacket(packet);
            if (plain == null) {
                sender.sendPacket(session.buildDeadDataPacket());
                return;
            }
            byte[] full = session.reassembleChunk(plain);
            if (full != null && full.length > 0) {
                try {
                    inboundSink.write(full);
                    inboundSink.flush();
                } catch (IOException e) {
                    try { close(); } catch (IOException ignored) {}
                }
            }
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { inboundSink.close(); } catch (IOException ignored) {}
            try { in.close(); } catch (IOException ignored) {}
            if (rawClose != null) {
                try { rawClose.close(); } catch (IOException ignored) {}
            }
        }
    }

    // --------------------------------------------------- BLE GATT Server & Client (0xFE12)

    private final Context ctx;
    private final IncomingListener listener;
    private final Map<String, Pipe> serverPipes = new ConcurrentHashMap<String, Pipe>();
    private final Map<String, Integer> deviceMtus = new ConcurrentHashMap<String, Integer>();
    private final Map<String, Semaphore> serverGates = new ConcurrentHashMap<String, Semaphore>();
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic txChar;

    MeshAccess(Context ctx, IncomingListener listener) {
        this.ctx = ctx != null ? ctx.getApplicationContext() : null;
        this.listener = listener;
    }

    void startServer() {
        if (ctx == null || gattServer != null) return;
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null) return;
            BluetoothGattServer srv = bm.openGattServer(ctx, serverCallback);
            if (srv == null) return;
            BluetoothGattService svc = new BluetoothGattService(
                    SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            // RX Characteristic: SET_OPEN (PERMISSION_WRITE without encryption/MITM flags -> zero OS popup!)
            BluetoothGattCharacteristic rx = new BluetoothGattCharacteristic(
                    RX_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            // TX Characteristic: Notify + CCCD descriptor
            BluetoothGattCharacteristic tx = new BluetoothGattCharacteristic(
                    TX_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ
                            | BluetoothGattDescriptor.PERMISSION_WRITE);
            tx.addDescriptor(cccd);
            svc.addCharacteristic(rx);
            svc.addCharacteristic(tx);
            srv.addService(svc);
            this.txChar = tx;
            this.gattServer = srv;
        } catch (RuntimeException ignored) {
        }
    }

    void stopServer() {
        for (Pipe p : serverPipes.values()) {
            try { p.close(); } catch (IOException ignored) {}
        }
        serverPipes.clear();
        deviceMtus.clear();
        serverGates.clear();
        BluetoothGattServer srv = gattServer;
        gattServer = null;
        if (srv != null) {
            try { srv.close(); } catch (RuntimeException ignored) {}
        }
    }

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (device == null) return;
            String addr = device.getAddress();
            if (newState == BluetoothProfile.STATE_DISCONNECTED && addr != null) {
                deviceMtus.remove(addr);
                Semaphore gate = serverGates.remove(addr);
                if (gate != null && gate.availablePermits() == 0) gate.release();
                Pipe p = serverPipes.remove(addr);
                if (p != null) {
                    try { p.close(); } catch (IOException ignored) {}
                }
            }
        }

        @Override public void onNotificationSent(BluetoothDevice device, int status) {
            if (device == null || device.getAddress() == null) return;
            Semaphore gate = serverGates.get(device.getAddress());
            if (gate != null && gate.availablePermits() == 0) {
                gate.release();
            }
        }

        @Override public void onMtuChanged(BluetoothDevice device, int mtu) {
            if (device == null || device.getAddress() == null) return;
            deviceMtus.put(device.getAddress(), mtu);
            Pipe p = serverPipes.get(device.getAddress());
            if (p != null) p.setMtuPayload(mtu);
        }

        @Override public void onDescriptorWriteRequest(final BluetoothDevice device, int requestId,
                BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            BluetoothGattServer srv = gattServer;
            if (srv != null && responseNeeded) {
                try {
                    srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                } catch (RuntimeException ignored) {}
            }
            if (device != null && descriptor != null && CCCD_UUID.equals(descriptor.getUuid())) {
                ensureServerPipe(device);
            }
        }

        @Override public void onCharacteristicWriteRequest(final BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            BluetoothGattServer srv = gattServer;
            if (srv != null && responseNeeded) {
                try {
                    srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                } catch (RuntimeException ignored) {}
            }
            if (device == null || characteristic == null || !RX_CHAR_UUID.equals(characteristic.getUuid())) {
                return;
            }
            final Pipe pipe = ensureServerPipe(device);
            if (pipe == null || value == null) return;
            pipe.onIncomingPacket(value, new Runnable() {
                @Override public void run() {
                    if (listener != null) {
                        listener.onIncomingTunnel(pipe, pipe.session.tunnelType, pipe.session.fmKeyId);
                    }
                }
            });
        }
    };

    private Pipe ensureServerPipe(final BluetoothDevice device) {
        final String addr = device.getAddress();
        if (addr == null) return null;
        Pipe existing = serverPipes.get(addr);
        if (existing != null) return existing;
        try {
            final Semaphore gate = new Semaphore(1);
            serverGates.put(addr, gate);
            Session s = new Session(false, (short) (addr.hashCode() & 0x7FFF),
                    FM_KEY_NETWORK, TUNNEL_REMOTE_MESH);
            Pipe p = new Pipe(s, new PacketSender() {
                @Override public void sendPacket(byte[] packet) {
                    BluetoothGattServer srv = gattServer;
                    BluetoothGattCharacteristic tx = txChar;
                    if (srv == null || tx == null || packet == null) return;
                    boolean acquired = false;
                    try {
                        acquired = gate.tryAcquire(80, TimeUnit.MILLISECONDS);
                        synchronized (tx) {
                            tx.setValue(packet);
                            boolean ok = srv.notifyCharacteristicChanged(device, tx, false);
                            if (!ok && acquired && gate.availablePermits() == 0) {
                                gate.release();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        if (acquired && gate.availablePermits() == 0) gate.release();
                    }
                }
            }, new Closeable() {
                @Override public void close() {
                    serverPipes.remove(addr);
                    Semaphore g = serverGates.remove(addr);
                    if (g != null && g.availablePermits() == 0) g.release();
                    BluetoothGattServer srv = gattServer;
                    if (srv != null) {
                        try { srv.cancelConnection(device); } catch (RuntimeException ignored) {}
                    }
                }
            });
            Integer mtu = deviceMtus.get(addr);
            if (mtu != null) p.setMtuPayload(mtu);
            serverPipes.put(addr, p);
            return p;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Synchronous helper called from Bt.connect() worker thread to open a zero-popup
     * MeshAccess GATT tunnel (0xFE12) on API 23+ when L2CAP is unavailable or fails.
     */
    Pipe openTunnel(final BluetoothDevice device, final int fmKeyId, final byte tunnelType) {
        if (ctx == null || device == null) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final Pipe[] resultPipe = new Pipe[1];
        final Pipe[] createdPipe = new Pipe[1];
        final BluetoothGatt[] gattRef = new BluetoothGatt[1];
        final Semaphore writeGate = new Semaphore(1);

        BluetoothGattCallback cb = new BluetoothGattCallback() {
            private BluetoothGattCharacteristic rx;
            private Pipe pipe;
            private volatile int negotiatedMtu = DEFAULT_MTU_PAYLOAD + 3;

            @Override public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                        if (!gatt.requestMtu(247)) {
                            gatt.discoverServices();
                        }
                    } catch (RuntimeException e) {
                        latch.countDown();
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (writeGate.availablePermits() == 0) writeGate.release();
                    if (pipe != null) {
                        try { pipe.close(); } catch (IOException ignored) {}
                    }
                    try { gatt.close(); } catch (RuntimeException ignored) {}
                    latch.countDown();
                }
            }

            @Override public void onCharacteristicWrite(BluetoothGatt gatt,
                    BluetoothGattCharacteristic characteristic, int status) {
                if (writeGate.availablePermits() == 0) {
                    writeGate.release();
                }
            }

            @Override public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS && mtu > DEFAULT_MTU_PAYLOAD + 3) {
                    negotiatedMtu = mtu;
                    if (pipe != null) {
                        pipe.setMtuPayload(mtu);
                    }
                }
                try {
                    gatt.discoverServices();
                } catch (RuntimeException e) {
                    latch.countDown();
                }
            }

            @Override public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    latch.countDown();
                    return;
                }
                BluetoothGattService svc = gatt.getService(SERVICE_UUID);
                if (svc == null) {
                    latch.countDown();
                    return;
                }
                rx = svc.getCharacteristic(RX_CHAR_UUID);
                BluetoothGattCharacteristic tx = svc.getCharacteristic(TX_CHAR_UUID);
                if (rx == null || tx == null) {
                    latch.countDown();
                    return;
                }
                try {
                    Session session = new Session(true, (short) (RNG.nextInt(0x7FFE) + 1),
                            fmKeyId, tunnelType);
                    pipe = new Pipe(session, new PacketSender() {
                        @Override public void sendPacket(byte[] packet) {
                            if (rx == null || packet == null) return;
                            boolean acquired = false;
                            try {
                                acquired = writeGate.tryAcquire(80, TimeUnit.MILLISECONDS);
                                synchronized (rx) {
                                    rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                                    rx.setValue(packet);
                                    boolean ok = gatt.writeCharacteristic(rx);
                                    if (!ok && acquired && writeGate.availablePermits() == 0) {
                                        writeGate.release();
                                    }
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (RuntimeException e) {
                                if (acquired && writeGate.availablePermits() == 0) writeGate.release();
                            }
                        }
                    }, new Closeable() {
                        @Override public void close() {
                            if (writeGate.availablePermits() == 0) writeGate.release();
                            try { gatt.disconnect(); } catch (RuntimeException ignored) {}
                            try { gatt.close(); } catch (RuntimeException ignored) {}
                        }
                    });
                    pipe.setMtuPayload(negotiatedMtu);
                    createdPipe[0] = pipe;
                    gatt.setCharacteristicNotification(tx, true);
                    BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD_UUID);
                    if (cccd != null) {
                        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(cccd);
                    } else {
                        pipe.sender.sendPacket(session.startHandshake());
                    }
                } catch (Exception e) {
                    latch.countDown();
                }
            }

            @Override public void onDescriptorWrite(BluetoothGatt gatt,
                    BluetoothGattDescriptor descriptor, int status) {
                if (pipe != null) {
                    pipe.sender.sendPacket(pipe.session.startHandshake());
                }
            }

            @Override public void onCharacteristicChanged(BluetoothGatt gatt,
                    BluetoothGattCharacteristic characteristic) {
                if (pipe == null || characteristic == null) return;
                byte[] val = characteristic.getValue();
                pipe.onIncomingPacket(val, new Runnable() {
                    @Override public void run() {
                        resultPipe[0] = pipe;
                        latch.countDown();
                    }
                });
            }
        };

        try {
            BluetoothGatt g = Build.VERSION.SDK_INT >= 23
                    ? device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                    : device.connectGatt(ctx, false, cb);
            gattRef[0] = g;
            if (g == null) return null;
            boolean ok = latch.await(5000, TimeUnit.MILLISECONDS) && resultPipe[0] != null;
            if (!ok) {
                if (createdPipe[0] != null) {
                    try { createdPipe[0].close(); } catch (IOException ignored) {}
                }
                try { g.disconnect(); } catch (RuntimeException ignored) {}
                try { g.close(); } catch (RuntimeException ignored) {}
                return null;
            }
            return resultPipe[0];
        } catch (InterruptedException e) {
            if (createdPipe[0] != null) {
                try { createdPipe[0].close(); } catch (IOException ignored) {}
            }
            if (gattRef[0] != null) {
                try { gattRef[0].close(); } catch (RuntimeException ignored) {}
            }
            return null;
        } catch (RuntimeException e) {
            if (createdPipe[0] != null) {
                try { createdPipe[0].close(); } catch (IOException ignored) {}
            }
            return null;
        }
    }
}
