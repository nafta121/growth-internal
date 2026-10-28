package com.growthinternal.app;

import static org.junit.Assert.*;

import java.io.Closeable;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests the v0.13.0 multi-transport offline additions:
 * - 4:1 IMA ADPCM speech codec (Adpcm)
 * - BLE 20-byte beacon packing & UTF-8 boundary safety (Ble)
 * - Local Hotspot/LAN UDP discovery packets (Lan)
 * - Cross-transport Hub relay between a high-speed TCP peer and a low-bandwidth
 *   Bluetooth RFCOMM stream link with automatic AUDIO <-> AUDIO_ADPCM transcoding.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AdpcmTest {

    @Test public void adpcmCompressesFourToOneAndReconstructsSpeechWave() {
        int samples = 320; // 20 ms @ 16 kHz = 640 bytes PCM
        byte[] pcm = new byte[samples * 2];
        ByteBuffer buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            short s = (short) (Math.sin(2.0 * Math.PI * 440.0 * i / 16000.0) * 12000);
            buf.putShort(s);
        }

        byte[] encoded = Adpcm.encode(pcm);
        assertEquals(164, encoded.length); // 4-byte header + 160 bytes of 4:1 ADPCM nibbles

        byte[] decoded = Adpcm.decode(encoded);
        assertEquals(640, decoded.length);

        ByteBuffer origBuf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer decBuf = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN);
        double sumSqErr = 0;
        for (int i = 0; i < samples; i++) {
            int o = origBuf.getShort();
            int d = decBuf.getShort();
            double diff = o - d;
            sumSqErr += diff * diff;
        }
        double rmsError = Math.sqrt(sumSqErr / samples);
        assertTrue("ADPCM RMS error too high: " + rmsError, rmsError < 1200.0);
    }

    @Test public void bleBeaconPackAndUnpackRoundTrip() {
        Presence p = new Presence();
        p.status = Presence.SOS;
        p.battery = 87;
        p.charging = true;
        p.hasLocation = true;
        p.lat = -6.59714f;
        p.lon = 106.80603f;

        byte[] packed = Ble.pack("PIC_Lapangan_Utama", p, true);
        assertTrue("BLE service data must fit in 20 bytes", packed.length <= 20);

        Ble.Beacon b = Ble.unpack(packed, -68);
        assertNotNull(b);
        assertEquals("PIC_Lapang", b.name);
        assertEquals(Presence.SOS, b.presence.status);
        assertEquals(87, b.presence.battery);
        assertTrue(b.presence.charging);
        assertTrue(b.presence.hasLocation);
        assertTrue(b.inGroup);
        assertEquals(-68, b.rssi);
        assertEquals(-6.59714, b.presence.lat, 0.0001);
        assertEquals(106.80603, b.presence.lon, 0.0001);
    }

    @Test public void lanDiscoveryPacketRoundTrip() throws Exception {
        InetAddress addr = InetAddress.getByName("192.168.43.10");
        byte[] pkt = Lan.encodePacket("Pos 1 - Raka", 8988, true, 4);
        Lan.Peer peer = Lan.decodePacket(pkt, pkt.length, addr);
        assertNotNull(peer);
        assertEquals("Pos 1 - Raka", peer.name);
        assertEquals(addr, peer.address);
        assertEquals(8988, peer.port);
        assertTrue(peer.inGroup);
        assertEquals(4, peer.memberCount);
    }

    @Test public void autoConnectDecisionResolvesSplitBrainAndTieBreaker() {
        // 1. Standalone phone meets an active group owner -> auto-join (1)
        assertEquals(1, MainActivity.autoConnectDecision(false, 0, "Andi", true, 2, "Zaki"));
        // 2. Two standalone phones meet -> lexicographically smaller name becomes owner (2)
        assertEquals(2, MainActivity.autoConnectDecision(false, 0, "Andi", false, 0, "Budi"));
        assertEquals(0, MainActivity.autoConnectDecision(false, 0, "Budi", false, 0, "Andi"));
        // 3. Split-brain: two empty owners (count=1) meet -> larger name yields & joins smaller name (1)
        assertEquals(1, MainActivity.autoConnectDecision(true, 1, "Budi", true, 1, "Andi"));
        assertEquals(0, MainActivity.autoConnectDecision(true, 1, "Andi", true, 1, "Budi"));
        // 4. Empty owner meets an owner with real members (count=3 > 1) -> empty owner joins larger group (1)
        assertEquals(1, MainActivity.autoConnectDecision(true, 1, "Andi", true, 3, "Zaki"));
    }

    @Test public void crossTransportRelayBetweenTcpAndBluetoothStream() throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        int port = 19123;
        HubTest.Rec ro = new HubTest.Rec();
        HubTest.Rec rTcp = new HubTest.Rec();
        HubTest.Rec rBt = new HubTest.Rec();

        Hub owner = new Hub("PIC", null, ro).port(port);
        owner.startServer(lo);
        Thread.sleep(200);

        Hub tcpPeer = new Hub("Tim WiFi", null, rTcp).port(port);
        tcpPeer.startClient(lo);
        HubTest.waitFor(ro, "join:Tim WiFi");

        // Simulate a Bluetooth RFCOMM connection between owner and btPeer using piped streams
        final PipedOutputStream ownerOut = new PipedOutputStream();
        final PipedInputStream btIn = new PipedInputStream(ownerOut, 65536);
        final PipedOutputStream btOut = new PipedOutputStream();
        final PipedInputStream ownerIn = new PipedInputStream(btOut, 65536);
        Closeable rawClose = new Closeable() {
            @Override public void close() throws IOException {
                ownerOut.close();
                btOut.close();
                btIn.close();
                ownerIn.close();
            }
        };

        Hub btPeer = new Hub("Tim BT", null, rBt).asOwner(false);
        owner.addStreamLink(ownerIn, ownerOut, rawClose, true);
        btPeer.addStreamLink(btIn, btOut, rawClose, true);

        HubTest.waitFor(ro, "join:Tim BT");
        HubTest.waitFor(rTcp, "join:Tim BT");
        HubTest.waitFor(rBt, "join:Tim WiFi");

        // Text from Bluetooth peer reaches Wi-Fi TCP peer
        btPeer.sendText("pos 2 aman via BT");
        HubTest.waitFor(rTcp, "text:Tim BT:pos 2 aman via BT");

        // 640-byte PCM audio from Bluetooth peer is compressed to 160-byte ADPCM over the
        // BT link, decoded back to 640-byte PCM by owner Hub, and relayed to Wi-Fi TCP peer!
        btPeer.sendAudio(new byte[640]);
        HubTest.waitFor(ro, "audio:Tim BT:640");
        HubTest.waitFor(rTcp, "audio:Tim BT:640");

        // 640-byte PCM audio from Wi-Fi TCP peer is compressed to ADPCM over the BT link
        // and decoded back to 640-byte PCM at btPeer!
        tcpPeer.sendAudio(new byte[640]);
        HubTest.waitFor(rBt, "audio:Tim WiFi:640");

        btPeer.stop();
        tcpPeer.stop();
        owner.stop();
    }

    @Test public void bleBeaconWithL2capPsmAndMeshPacketRoundTrip() {
        Presence p = new Presence();
        p.status = Presence.SAFE;
        p.battery = 92;
        p.hasLocation = true;
        p.lat = -6.2000f;
        p.lon = 106.8166f;

        byte[] withPsm = Ble.pack("Andi_PIC", p, true, 0x0085);
        assertTrue("BLE service data with L2CAP PSM must fit in 22 bytes", withPsm.length <= 22);
        Ble.Beacon b = Ble.unpack(withPsm, -61);
        assertNotNull(b);
        assertEquals("Andi_PIC", b.name);
        assertEquals(0x0085, b.psm);
        assertTrue(b.inGroup);

        // BLE Mesh packet with TTL=5 and deduplication
        byte[] meshRaw = Ble.packMesh(0x1234, Ble.DEFAULT_TTL, Ble.MESH_TEXT,
                "Pos 3", "Aman".getBytes(Hub.UTF8));
        assertNotNull(meshRaw);
        assertTrue(meshRaw.length <= 22);
        Ble.MeshPacket pkt = Ble.unpackMesh(meshRaw);
        assertNotNull(pkt);
        assertEquals(0x1234, pkt.msgId);
        assertEquals(5, pkt.ttl);
        assertEquals(Ble.MESH_TEXT, pkt.type);
        assertEquals("Pos 3", pkt.sender);
        assertEquals("Aman", new String(pkt.body, Hub.UTF8));

        Ble ble = new Ble(null);
        assertTrue(ble.recordSeenMesh(0x1234));
        assertFalse(ble.recordSeenMesh(0x1234)); // duplicate dropped
    }

    @Test public void automaticMultiHopRelayAcrossThreeHubs() throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        int port = 19129;
        HubTest.Rec rA = new HubTest.Rec();
        HubTest.Rec rB = new HubTest.Rec();
        HubTest.Rec rC = new HubTest.Rec();

        // Hub A: connected to Hub B via TCP (e.g. Wi-Fi Aware / Hotspot)
        Hub hubA = new Hub("Pos 1 (Aware)", null, rA).port(port);
        hubA.startServer(lo);
        Thread.sleep(200);

        // Hub B: middle node (client of Hub A over TCP, and peer to Hub C over BLE L2CAP stream)
        Hub hubB = new Hub("Pos 2 (Relay)", null, rB).port(port);
        hubB.startClient(lo);
        HubTest.waitFor(rA, "join:Pos 2 (Relay)");
        HubTest.waitFor(rB, "join:Pos 1 (Aware)");

        // Hub C connects only to Hub B (2 hops away from Hub A)
        final PipedOutputStream bOut = new PipedOutputStream();
        final PipedInputStream cIn = new PipedInputStream(bOut, 65536);
        final PipedOutputStream cOut = new PipedOutputStream();
        final PipedInputStream bIn = new PipedInputStream(cOut, 65536);
        Closeable rawClose = new Closeable() {
            @Override public void close() throws IOException {
                bOut.close();
                cOut.close();
                cIn.close();
                bIn.close();
            }
        };

        Hub hubC = new Hub("Pos 3 (Mesh)", null, rC).asOwner(false);
        hubB.addStreamLink(bIn, bOut, rawClose, true);
        hubC.addStreamLink(cIn, cOut, rawClose, true);

        // Automatic multi-hop discovery: Pos 1 learns Pos 3, and Pos 3 learns Pos 1!
        HubTest.waitFor(rA, "join:Pos 3 (Mesh)");
        HubTest.waitFor(rC, "join:Pos 1 (Aware)");

        // Multi-hop chat & voice from Pos 3 -> Pos 2 -> Pos 1
        hubC.sendText("Rotasi dari Pos 3");
        HubTest.waitFor(rA, "text:Pos 3 (Mesh):Rotasi dari Pos 3");
        hubC.sendAudio(new byte[640]);
        HubTest.waitFor(rA, "audio:Pos 3 (Mesh):640");

        // Multi-hop chat & voice from Pos 1 -> Pos 2 -> Pos 3
        hubA.sendText("Diterima di Pos 1");
        HubTest.waitFor(rC, "text:Pos 1 (Aware):Diterima di Pos 1");
        hubA.sendAudio(new byte[640]);
        HubTest.waitFor(rC, "audio:Pos 1 (Aware):640");

        hubC.stop();
        hubB.stop();
        hubA.stop();
    }
}
