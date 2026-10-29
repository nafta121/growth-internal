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

        // BLE Mesh packet with TTL=15 and deduplication
        byte[] meshRaw = Ble.packMesh(0x1234, Ble.DEFAULT_TTL, Ble.MESH_TEXT,
                "Pos 3", "Aman".getBytes(Hub.UTF8));
        assertNotNull(meshRaw);
        assertTrue(meshRaw.length <= 22);
        Ble.MeshPacket pkt = Ble.unpackMesh(meshRaw);
        assertNotNull(pkt);
        assertEquals(0x1234, pkt.msgId);
        assertEquals(15, pkt.ttl);
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

    @Test public void dynamicClusterSizePropagationAndCoordinatorLifecycle() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger clusterChanges =
                new java.util.concurrent.atomic.AtomicInteger();
        Runnable onChanged = new Runnable() {
            @Override public void run() { clusterChanges.incrementAndGet(); }
        };

        // 1. MeshCoordinator passive relay hub lifecycle
        MeshCoordinator coord = new MeshCoordinator();
        assertEquals(0, coord.activeClusterId(null));
        Hub relay = coord.ensureRelayHub("Standby", onChanged);
        assertNotNull(relay);
        assertTrue(coord.activeClusterId(null) != 0);
        coord.stopRelayHub();
        assertEquals(0, coord.activeClusterId(null));

        // 2. Dynamic 3-node cluster (A-B-C) vs 2-node cluster (D-E) collision resolution
        HubTest.Rec rA = new HubTest.Rec();
        HubTest.Rec rB = new HubTest.Rec();
        HubTest.Rec rC = new HubTest.Rec();
        HubTest.Rec rD = new HubTest.Rec();
        HubTest.Rec rE = new HubTest.Rec();

        Hub hubA = new Hub("Node A", null, rA).asOwner(true).onClusterChanged(onChanged);
        Hub hubB = new Hub("Node B", null, rB).asOwner(true).onClusterChanged(onChanged);
        Hub hubC = new Hub("Node C", null, rC).asOwner(true).onClusterChanged(onChanged);

        MeshAccess.Pipe[] ab = createPairedMeshAccessPipes(MeshAccess.FM_KEY_NETWORK);
        hubA.addStreamLink(ab[0].in, ab[0].out, ab[0], true);
        hubB.addStreamLink(ab[1].in, ab[1].out, ab[1], true);
        HubTest.waitFor(rA, "join:Node B");

        MeshAccess.Pipe[] bc = createPairedMeshAccessPipes(MeshAccess.FM_KEY_NETWORK);
        hubB.addStreamLink(bc[0].in, bc[0].out, bc[0], true);
        hubC.addStreamLink(bc[1].in, bc[1].out, bc[1], true);
        HubTest.waitFor(rA, "join:Node C");
        HubTest.waitFor(rC, "join:Node A");

        for (int i = 0; i < 20 && (hubA.clusterId() != hubC.clusterId() || hubC.clusterSize() < 3); i++) {
            Thread.sleep(50);
        }
        int winnerCid = hubA.clusterId();
        assertEquals(winnerCid, hubC.clusterId());
        assertEquals("3-node cluster size must propagate acrossCLUSTER_UPDATE", 3, hubC.clusterSize());

        // Form separate 2-node cluster (D-E)
        Hub hubD = new Hub("Node D", null, rD).asOwner(true).onClusterChanged(onChanged);
        Hub hubE = new Hub("Node E", null, rE).asOwner(true).onClusterChanged(onChanged);
        MeshAccess.Pipe[] de = createPairedMeshAccessPipes(MeshAccess.FM_KEY_NETWORK);
        hubD.addStreamLink(de[0].in, de[0].out, de[0], true);
        hubE.addStreamLink(de[1].in, de[1].out, de[1], true);
        HubTest.waitFor(rD, "join:Node E");
        HubTest.waitFor(rE, "join:Node D");
        for (int i = 0; i < 20 && (hubD.clusterId() != hubE.clusterId() || hubD.clusterSize() < 2); i++) {
            Thread.sleep(50);
        }

        // Link 3-node cluster (C) with 2-node cluster (D): larger 3-node cluster must always win!
        MeshAccess.Pipe[] cd = createPairedMeshAccessPipes(MeshAccess.FM_KEY_NETWORK);
        hubC.addStreamLink(cd[0].in, cd[0].out, cd[0], true);
        hubD.addStreamLink(cd[1].in, cd[1].out, cd[1], true);
        HubTest.waitFor(rA, "join:Node E");
        HubTest.waitFor(rE, "join:Node A");

        for (int i = 0; i < 30 && (hubE.clusterId() != winnerCid); i++) {
            Thread.sleep(50);
        }
        assertEquals("Larger 3-node cluster must win over 2-node cluster", winnerCid, hubD.clusterId());
        assertEquals("All 5 nodes must converge to 3-node cluster's ClusterId", winnerCid, hubE.clusterId());
        assertTrue("onClusterChanged callback must have fired", clusterChanges.get() > 0);

        hubE.stop();
        hubD.stop();
        hubC.stop();
        hubB.stop();
        hubA.stop();
    }

    @Test public void githubReleaseUpdaterParsesJsonComparesSemverAndComputesSha256() throws Exception {
        String sampleJson = "{"
                + "\"tag_name\": \"v0.20.0\","
                + "\"body\": \"APK Growth Internal 0.20.0\\n\\n### Perubahan di v0.20.0\\n"
                + "- Auto-update internal dari GitHub Releases\\n\\n### Cek keaslian file\\n"
                + "- Versi build (versionCode): 118\\n"
                + "- SHA-256 APK: `5bfff4619200b9719ae92adf87e915b30327c9c7e1ecb5d458ac2356d7b7e21c`\","
                + "\"assets\": ["
                + "{\"name\": \"growth-internal.apk\", \"browser_download_url\": \"https://example.com/growth-internal.apk\"},"
                + "{\"name\": \"growth-internal.apk.sha256\", \"browser_download_url\": \"https://example.com/growth-internal.apk.sha256\"}"
                + "]"
                + "}";

        Updater.Release rel = Updater.parseReleaseJson(sampleJson);
        assertNotNull(rel);
        assertEquals("0.20.0", rel.versionName);
        assertEquals(118, rel.versionCode);
        assertEquals("5bfff4619200b9719ae92adf87e915b30327c9c7e1ecb5d458ac2356d7b7e21c", rel.sha256);
        assertEquals("https://example.com/growth-internal.apk", rel.apkUrl);
        assertTrue(rel.notes.contains("Auto-update internal dari GitHub Releases"));

        // Older semver -> true
        assertTrue(Updater.isNewer("0.19.0", 117, rel));
        // Same semver and same CI versionCode -> false
        assertFalse(Updater.isNewer("0.20.0", 118, rel));
        // Same semver but lower CI versionCode -> true
        assertTrue(Updater.isNewer("0.20.0", 117, rel));
        // Newer local semver -> false
        assertFalse(Updater.isNewer("0.21.0", 22, rel));

        java.io.File tempFile = java.nio.file.Files.createTempFile("upd-test", ".apk").toFile();
        try {
            java.nio.file.Files.write(tempFile.toPath(), "abc".getBytes(Hub.UTF8));
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    Updater.sha256File(tempFile));
        } finally {
            tempFile.delete();
        }
    }

    @Test public void ruviewConnectivityQualityGateTdmFresnelAndHandover() throws Exception {
        MeshCoordinator coord = new MeshCoordinator();
        long t0 = 100000L;

        // 1. Stage 1: Welford RunningStats & 3-Filter QualityGate
        // Strong/normal signal (>= -82 dBm) passes immediately
        assertTrue(coord.evaluateRssi("NearPeer", -78, t0));

        // Coherent distant peer (-88 dBm): extends range below -82 dBm down to -91 dBm after 2 stable samples
        assertFalse("First weak sample (-88 dBm) awaits coherence evidence",
                coord.evaluateRssi("FarCoherent", -88, t0));
        assertTrue("Second coherent weak sample (-87 dBm, stdDev < 4.0) must pass QualityGate",
                coord.evaluateRssi("FarCoherent", -87, t0 + 500));

        // Multipath spike rejection: out-of-range noise (-95, -94) jumping suddenly to -80 dBm (> 10 dB shift)
        assertFalse(coord.evaluateRssi("MultipathNoise", -95, t0));
        assertFalse(coord.evaluateRssi("MultipathNoise", -94, t0 + 500));
        assertFalse("Sudden multipath reflection spike (> 10 dB shift) must be blocked",
                coord.evaluateRssi("MultipathNoise", -80, t0 + 1000));

        // Incoherent noisy weak peer (-95, -83 -> stdDev = 8.48 > 4.0 dB) must be blocked
        assertFalse(coord.evaluateRssi("NoisyWeak", -95, t0));
        assertFalse("High-variance weak link must be blocked by Coherence filter",
                coord.evaluateRssi("NoisyWeak", -83, t0 + 500));

        // 2. Stage 2: Distance-Weighted TDM Slot Scheduling & Duplicate Relay Suppression
        int edgeDelay = Ble.computeRelayDelayMs(-88, 10);
        int midDelay = Ble.computeRelayDelayMs(-72, 10);
        int nearDelay = Ble.computeRelayDelayMs(-52, 10);
        assertTrue("Edge node must get earliest TDM slot (15..40 ms)", edgeDelay >= 15 && edgeDelay <= 40);
        assertTrue("Mid node must get middle TDM slot (55..105 ms)", midDelay >= 55 && midDelay <= 105);
        assertTrue("Near node must get latest TDM slot (125..200 ms)", nearDelay >= 125 && nearDelay <= 200);
        assertTrue(edgeDelay < midDelay && midDelay < nearDelay);

        Ble ble = new Ble(null);
        assertTrue(ble.recordSeenMesh(0x4321));
        assertEquals(1, ble.overheardCount(0x4321));
        assertFalse(ble.recordSeenMesh(0x4321));
        assertEquals("Overheard duplicate relay increments count for suppression", 2, ble.overheardCount(0x4321));

        // 3. Stage 3: Fresnel-Zone Clearance & Relay Placement Advisor
        // F1 at d1=100m, d2=100m, lambda=0.125m -> sqrt(0.125 * 10000 / 200) = sqrt(6.25) = 2.5m
        assertEquals(2.5, MeshCoordinator.fresnelRadiusMeters(100.0, 100.0), 0.01);

        Presence basecamp = new Presence();
        basecamp.hasLocation = true;
        basecamp.lat = -6.200000;
        basecamp.lon = 106.800000;

        Presence valleySos = new Presence();
        valleySos.hasLocation = true;
        valleySos.status = Presence.SOS;
        valleySos.lat = -6.200000;
        valleySos.lon = 106.802000; // ~222m East

        Presence ridgeMid = new Presence();
        ridgeMid.hasLocation = true;
        ridgeMid.lat = -6.200000;
        ridgeMid.lon = 106.801000; // Exact midpoint in Fresnel corridor

        Presence offCorridor = new Presence();
        offCorridor.hasLocation = true;
        offCorridor.lat = -6.201500; // ~166m South of corridor
        offCorridor.lon = 106.801000;

        double midScore = MeshCoordinator.relayPlacementScore(basecamp, valleySos, ridgeMid);
        double offScore = MeshCoordinator.relayPlacementScore(basecamp, valleySos, offCorridor);
        assertTrue("Midpoint node in Fresnel corridor must score > 0.95", midScore > 0.95);
        assertTrue("Off-corridor node must score much lower than midpoint", offScore < 0.20);

        java.util.Map<String, Presence> map = new java.util.HashMap<String, Presence>();
        map.put("Pos Lembah", valleySos);
        map.put("Pos Bukit", ridgeMid);
        map.put("Pos Samping", offCorridor);
        assertEquals("Pos Bukit", MeshCoordinator.selectBestRelayCandidate(basecamp, map));

        // 4. Stage 4: Proactive Make-Before-Break Multi-Transport Handover
        // Simulate degrading signal: -76 -> -81 -> -85 dBm (emaDrift < -2.0 dB at <= -82 dBm)
        coord.evaluateRssi("Node B", -76, t0);
        coord.evaluateRssi("Node B", -81, t0 + 1000);
        coord.evaluateRssi("Node B", -85, t0 + 2000);
        assertTrue("Degrading signal into weak zone must trigger Make-Before-Break BLE handover",
                coord.shouldHandoverToBle("Node B"));

        // Verify live Hub seamless cross-transport handover (BLE lowBandwidth <-> Wi-Fi TCP)
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        HubTest.Rec rA = new HubTest.Rec();
        HubTest.Rec rB = new HubTest.Rec();
        Hub hubA = new Hub("Node A", null, rA).asOwner(true).port(9125);
        Hub hubB = new Hub("Node B", null, rB).asOwner(false).port(9125);
        hubA.startServer(lo);
        Thread.sleep(200);

        // Start on BLE MeshAccess pipe (lowBandwidth = true)
        MeshAccess.Pipe[] blePipes = createPairedMeshAccessPipes(MeshAccess.FM_KEY_NETWORK);
        hubA.addStreamLink(blePipes[0].in, blePipes[0].out, blePipes[0], true);
        hubB.addStreamLink(blePipes[1].in, blePipes[1].out, blePipes[1], true);
        HubTest.waitFor(rA, "join:Node B");
        HubTest.waitFor(rB, "join:Node A");
        assertTrue(hubA.hasLowBandwidthLink("Node B"));
        assertTrue("canAutoConnect must allow upgrading BLE peer to LAN/Aware",
                coord.canAutoConnect("lan:Node B", "Node B", "Node A", hubA));

        // Upgrade to high-speed TCP link (lowBandwidth = false) without dropping member
        hubB.connectPeer(lo, 9125, null);
        for (int i = 0; i < 40 && hubA.hasLowBandwidthLink("Node B"); i++) {
            Thread.sleep(50);
        }
        assertFalse("BLE link must be seamlessly replaced by TCP link", hubA.hasLowBandwidthLink("Node B"));
        assertFalse("Handover must never emit member left event", rA.log.contains("left:Node B"));
        assertFalse("Handover must never reject peer", rB.log.contains("rejected"));

        hubB.sendText("seamless-upgrade");
        HubTest.waitFor(rA, "text:Node B:seamless-upgrade");

        hubB.stop();
        hubA.stop();
    }

    @Test public void spectreFissureBluerangeMultiHopCommunicationUpgrade() throws Exception {
        // 1. Stage 1: Spectre Smart NIC Filtering & Unicast .1 Gateway Probe
        assertTrue(Lan.isVirtualOrCellularInterface("tun0"));
        assertTrue(Lan.isVirtualOrCellularInterface("rmnet_data0"));
        assertTrue(Lan.isVirtualOrCellularInterface("clat4"));
        assertFalse(Lan.isVirtualOrCellularInterface("wlan0"));
        assertTrue(Lan.isPreferredWifiInterface("wlan0"));
        assertTrue(Lan.isPreferredWifiInterface("ap0"));
        assertTrue(Lan.isPreferredWifiInterface("swlan0"));

        InetAddress clientIp = InetAddress.getByName("192.168.43.112");
        InetAddress gwIp = Lan.gatewayFor(clientIp);
        assertNotNull(gwIp);
        assertEquals("192.168.43.1", gwIp.getHostAddress());
        assertNull(Lan.gatewayFor(InetAddress.getByName("192.168.43.1")));

        // 2. Stage 2: FISSURE LT Micro-Fragmentation & Out-of-Order Reassembly
        java.util.List<byte[]> shortParts = Ble.splitMeshText("Aman Pos1", 1);
        assertEquals("<=10B text must remain a single unfragmented packet", 1, shortParts.size());
        Ble ble = new Ble(null);
        assertArrayEquals("Aman Pos1".getBytes(Hub.UTF8),
                ble.reassembleMeshBody("Andi", Ble.MESH_TEXT, shortParts.get(0)));

        String longMsg = "Rotasi pos 3 dimulai 10 menit lagi menuju Main Hall";
        java.util.List<byte[]> frags = Ble.splitMeshText(longMsg, 2);
        assertTrue("51-byte text must split into 7 fragments", frags.size() > 1 && frags.size() <= 7);
        for (byte[] frag : frags) {
            byte[] packed = Ble.packMesh(0x2233, Ble.DEFAULT_TTL, Ble.MESH_TEXT, "Pos 3", frag);
            assertNotNull(packed);
            assertTrue("Each fragment must fit in 22-byte BLE service data", packed.length <= 22);
        }
        // Feed fragments out of order (reverse order) and verify reassembly only on final piece
        byte[] assembled = null;
        for (int i = frags.size() - 1; i >= 0; i--) {
            assembled = ble.reassembleMeshBody("Pos 3", Ble.MESH_TEXT, frags.get(i));
            if (i > 0) assertNull("Incomplete fragment set must wait", assembled);
        }
        assertNotNull(assembled);
        assertEquals(longMsg, new String(assembled, Hub.UTF8));

        // 3. Stage 3 & 4: 8 KB Chunked Photo over Low-Bandwidth Multi-Hop + Cross-Transport MESH_RELAY
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        int port = 19149;
        HubTest.Rec rA = new HubTest.Rec();
        HubTest.Rec rB = new HubTest.Rec();
        HubTest.Rec rC = new HubTest.Rec();

        Hub hubA = new Hub("Pos 1", null, rA).port(port);
        hubA.startServer(lo);
        Thread.sleep(200);

        Hub hubB = new Hub("Pos 2", null, rB).port(port);
        hubB.startClient(lo);
        HubTest.waitFor(rA, "join:Pos 2");
        HubTest.waitFor(rB, "join:Pos 1");

        final PipedOutputStream bOut = new PipedOutputStream();
        final PipedInputStream cIn = new PipedInputStream(bOut, 65536);
        final PipedOutputStream cOut = new PipedOutputStream();
        final PipedInputStream bIn = new PipedInputStream(cOut, 65536);
        Closeable rawClose = new Closeable() {
            @Override public void close() throws IOException {
                bOut.close(); cOut.close(); cIn.close(); bIn.close();
            }
        };
        Hub hubC = new Hub("Pos 3", null, rC).asOwner(false);
        hubB.addStreamLink(bIn, bOut, rawClose, true);
        hubC.addStreamLink(cIn, cOut, rawClose, true);
        HubTest.waitFor(rA, "join:Pos 3");
        HubTest.waitFor(rC, "join:Pos 1");

        // Send a 60 KB photo (8 chunks of 8 KB) + live audio from Pos 1 across low-bandwidth hop to Pos 3
        byte[] photo = new byte[60 * 1024];
        photo[0] = 42;
        photo[photo.length - 1] = 99;
        hubA.sendImage(photo);
        hubA.sendAudio(new byte[640]);
        HubTest.waitFor(rC, "audio:Pos 1:640");
        HubTest.waitFor(rC, "image:Pos 1:" + photo.length);

        // Cross-transport BLE -> Hub MESH_RELAY across 2 hops without blocking direct join
        Presence sos = new Presence();
        sos.status = Presence.SOS;
        hubA.relayMeshPresence("Tim Jauh", sos.encode());
        hubA.relayMeshText("Tim Jauh", "Butuh medis di sektor B");
        hubA.relayMeshAnnounce("Komandan", "Prioritaskan jalur evakuasi");
        HubTest.waitFor(rC, "presence:Tim Jauh:4");
        HubTest.waitFor(rC, "text:Tim Jauh:Butuh medis di sektor B");
        HubTest.waitFor(rC, "announce:Komandan:Prioritaskan jalur evakuasi");

        // Verify "Tim Jauh" is NOT blocked by nameTaken when walking closer and joining Hub directly
        HubTest.Rec rFar = new HubTest.Rec();
        Hub hubFar = new Hub("Tim Jauh", null, rFar).port(port);
        hubFar.startClient(lo);
        HubTest.waitFor(rA, "join:Tim Jauh");
        HubTest.waitFor(rC, "join:Tim Jauh");
        assertFalse(rFar.log.contains("rejected"));

        // Verify GPS-independent Welford RF relay selection & fast-reconnect cooldown clear
        MeshCoordinator coord = new MeshCoordinator();
        coord.evaluateRssi("Relay Tengah", -62, 1000L);
        coord.evaluateRssi("Relay Tengah", -63, 2000L);
        coord.evaluateRssi("Peer Lemah", -88, 1000L);
        assertEquals("Relay Tengah",
                coord.selectBestRelay(new Presence(), new java.util.HashMap<String, Presence>()));

        assertTrue(coord.canAutoConnect("lan:Relay Tengah", "Relay Tengah", "Pos 1", null));
        assertFalse("Cooldown blocks immediate duplicate",
                coord.canAutoConnect("lan:Relay Tengah", "Relay Tengah", "Pos 1", null));
        coord.clearPeerCooldown("Relay Tengah");
        assertTrue("clearPeerCooldown must allow immediate BlueRange fast reconnect",
                coord.canAutoConnect("lan:Relay Tengah", "Relay Tengah", "Pos 1", null));

        hubFar.stop();
        hubC.stop();
        hubB.stop();
        hubA.stop();
    }

    @Test public void proseAwareSidelinkHembVoiceSmaz2And15HopKilometerMesh() throws Exception {
        // 1. Stage 1: 8 kHz Narrowband ADPCM (84B) & MeshSat HeMB GF(2^8) RLNC Voice FEC
        byte[] pcm = new byte[640];
        for (int i = 0; i < 320; i++) {
            short s = (short) (Math.sin(i * 0.25) * 14000);
            pcm[i * 2] = (byte) (s & 0xff);
            pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        byte[] nb = Adpcm.encodeNarrowband(pcm);
        assertEquals("8 kHz narrowband ADPCM must be 84 bytes (32 kbps)", 84, nb.length);
        assertTrue("Narrowband header flag must be set", (nb[3] & Adpcm.NARROWBAND_FLAG) != 0);
        byte[] upsampled = Adpcm.decode(nb);
        assertEquals("Decoded narrowband ADPCM must upsample back to 640B 16 kHz PCM", 640, upsampled.length);

        // GF(2^8) field inversion identity: a * inv(a) == 1 for all non-zero elements
        for (int a = 1; a < 256; a++) {
            assertEquals(1, Adpcm.gfMul(a, Adpcm.gfInv(a)));
        }

        // HeMB 4-frame sliding-window parity encode & exact recovery of a lost voice frame
        byte[][] window = new byte[4][84];
        for (int k = 0; k < 4; k++) {
            for (int i = 0; i < 84; i++) window[k][i] = (byte) ((k * 37 + i * 13 + 7) & 0xff);
        }
        byte[] parity = Adpcm.encodeHembParity(40, window, Adpcm.HEMB_DEFAULT_COEFFS);
        assertNotNull(parity);
        assertEquals(6 + 84, parity.length);

        byte[][] rxWindow = new byte[][] { window[0], window[1], null, window[3] };
        byte[] recovered = Adpcm.recoverHembFrame(rxWindow, 2, parity);
        assertNotNull("HeMB must reconstruct missing voice frame #2 in 0 ms", recovered);
        assertArrayEquals(window[2], recovered);

        // 2. Stage 2: ProSe-Style Wi-Fi Aware L2 Sidelink Multi-Hop Relay (< 255B)
        byte[] slAudio = Aware.packSidelink(0x55AA, Aware.DEFAULT_SIDELINK_TTL,
                Aware.SIDELINK_AUDIO, "Pos Ridge", nb);
        assertNotNull(slAudio);
        assertTrue("84B narrowband voice + Sidelink header must fit within 255B L2 limit",
                slAudio.length <= Aware.MAX_SIDELINK_BYTES);
        Aware.SidelinkPacket slPkt = Aware.unpackSidelink(slAudio);
        assertNotNull(slPkt);
        assertEquals(0x55AA, slPkt.msgId);
        assertEquals(15, slPkt.ttl);
        assertEquals(Aware.SIDELINK_AUDIO, slPkt.type);
        assertEquals("Pos Ridge", slPkt.sender);
        assertArrayEquals(nb, slPkt.body);

        Aware aware = new Aware(null);
        assertTrue(aware.recordSeenSidelink(0x55AA));
        assertFalse("Duplicate Sidelink msgId must be dropped", aware.recordSeenSidelink(0x55AA));

        // 3. Stage 3: Extended 15-Hop BLE Mesh + SMAZ2 Indonesian Field Dictionary Compression
        assertEquals(15, Ble.DEFAULT_TTL);
        String fieldText = "prioritas butuh backup segera di pos 1 jalur aman menuju basecamp";
        byte[] comp = Ble.compressText(fieldText);
        assertTrue("SMAZ2 must compress Indonesian field phrase significantly",
                comp.length < fieldText.getBytes(Hub.UTF8).length);
        assertEquals(fieldText, Ble.decompressText(comp));

        java.util.List<byte[]> smazFrags = Ble.splitMeshText(fieldText, 9);
        assertTrue("65-byte Indonesian phrase compressed with SMAZ2 must fit in <= 7 BLE fragments",
                smazFrags.size() <= Ble.MAX_FRAG_PARTS);
        Ble ble = new Ble(null);
        byte[] reassembled = null;
        for (byte[] frag : smazFrags) {
            reassembled = ble.reassembleMeshBody("Komandan", Ble.MESH_TEXT, frag);
        }
        assertNotNull(reassembled);
        assertEquals(fieldText, new String(reassembled, Hub.UTF8));

        // 3GPP Rel-12/13 ProSe interface detection, Deaf-Link Watchdog & UE-to-Network Gateway Selection
        assertTrue(Lan.isProSeSidelinkInterface("prose0"));
        assertTrue(Lan.isProSeSidelinkInterface("pc5_0"));
        assertTrue(Lan.isPreferredWifiInterface("prose0"));

        MeshCoordinator coord = new MeshCoordinator();
        coord.recordGatewayPeer("Pos Lembah", false);
        coord.recordGatewayPeer("Pos Bukit LTE", true);
        coord.evaluateRssi("Pos Bukit LTE", -70, 1000L);
        assertEquals("Pos Bukit LTE", coord.selectBestGatewayPeer());
        coord.evaluateRssi("Pos Lembah", -75, 5000L);
        assertTrue("Link with TX but 0 RX for >12s must be flagged deaf",
                coord.isReceiveDeaf("Pos Lembah", 20000L, 25000L));

        // 4. End-to-End Live Hub: HeMB 0ms Voice Recovery + Sidelink Voice Bridge + MCPTT Priority
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        int port = 19163;
        HubTest.Rec rA = new HubTest.Rec();
        HubTest.Rec rB = new HubTest.Rec();
        Hub hubA = new Hub("Pos 1", null, rA).port(port);
        hubA.startServer(lo);
        Thread.sleep(200);

        Hub hubB = new Hub("Pos 2", null, rB).port(port);
        hubB.startClient(lo);
        HubTest.waitFor(rA, "join:Pos 2");
        HubTest.waitFor(rB, "join:Pos 1");

        // Put Pos 1 in SOS priority state and verify MCPTT priority speaker detection
        Presence sos = new Presence();
        sos.status = Presence.SOS;
        hubA.sendPresence(sos.encode());
        HubTest.waitFor(rB, "presence:Pos 1:4");
        assertTrue(hubA.isPrioritySpeaker("Pos 1"));
        assertTrue(hubB.isPrioritySpeaker("Pos 1"));

        // Simulate 4-frame window on Hub B where frame #1 is dropped in transit, then HeMB parity arrives
        byte[][] adpcmWin = new byte[4][];
        for (int k = 0; k < 4; k++) {
            pcm[0] = (byte) (k * 50);
            adpcmWin[k] = Adpcm.encodeNarrowband(pcm);
        }
        byte[] hembPkt = Adpcm.encodeHembParity(0, adpcmWin);
        // Deliver frames 0, 2, 3 (drop frame 1) + HeMB parity frame directly into Hub B
        hubB.onFrame(null, new Frame(Frame.AUDIO_ADPCM, "Pos 1", adpcmWin[0]));
        hubB.onFrame(null, new Frame(Frame.AUDIO_ADPCM, "Pos 1", adpcmWin[2]));
        hubB.onFrame(null, new Frame(Frame.AUDIO_ADPCM, "Pos 1", adpcmWin[3]));
        hubB.onFrame(null, new Frame(Frame.AUDIO_HEMB, "Pos 1", hembPkt));

        int audioCount = 0;
        for (String ev : rB.log) {
            if ("audio:Pos 1:640".equals(ev)) audioCount++;
        }
        assertEquals("HeMB must recover dropped frame #1 so all 4 voice frames play", 4, audioCount);

        // Verify Sidelink live voice & PTT talk-state bridge across Hub tree
        hubA.relayMeshTalkState("Pos Sidelink", true);
        hubA.relayMeshAudio("Pos Sidelink", nb);
        HubTest.waitFor(rB, "audio:Pos Sidelink:640");

        hubB.stop();
        hubA.stop();
    }

    @Test public void voiceJitterBufferLinkAutoReconnectAndScreenOffSurvival() throws Exception {
        // 1. Stage 1: Voice Adaptive Pre-Roll Jitter Buffer, Latency Cap, & Zero-Underrun PLC
        Voice.JitterBuffer jb = new Voice.JitterBuffer();
        byte[] f0 = new byte[Voice.FRAME_BYTES];
        byte[] f1 = new byte[Voice.FRAME_BYTES];
        byte[] f2 = new byte[Voice.FRAME_BYTES];
        for (int i = 0; i < 320; i++) {
            f0[i * 2] = 20;
            f1[i * 2] = 40;
            f2[i * 2] = 60;
        }
        jb.offer(f0, 1000L);
        assertFalse("1 frame must wait in pre-roll to absorb RF jitter", jb.isPrimed());
        assertNull(jb.pollNext(1010L));

        jb.offer(f1, 1020L);
        assertFalse("2 frames must still hold pre-roll", jb.isPrimed());
        assertNull(jb.pollNext(1030L));

        jb.offer(f2, 1040L);
        assertTrue("3 frames (60 ms) must prime playout", jb.isPrimed());
        assertArrayEquals(f0, jb.pollNext(1040L));
        assertArrayEquals(f1, jb.pollNext(1060L));
        assertArrayEquals(f2, jb.pollNext(1080L));

        // Simulate late packet #3: jitter buffer synthesizes up to MAX_PLC_FRAMES (40 ms) instead of underrunning
        byte[] plc1 = jb.pollNext(1100L);
        byte[] plc2 = jb.pollNext(1120L);
        assertNotNull("Late packet slot 1 must synthesize PLC frame", plc1);
        assertNotNull("Late packet slot 2 must synthesize PLC frame", plc2);
        assertEquals(Voice.FRAME_BYTES, plc1.length);
        assertEquals(Voice.FRAME_BYTES, plc2.length);
        assertNull("After MAX_PLC_FRAMES, spurt ends cleanly and resets pre-roll", jb.pollNext(1140L));
        assertFalse(jb.isPrimed());

        // Bounded latency cap: burst of 16 frames trims oldest 4 so delay never exceeds 240 ms (12 frames)
        for (int i = 0; i < 16; i++) {
            byte[] burst = new byte[Voice.FRAME_BYTES];
            burst[0] = (byte) i;
            jb.offer(burst, 2000L + i);
        }
        assertEquals(Voice.MAX_JITTER_FRAMES, jb.size());
        byte[] oldestKept = jb.pollNext(2020L);
        assertNotNull(oldestKept);
        assertEquals("Oldest 4 stale burst frames must be trimmed", 4, oldestKept[0]);

        // 2. Stage 1 & 3: BLE 2.4 GHz Voice Coexistence & Screen-Off Hardware ScanFilters
        Ble ble = new Ble(null);
        assertEquals(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY, ble.effectiveScanMode());
        ble.setConnectedMode(true);
        assertEquals(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED, ble.effectiveScanMode());
        ble.setVoiceActive(true);
        assertEquals("Active voice must throttle BLE scan to LOW_POWER to free 2.4 GHz antenna",
                android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER, ble.effectiveScanMode());
        ble.setVoiceActive(false);
        assertEquals(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED, ble.effectiveScanMode());

        java.util.List<android.bluetooth.le.ScanFilter> filters = Ble.buildScanFilters();
        assertEquals(3, filters.size());
        android.bluetooth.le.ScanFilter emptyFilter = new android.bluetooth.le.ScanFilter.Builder().build();
        for (android.bluetooth.le.ScanFilter sf : filters) {
            assertFalse("Screen-off BLE ScanFilter must not be empty on Android 8.1+",
                    emptyFilter.equals(sf));
        }
        assertEquals(Ble.SERVICE_UUID, filters.get(0).getServiceDataUuid());
        assertEquals(Ble.MESH_UUID, filters.get(1).getServiceDataUuid());
        assertEquals(Ble.MESH_ACCESS_UUID, filters.get(2).getServiceUuid());

        // 3. Stage 3: KeepAliveService Low-Latency Wi-Fi Lock (Android 10+), MulticastLock & START_STICKY
        assertEquals(KeepAliveService.WIFI_MODE_LOW_LATENCY, KeepAliveService.wifiLockModeForSdk(29));
        assertEquals(KeepAliveService.WIFI_MODE_LOW_LATENCY, KeepAliveService.wifiLockModeForSdk(34));
        assertEquals(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                KeepAliveService.wifiLockModeForSdk(28));

        KeepAliveService svc = org.robolectric.Robolectric.buildService(KeepAliveService.class).create().get();
        int sticky = svc.onStartCommand(
                new android.content.Intent().putExtra(KeepAliveService.EXTRA_TEXT, "Mode Telepon aktif"),
                0, 1);
        assertEquals("KeepAliveService must return START_STICKY for background survival",
                android.app.Service.START_STICKY, sticky);
        svc.onDestroy();

        // 4. Stage 2: 25s Link Timeout & Seamless In-Session TCP Auto-Reconnect
        assertEquals(25000, Link.TIMEOUT_MS);
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        int port = 19177;
        HubTest.Rec rOwner = new HubTest.Rec();
        HubTest.Rec rClient = new HubTest.Rec();
        Hub ownerHub = new Hub("Basecamp", null, rOwner).port(port);
        ownerHub.startServer(lo);
        Thread.sleep(200);

        Hub clientHub = new Hub("Pos Tebing", null, rClient).port(port);
        clientHub.startClient(lo);
        HubTest.waitFor(rOwner, "join:Pos Tebing");
        HubTest.waitFor(rClient, "join:Basecamp");

        // Forcibly drop the live TCP link mid-session (simulating a sudden RF drop / screen-off socket reset)
        rOwner.log.clear();
        rClient.log.clear();
        assertFalse(clientHub.activeLinks().isEmpty());
        clientHub.activeLinks().get(0).close();
        HubTest.waitFor(rClient, "left:Basecamp");

        // Verify Hub automatically reconnects in the background and restores voice & chat!
        HubTest.waitFor(rOwner, "join:Pos Tebing");
        HubTest.waitFor(rClient, "join:Basecamp");
        clientHub.sendAudio(new byte[Voice.FRAME_BYTES]);
        ownerHub.sendText("tersambung-kembali");
        HubTest.waitFor(rOwner, "audio:Pos Tebing:640");
        HubTest.waitFor(rClient, "text:Basecamp:tersambung-kembali");

        clientHub.stop();
        ownerHub.stop();
    }

    @Test public void closeRangeSmoothVoiceNoRelayDuplicateAndHembWindowSync() throws Exception {
        // 1. Hardware-cushioned JitterBuffer.pollQueued: absorbs 45ms late packet without premature PLC chop
        Voice.JitterBuffer jb = new Voice.JitterBuffer();
        byte[] p0 = new byte[Voice.FRAME_BYTES];
        byte[] p1 = new byte[Voice.FRAME_BYTES];
        byte[] p2 = new byte[Voice.FRAME_BYTES];
        byte[] p3 = new byte[Voice.FRAME_BYTES];
        p0[0] = 11;
        p1[0] = 22;
        p2[0] = 33;
        p3[0] = 44;

        jb.offer(p0, 1000L);
        assertNull("Single frame at +50ms must not under-prime spurt", jb.pollQueued(1050L));
        assertFalse(jb.isPrimed());
        jb.offer(p1, 1055L);
        jb.offer(p2, 1060L);
        assertTrue(jb.isPrimed());
        assertArrayEquals(p0, jb.pollQueued(1060L));
        assertArrayEquals(p1, jb.pollQueued(1060L));
        assertArrayEquals(p2, jb.pollQueued(1060L));
        // Momentarily empty queue while AudioTrack hardware cushion plays: pollQueued returns null, stays primed!
        assertNull(jb.pollQueued(1085L));
        assertTrue("JitterBuffer must stay primed while AudioTrack hardware cushion plays", jb.isPrimed());
        // Late packet p3 arrives 45ms later and plays bit-exact without any synthetic PLC frame wedged in!
        jb.offer(p3, 1105L);
        assertArrayEquals(p3, jb.pollQueued(1105L));

        // Cross-faded concealFrame avoids abrupt DC step click between end sample (+10000) and start sample (-10000)
        byte[] stepFrame = new byte[Voice.FRAME_BYTES];
        ByteBuffer sb = ByteBuffer.wrap(stepFrame).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 319; i++) sb.putShort((short) -10000);
        sb.putShort((short) 10000);
        byte[] concealed = Voice.concealFrame(stepFrame);
        short firstConcealed = ByteBuffer.wrap(concealed).order(ByteOrder.LITTLE_ENDIAN).getShort(0);
        assertTrue("PLC frame must crossfade smoothly from lastSample (+7500) instead of jumping to -7500",
                firstConcealed > 7000);

        // 2. MeshAccess MTU 247 fits narrowband ADPCM + Frame header in 1 single GATT write (vs 8-13 writes at MTU 20)
        MeshAccess.Pipe[] mtuPipes = createPairedMeshAccessPipes(MeshAccess.FM_KEY_NETWORK);
        mtuPipes[0].setMtuPayload(247);
        assertEquals(244, mtuPipes[0].mtuPayload());
        byte[][] singleGattWrite = MeshAccess.Session.splitPayload(new byte[100], mtuPipes[0].mtuPayload());
        assertEquals("84B narrowband voice + Frame header must fit in 1 GATT packet at MTU 247",
                1, singleGattWrite.length);
        mtuPipes[0].close();
        mtuPipes[1].close();

        // 3. #relay passive node must NEVER appear as a duplicate user, and upgrades cleanly to active <Name>
        assertTrue(Hub.isPassiveRelayName("Budi#relay"));
        assertFalse(Hub.isPassiveRelayName("Budi"));
        assertEquals("Budi", Hub.baseMemberName("Budi#relay"));

        HubTest.Rec rAndi = new HubTest.Rec();
        HubTest.Rec rBudi = new HubTest.Rec();
        HubTest.Rec rCici = new HubTest.Rec();
        Hub hubAndi = new Hub("Andi", null, rAndi).asOwner(true);
        Hub budiRelay = new Hub("Budi#relay", null, Hub.NOOP_CALLBACK).asOwner(true);

        MeshAccess.Pipe[] relayPipes = createPairedMeshAccessPipes(MeshAccess.FM_KEY_ZERO);
        hubAndi.addStreamLink(relayPipes[0].in, relayPipes[0].out, relayPipes[0], true);
        budiRelay.addStreamLink(relayPipes[1].in, relayPipes[1].out, relayPipes[1], true);
        Thread.sleep(200);
        assertFalse("Passive #relay node must never be listed in Hub.members",
                hubAndi.hasMember("Budi#relay"));
        assertFalse("Passive #relay node must never fire onMemberJoined",
                rAndi.log.contains("join:Budi#relay"));

        // Now Budi joins actively as "Budi": seamlessly replaces "Budi#relay" link with only 1 user "Budi"
        Hub hubBudi = new Hub("Budi", null, rBudi).asOwner(false);
        MeshAccess.Pipe[] activePipes = createPairedMeshAccessPipes(MeshAccess.FM_KEY_NETWORK);
        hubAndi.addStreamLink(activePipes[0].in, activePipes[0].out, activePipes[0], true);
        hubBudi.addStreamLink(activePipes[1].in, activePipes[1].out, activePipes[1], true);
        HubTest.waitFor(rAndi, "join:Budi");
        HubTest.waitFor(rBudi, "join:Andi");
        assertTrue(hubAndi.hasMember("Budi"));
        assertFalse(hubAndi.hasMember("Budi#relay"));

        // Late redundant "Budi#relay" tunnel while "Budi" is already connected must be rejected immediately
        Hub lateRelay = new Hub("Budi#relay", null, Hub.NOOP_CALLBACK).asOwner(true);
        MeshAccess.Pipe[] dupRelayPipes = createPairedMeshAccessPipes(MeshAccess.FM_KEY_ZERO);
        hubAndi.addStreamLink(dupRelayPipes[0].in, dupRelayPipes[0].out, dupRelayPipes[0], true);
        lateRelay.addStreamLink(dupRelayPipes[1].in, dupRelayPipes[1].out, dupRelayPipes[1], true);
        Thread.sleep(150);
        assertEquals("Redundant #relay link must be closed so only 1 link to Budi remains",
                1, hubAndi.activeLinks().size());
        assertFalse(rAndi.log.contains("join:Budi#relay"));

        // Self-relay loop ("Budi#relay" connecting into client hubBudi "Budi") must close silently without onRejected
        MeshAccess.Pipe[] selfLoopPipes = createPairedMeshAccessPipes(MeshAccess.FM_KEY_ZERO);
        hubBudi.addStreamLink(selfLoopPipes[0].in, selfLoopPipes[0].out, selfLoopPipes[0], true);
        lateRelay.addStreamLink(selfLoopPipes[1].in, selfLoopPipes[1].out, selfLoopPipes[1], true);
        Thread.sleep(150);
        assertEquals("Self-relay loop must be discarded without dropping active link",
                1, hubBudi.activeLinks().size());

        // 4. Duplicate Sidelink/MeshRelay voice suppression when sender is already a Hub member
        Hub hubCici = new Hub("Cici", null, rCici).asOwner(false);
        MeshAccess.Pipe[] ciciPipes = createPairedMeshAccessPipes(MeshAccess.FM_KEY_NETWORK);
        hubAndi.addStreamLink(ciciPipes[0].in, ciciPipes[0].out, ciciPipes[0], true);
        hubCici.addStreamLink(ciciPipes[1].in, ciciPipes[1].out, ciciPipes[1], true);
        HubTest.waitFor(rCici, "join:Budi");

        rAndi.log.clear();
        byte[] nbVoice = Adpcm.encodeNarrowband(new byte[Voice.FRAME_BYTES]);
        // Cici relays sidelink audio claiming originSender="Budi" (already in hubAndi!) -> must NOT double-play!
        hubCici.relayMeshAudio("Budi", nbVoice);
        // Cici relays sidelink audio for external pure-Sidelink node "Pos Luar" -> MUST play!
        hubCici.relayMeshAudio("Pos Luar", nbVoice);
        HubTest.waitFor(rAndi, "audio:Pos Luar:640");
        assertFalse("Relayed sidelink audio for already-connected Hub member Budi must be suppressed",
                rAndi.log.contains("audio:Budi:640"));

        // 5. HeMB window desync protection when an earlier AUDIO_HEMB parity packet was dropped
        rAndi.log.clear();
        byte[][] w0 = new byte[4][];
        byte[][] w1 = new byte[4][];
        for (int k = 0; k < 4; k++) {
            byte[] raw = new byte[Voice.FRAME_BYTES];
            raw[0] = (byte) (k + 10);
            w0[k] = Adpcm.encodeNarrowband(raw);
            raw[0] = (byte) (k + 90);
            w1[k] = Adpcm.encodeNarrowband(raw);
        }
        // Window 0 loses frame #3 AND loses its AUDIO_HEMB parity frame (3 frames delivered)
        hubAndi.onFrame(null, new Frame(Frame.AUDIO_ADPCM, "Budi", w0[0]));
        hubAndi.onFrame(null, new Frame(Frame.AUDIO_ADPCM, "Budi", w0[1]));
        hubAndi.onFrame(null, new Frame(Frame.AUDIO_ADPCM, "Budi", w0[2]));
        // Window 1 delivers all 4 frames + its AUDIO_HEMB parity frame
        for (int k = 0; k < 4; k++) {
            hubAndi.onFrame(null, new Frame(Frame.AUDIO_ADPCM, "Budi", w1[k]));
        }
        hubAndi.onFrame(null, new Frame(Frame.AUDIO_HEMB, "Budi", Adpcm.encodeHembParity(4, w1)));
        int budiPlayed = 0;
        for (String ev : rAndi.log) {
            if ("audio:Budi:640".equals(ev)) budiPlayed++;
        }
        assertEquals("Dropped earlier parity must not trigger false stale-frame replay in next window",
                7, budiPlayed);

        lateRelay.stop();
        budiRelay.stop();
        hubCici.stop();
        hubBudi.stop();
        hubAndi.stop();
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

    private static byte[] makeSinePcm(int samples, double amplitude) {
        byte[] pcm = new byte[samples * 2];
        ByteBuffer buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            int s = (int) Math.round(Math.sin(2.0 * Math.PI * 1000.0 * i / Voice.RATE) * amplitude);
            if (s > 32767) s = 32767;
            if (s < -32768) s = -32768;
            buf.putShort((short) s);
        }
        return pcm;
    }

    private static int pcmPeak(byte[] pcm, int startSample, int endSample) {
        int peak = 0;
        for (int i = startSample; i < endSample; i++) {
            int lo = pcm[i * 2] & 0xFF;
            int hi = pcm[i * 2 + 1];
            int s = (short) ((hi << 8) | lo);
            int abs = s < 0 ? -s : s;
            if (abs > peak) peak = abs;
        }
        return peak;
    }

    @Test public void fieldSpeakerBoostNoiseGateSoftLimiterAndAudioRouting() {
        int frameSamples = Voice.FRAME_BYTES / 2;

        // 1. Quiet field speech (~-24 dBFS, peak 2000) is boosted ~4.0x on frame 1 and ~5.0x (+14 dB) by frame 3
        byte[] quietSpeech = makeSinePcm(frameSamples, 2000.0);
        byte[] out = new byte[Voice.FRAME_BYTES];
        int gain = Voice.BOOST_UNITY_Q10;
        gain = Voice.boostPcm(quietSpeech, out, gain);
        int frame1Peak = pcmPeak(out, 64, frameSamples);
        assertTrue("First quiet speech frame must already rise >= 3.8x, got " + frame1Peak,
                frame1Peak >= 7600 && frame1Peak <= 10500);
        gain = Voice.boostPcm(quietSpeech, out, gain);
        gain = Voice.boostPcm(quietSpeech, out, gain);
        int frame3Peak = pcmPeak(out, 64, frameSamples);
        assertTrue("Sustained quiet speech must reach ~4.9x-5.0x (+14 dB), got " + frame3Peak,
                frame3Peak >= 9600 && frame3Peak <= 10200);

        // 2. Moderate field speech (peak 6000) normalizes cleanly toward BOOST_TARGET_PEAK (~26000) without clipping
        byte[] normalSpeech = makeSinePcm(frameSamples, 6000.0);
        int normGain = Voice.BOOST_UNITY_Q10;
        for (int k = 0; k < 4; k++) {
            normGain = Voice.boostPcm(normalSpeech, out, normGain);
        }
        int normalPeak = pcmPeak(out, 64, frameSamples);
        assertTrue("Normal speech must normalize near -2 dBFS (~25000-26500), got " + normalPeak,
                normalPeak >= 24500 && normalPeak <= 26500);

        // 3. Idle background noise floor (peak 180 <= BOOST_NOISE_FLOOR) must NOT be amplified into static hiss
        byte[] idleNoise = makeSinePcm(frameSamples, 180.0);
        byte[] idleBoosted = Voice.boostFieldAudio(idleNoise);
        assertEquals("Idle noise below gate must stay at exact 1.0x amplitude",
                pcmPeak(idleNoise, 0, frameSamples), pcmPeak(idleBoosted, 0, frameSamples));

        // After active speech, silence decays gain smoothly back toward unity
        int decayedGain = gain;
        for (int k = 0; k < 8; k++) {
            decayedGain = Voice.boostPcm(idleNoise, out, decayedGain);
        }
        assertTrue("Gain must decay smoothly back near unity during silence, got " + decayedGain,
                decayedGain <= Voice.BOOST_UNITY_Q10 + 32);

        // 4. Sudden loud transient after high-gain quiet speech + Short.MIN_VALUE soft-knee safety
        byte[] loudShout = makeSinePcm(frameSamples, 28000.0);
        ByteBuffer.wrap(loudShout).order(ByteOrder.LITTLE_ENDIAN).putShort(0, Short.MIN_VALUE);
        Voice.boostPcm(loudShout, out, Voice.BOOST_MAX_Q10);
        int loudPeak = pcmPeak(out, 0, frameSamples);
        assertTrue("Soft-knee limiter + fast attack must prevent overflow/hard-clip, got " + loudPeak,
                loudPeak >= 26000 && loudPeak <= 32767);
        assertTrue(Voice.softLimit(160000) > Voice.BOOST_SOFT_KNEE && Voice.softLimit(160000) <= 32767);
        assertTrue(Voice.softLimit(-160000) < -Voice.BOOST_SOFT_KNEE && Voice.softLimit(-160000) >= -32767);

        // 5. Multi-window Voice Note: a loud cough in window 0 must not suppress quiet speech in windows 1..4
        byte[] voiceNote = new byte[Voice.FRAME_BYTES * 5];
        System.arraycopy(makeSinePcm(frameSamples, 25000.0), 0, voiceNote, 0, Voice.FRAME_BYTES);
        for (int w = 1; w < 5; w++) {
            System.arraycopy(quietSpeech, 0, voiceNote, w * Voice.FRAME_BYTES, Voice.FRAME_BYTES);
        }
        byte[] boostedNote = Voice.boostFieldAudio(voiceNote);
        int tailNotePeak = pcmPeak(boostedNote, frameSamples * 4 + 64, frameSamples * 5);
        assertTrue("20 ms windowed AGC must boost quiet Voice Note speech after initial loud spike, got "
                + tailNotePeak, tailNotePeak >= 9500);

        // 6. 3-state field audio routing & hardware volume floor invariants
        // Walkie-talkie PTT & Voice Note loudspeaker: full-wattage MODE_NORMAL + STREAM_MUSIC
        assertTrue(Voice.isLoudSpeakerActive(true, false, false));
        assertTrue(Voice.isLoudSpeakerActive(true, false, true)); // hand near sensor during PTT must keep speaker on
        assertEquals(android.media.AudioManager.MODE_NORMAL, Voice.selectAudioMode(true, true, false));
        assertEquals(android.media.AudioManager.STREAM_MUSIC, Voice.selectOutputStream(true, false, false));

        // Full-duplex Mode Telepon on speakerphone: MODE_IN_COMMUNICATION (for hardware AEC) + STREAM_VOICE_CALL + boost
        assertTrue(Voice.isLoudSpeakerActive(true, true, false));
        assertEquals(android.media.AudioManager.MODE_IN_COMMUNICATION, Voice.selectAudioMode(true, true, true));
        assertEquals(android.media.AudioManager.STREAM_VOICE_CALL, Voice.selectOutputStream(true, true, false));

        // Mode Telepon held to ear (nearEar=true) OR speaker toggled OFF: earpiece without loudspeaker boost
        assertFalse(Voice.isLoudSpeakerActive(true, true, true));
        assertFalse(Voice.isLoudSpeakerActive(false, false, false));
        assertEquals(android.media.AudioManager.MODE_IN_COMMUNICATION, Voice.selectAudioMode(true, false, false));
        assertEquals(android.media.AudioManager.STREAM_VOICE_CALL, Voice.selectOutputStream(true, true, true));
        assertEquals(android.media.AudioManager.STREAM_VOICE_CALL, Voice.selectOutputStream(false, false, false));

        // Field volume floor: lifts low slider to >= 90% on group start, 100% on explicit speaker button ON
        assertEquals(14, Voice.targetFieldVolume(3, 15, false));
        assertEquals(15, Voice.targetFieldVolume(15, 15, false));
        assertEquals(15, Voice.targetFieldVolume(10, 15, true));
        assertEquals(6, Voice.targetFieldVolume(2, 6, false));
    }
}
