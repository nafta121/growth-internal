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

    @Test public void boundedMediaAgeDropsStaleAudioAndAudioConcealmentFades() throws Exception {
        // 1. Packet Loss Concealment (PLC) fades 75% -> 25% smoothly
        byte[] prev = new byte[640];
        ByteBuffer b = ByteBuffer.wrap(prev).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 320; i++) b.putShort((short) 10000);
        byte[] concealed = Voice.concealFrame(prev);
        assertEquals(640, concealed.length);
        ByteBuffer cb = ByteBuffer.wrap(concealed).order(ByteOrder.LITTLE_ENDIAN);
        short first = cb.getShort(0);
        short last = cb.getShort(638);
        assertTrue("First PLC sample should be ~7500, got " + first, first >= 7000 && first <= 8000);
        assertTrue("Last PLC sample should be ~2500, got " + last, last >= 2000 && last <= 3000);

        // 2. BoundedMediaAge drops audio older than MAX_AUDIO_AGE_MS (350 ms)
        final PipedOutputStream aOut = new PipedOutputStream();
        final PipedInputStream bIn = new PipedInputStream(aOut, 65536);
        final PipedOutputStream bOut = new PipedOutputStream();
        final PipedInputStream aIn = new PipedInputStream(bOut, 65536);
        Closeable rawClose = new Closeable() {
            @Override public void close() throws IOException {
                aOut.close(); bOut.close(); aIn.close(); bIn.close();
            }
        };
        final HubTest.Rec rB = new HubTest.Rec();
        Link linkA = new Link(aIn, aOut, rawClose, false, new Link.Listener() {
            @Override public void onFrame(Link link, Frame frame) {}
            @Override public void onClosed(Link link) {}
        });
        Link linkB = new Link(bIn, bOut, rawClose, false, new Link.Listener() {
            @Override public void onFrame(Link link, Frame frame) {
                if (frame.type == Frame.AUDIO) rB.onAudio(frame.sender, frame.payload);
                else if (frame.type == Frame.TEXT) rB.onText(frame.sender, new String(frame.payload, Hub.UTF8));
            }
            @Override public void onClosed(Link link) {}
        });
        linkA.start();
        linkB.start();

        long staleTime = System.currentTimeMillis() - (Link.MAX_AUDIO_AGE_MS + 500L);
        linkA.send(new Frame(Frame.AUDIO, "Andi", new byte[640], staleTime));
        linkA.send(new Frame(Frame.AUDIO, "Andi", new byte[320], System.currentTimeMillis()));
        linkA.send(new Frame(Frame.TEXT, "Andi", "ok".getBytes(Hub.UTF8), staleTime));

        HubTest.waitFor(rB, "audio:Andi:320");
        HubTest.waitFor(rB, "text:Andi:ok");
        assertFalse("Stale 640-byte audio frame should have been dropped",
                rB.log.contains("audio:Andi:640"));

        linkA.close();
        linkB.close();
    }

    @Test public void storeAndForwardReplaysChatAnnouncementsAndVoiceNotesToLateJoiner() throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        int port = 19135;
        HubTest.Rec rOwner = new HubTest.Rec();
        HubTest.Rec rBudi = new HubTest.Rec();
        HubTest.Rec rSari = new HubTest.Rec();

        Hub owner = new Hub("PIC", null, rOwner).port(port);
        owner.startServer(lo);
        Thread.sleep(200);

        Hub budi = new Hub("Budi", null, rBudi).port(port);
        budi.startClient(lo);
        HubTest.waitFor(rOwner, "join:Budi");

        // PIC and Budi send messages, an announcement, and a voice note BEFORE Sari joins
        owner.sendAnnouncement("Rotasi ke Pos 2 pukul 14:00");
        budi.sendText("Pos 1 siap bergeser");
        byte[] vnAdpcm = Adpcm.encode(new byte[Voice.RATE * 2]); // 1-second voice note
        owner.sendVoiceNote(vnAdpcm);

        HubTest.waitFor(rBudi, "announce:PIC:Rotasi ke Pos 2 pukul 14:00");
        HubTest.waitFor(rOwner, "text:Budi:Pos 1 siap bergeser");
        HubTest.waitFor(rBudi, "voicenote:PIC:" + vnAdpcm.length);

        // Sari walks into range later and joins the group: Store-and-Forward replays all 3!
        Hub sari = new Hub("Sari", null, rSari).port(port);
        sari.startClient(lo);
        HubTest.waitFor(rSari, "join:Budi");
        HubTest.waitFor(rSari, "announce:PIC:Rotasi ke Pos 2 pukul 14:00");
        HubTest.waitFor(rSari, "text:Budi:Pos 1 siap bergeser");
        HubTest.waitFor(rSari, "voicenote:PIC:" + vnAdpcm.length);

        sari.stop();
        budi.stop();
        owner.stop();
    }

    @Test public void offlineApkShareServesHtmlAndApkBinaryWithPortFallback() throws Exception {
        java.io.File fakeApk = java.nio.file.Files.createTempFile("growth", ".apk").toFile();
        byte[] payload = "PK\003\004GrowthInternalOfflineApkPayload".getBytes(Hub.UTF8);
        java.nio.file.Files.write(fakeApk.toPath(), payload);

        ApkShare s1 = new ApkShare();
        ApkShare s2 = new ApkShare();
        try {
            String url1 = s1.start(fakeApk);
            String url2 = s2.start(fakeApk); // port 8989 is busy, must fall back to ephemeral port
            assertNotNull(url1);
            assertNotNull(url2);
            assertTrue(s1.port() > 0);
            assertTrue(s2.port() > 0);
            assertNotEquals(s1.port(), s2.port());

            String landing = new String(httpGet(s1.port(), "/"), Hub.UTF8);
            assertTrue(landing.contains("HTTP/1.1 200 OK"));
            assertTrue(landing.contains("/growth-internal.apk"));

            byte[] dl = httpGet(s2.port(), "/growth-internal.apk");
            String dlStr = new String(dl, Hub.UTF8);
            assertTrue(dlStr.contains("application/vnd.android.package-archive"));
            assertTrue(dlStr.endsWith("PK\003\004GrowthInternalOfflineApkPayload"));
        } finally {
            s1.stop();
            s2.stop();
            fakeApk.delete();
        }
    }

    private static byte[] httpGet(int port, String path) throws Exception {
        java.net.Socket s = new java.net.Socket("127.0.0.1", port);
        try {
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(Hub.UTF8));
            s.getOutputStream().flush();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = s.getInputStream().read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            s.close();
        }
    }

    @Test public void meshAccessFourWayHandshakeEncryptionDeadDataAndPacketSplitting() {
        MeshAccess.Session central = new MeshAccess.Session(
                true, (short) 101, MeshAccess.FM_KEY_NETWORK, MeshAccess.TUNNEL_REMOTE_MESH);
        MeshAccess.Session periph = new MeshAccess.Session(
                false, (short) 202, MeshAccess.FM_KEY_ZERO, MeshAccess.TUNNEL_PEER_TO_PEER);

        // 1. 4-Way Handshake: START -> ANONCE -> SNONCE (encrypted+MIC) -> DONE (encrypted+MIC)
        byte[] startPkt = central.startHandshake();
        assertEquals(MeshAccess.STATE_WAITING_FOR_ANONCE, central.state);

        byte[] aNoncePkt = periph.onHandshakePacket(startPkt);
        assertNotNull(aNoncePkt);
        assertEquals(MeshAccess.STATE_WAITING_FOR_SNONCE, periph.state);
        assertEquals(MeshAccess.FM_KEY_NETWORK, periph.fmKeyId);
        assertEquals(MeshAccess.TUNNEL_REMOTE_MESH, periph.tunnelType);

        byte[] sNoncePkt = central.onHandshakePacket(aNoncePkt);
        assertNotNull(sNoncePkt);
        assertEquals(MeshAccess.STATE_WAITING_FOR_DONE, central.state);

        byte[] donePkt = periph.onHandshakePacket(sNoncePkt);
        assertNotNull(donePkt);
        assertEquals(MeshAccess.STATE_ENCRYPTED, periph.state);

        byte[] afterDone = central.onHandshakePacket(donePkt);
        assertNull(afterDone);
        assertEquals(MeshAccess.STATE_ENCRYPTED, central.state);

        // 2. Packet splitting & encrypted transfer + 4-byte MIC verification
        byte[] message = "Instruksi rotasi Pos 1 ke Pos 4 via BlueRange MeshAccess".getBytes(Hub.UTF8);
        byte[][] chunks = MeshAccess.Session.splitPayload(message, 20);
        assertTrue("Message must be split into multiple BLE MTU chunks", chunks.length > 3);

        byte[] reassembled = null;
        for (byte[] chunk : chunks) {
            byte[] encrypted = central.encryptPacket(chunk);
            assertNotNull(encrypted);
            assertEquals(chunk.length + MeshAccess.MIC_LEN, encrypted.length);
            byte[] decrypted = periph.decryptPacket(encrypted);
            assertNotNull(decrypted);
            reassembled = periph.reassembleChunk(decrypted);
        }
        assertNotNull(reassembled);
        assertEquals("Instruksi rotasi Pos 1 ke Pos 4 via BlueRange MeshAccess",
                new String(reassembled, Hub.UTF8));

        // 3. Corrupted packet triggers MIC rejection -> DEAD_DATA magic + forward window resync
        byte[] corrupted = central.encryptPacket(new byte[] { 1, 2, 3, 4 });
        corrupted[0] ^= (byte) 0xFF;
        assertNull("Tampered ciphertext must fail MIC check", periph.decryptPacket(corrupted));
        byte[] deadData = periph.buildDeadDataPacket();
        assertTrue(MeshAccess.Session.isDeadDataPacket(deadData));

        // Next valid packet resynchronizes sequence counter within forward window automatically
        byte[] nextValid = central.encryptPacket("ResyncOK".getBytes(Hub.UTF8));
        byte[] recovered = periph.decryptPacket(nextValid);
        assertNotNull("Next valid packet must resync and decrypt cleanly", recovered);
        assertEquals("ResyncOK", new String(recovered, Hub.UTF8));
    }

    @Test public void zeroActionPassiveRelayOverEncryptedMeshAccessPipesAndClusterIdAntiLoop() throws Exception {
        HubTest.Rec rA = new HubTest.Rec();
        HubTest.Rec rC = new HubTest.Rec();

        Hub hubA = new Hub("Pos 1", null, rA).asOwner(true);
        // Untouched standby device running a Zero-Action Passive Relay Hub (NOOP_CALLBACK)
        Hub passiveRelay = new Hub("Standby#relay", null, Hub.NOOP_CALLBACK).asOwner(true);
        Hub hubC = new Hub("Pos 3", null, rC).asOwner(false);

        int initialCidA = hubA.clusterId();
        int initialCidC = hubC.clusterId();
        assertTrue(initialCidA != 0 && initialCidC != 0);

        // Connect Pos 1 <---> Passive Relay via encrypted MeshAccess Pipe (FM_KEY_ZERO blind relay)
        MeshAccess.Pipe[] linkAB = createPairedMeshAccessPipes(MeshAccess.FM_KEY_ZERO);
        hubA.addStreamLink(linkAB[0].in, linkAB[0].out, linkAB[0], true);
        passiveRelay.addStreamLink(linkAB[1].in, linkAB[1].out, linkAB[1], true);

        // Connect Passive Relay <---> Pos 3 via encrypted MeshAccess Pipe
        MeshAccess.Pipe[] linkBC = createPairedMeshAccessPipes(MeshAccess.FM_KEY_ZERO);
        passiveRelay.addStreamLink(linkBC[0].in, linkBC[0].out, linkBC[0], true);
        hubC.addStreamLink(linkBC[1].in, linkBC[1].out, linkBC[1], true);

        // Pos 1 and Pos 3 discover each other through the passive relay and exchange chat + ADPCM voice!
        HubTest.waitFor(rA, "join:Pos 3");
        HubTest.waitFor(rC, "join:Pos 1");

        hubC.sendText("Laporan dari Pos 3 lewat Passive Relay");
        HubTest.waitFor(rA, "text:Pos 3:Laporan dari Pos 3 lewat Passive Relay");

        hubA.sendAudio(new byte[640]);
        HubTest.waitFor(rC, "audio:Pos 1:640");

        // Wait briefly for CLUSTER_UPDATE frames to converge across the 3-node tree
        for (int i = 0; i < 20 && (hubA.clusterId() != hubC.clusterId()); i++) {
            Thread.sleep(50);
        }
        assertEquals("All nodes in the connected tree must converge to one BlueRange ClusterId",
                hubA.clusterId(), hubC.clusterId());
        assertEquals(hubA.clusterId(), passiveRelay.clusterId());

        // ClusterId anti-loop: autoConnectDecision returns 0 when two nodes share the same ClusterId!
        int convergedCid = hubA.clusterId();
        assertEquals(0, MainActivity.autoConnectDecision(
                true, 3, "Pos 1", convergedCid, true, 3, "Pos 3", convergedCid));

        // BLE Beacon & LAN discovery packets preserve ClusterId within the 22-byte BLE limit
        Presence p = new Presence();
        p.status = Presence.SAFE;
        p.battery = 80;
        byte[] bleRaw = Ble.pack("Pos 1", p, true, 0x0085, convergedCid);
        assertTrue("BLE beacon with PSM + ClusterId must fit in 22 bytes", bleRaw.length <= 22);
        Ble.Beacon unpackedBle = Ble.unpack(bleRaw, -60);
        assertNotNull(unpackedBle);
        assertEquals(convergedCid, unpackedBle.clusterId);
        assertEquals(0x0085, unpackedBle.psm);

        byte[] lanRaw = Lan.encodePacket("Pos 1", 8988, true, 3, convergedCid);
        Lan.Peer unpackedLan = Lan.decodePacket(lanRaw, lanRaw.length, InetAddress.getByName("192.168.43.1"));
        assertNotNull(unpackedLan);
        assertEquals(convergedCid, unpackedLan.clusterId);

        // Self-healing: when passiveRelay stops, lossCounter increments and ClusterId regenerates
        passiveRelay.stop();
        for (int i = 0; i < 20 && hubC.lossCounter() == 0; i++) {
            Thread.sleep(50);
        }
        assertTrue("Disconnection must increment lossCounter for self-healing", hubC.lossCounter() > 0);

        hubC.stop();
        hubA.stop();
    }

    private static MeshAccess.Pipe[] createPairedMeshAccessPipes(int fmKeyId) throws IOException {
        MeshAccess.Session sCentral = new MeshAccess.Session(
                true, (short) 11, fmKeyId, MeshAccess.TUNNEL_REMOTE_MESH);
        MeshAccess.Session sPeriph = new MeshAccess.Session(
                false, (short) 22, fmKeyId, MeshAccess.TUNNEL_REMOTE_MESH);
        final MeshAccess.Pipe[] pair = new MeshAccess.Pipe[2];
        Closeable closeBoth = new Closeable() {
            @Override public void close() throws IOException {
                if (pair[0] != null) pair[0].close();
                if (pair[1] != null) pair[1].close();
            }
        };
        pair[0] = new MeshAccess.Pipe(sCentral, new MeshAccess.PacketSender() {
            @Override public void sendPacket(byte[] packet) {
                if (pair[1] != null) pair[1].onIncomingPacket(packet, null);
            }
        }, closeBoth);
        pair[1] = new MeshAccess.Pipe(sPeriph, new MeshAccess.PacketSender() {
            @Override public void sendPacket(byte[] packet) {
                if (pair[0] != null) pair[0].onIncomingPacket(packet, null);
            }
        }, closeBoth);
        pair[0].setMtuPayload(27);
        pair[1].setMtuPayload(27);
        pair[1].onIncomingPacket(sCentral.startHandshake(), null);
        assertEquals(MeshAccess.STATE_ENCRYPTED, sCentral.state);
        assertEquals(MeshAccess.STATE_ENCRYPTED, sPeriph.state);
        return pair;
    }
}
