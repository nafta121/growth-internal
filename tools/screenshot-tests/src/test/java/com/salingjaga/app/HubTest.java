package com.salingjaga.app;

import static org.junit.Assert.*;

import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Runs three hubs over real loopback sockets: relay, presence replay, anti-spoofing, duplicate names. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HubTest {
    static class Rec implements Hub.Callback {
        final List<String> log = new CopyOnWriteArrayList<String>();
        public void onStatus(String m) { }
        public void onMemberJoined(String n, byte[] p) { log.add("join:" + n); }
        public void onMemberLeft(String n) { log.add("left:" + n); }
        public void onText(String f, String t) { log.add("text:" + f + ":" + t); }
        public void onAudio(String f, byte[] p) { log.add("audio:" + f + ":" + p.length); }
        public void onTalkState(String f, boolean t) { }
        public void onPresence(String f, byte[] p) { log.add("presence:" + f + ":" + Presence.decode(p).status); }
        public void onAnnouncement(String f, String t) { log.add("announce:" + f + ":" + t); }
        public void onRejected(String r) { log.add("rejected"); }
        public void onImage(String f, byte[] j) { log.add("image:" + f + ":" + j.length); }
        public void onVideoIncoming(String f) { log.add("video-incoming:" + f); }
        public void onVideo(String f, byte[] v) { log.add("video:" + f + ":" + v.length + ":" + v[v.length - 1]); }
    }

    static void waitFor(Rec r, String entry) throws Exception {
        List<String> log = r.log;
        for (int i = 0; i < 100 && !log.contains(entry); i++) Thread.sleep(50);
        assertTrue("missing " + entry + " in " + log, log.contains(entry));
    }

    @Test public void groupWorks() throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        Rec ro = new Rec(), ra = new Rec(), rb = new Rec(), rc = new Rec();
        Hub owner = new Hub("Pak RT", null, ro);
        owner.startServer(lo);
        Thread.sleep(300);
        Presence sos = new Presence();
        sos.status = Presence.SOS;
        Hub a = new Hub("Budi", null, ra);
        a.startClient(lo);
        waitFor(ro, "join:Budi");
        a.sendPresence(sos.encode());
        waitFor(ro, "presence:Budi:4");

        Hub b = new Hub("Sari", null, rb);
        b.startClient(lo);
        waitFor(rb, "join:Budi");               // owner replays existing members
        waitFor(rb, "presence:Budi:4");         // ...and their SOS
        waitFor(ra, "join:Sari");

        b.sendText("halo");
        waitFor(ra, "text:Sari:halo");          // relayed through the owner
        owner.sendAnnouncement("kumpul di SD 2");
        waitFor(rb, "announce:Pak RT:kumpul di SD 2");
        a.sendAudio(new byte[640]);
        waitFor(rb, "audio:Budi:640");
        b.sendImage(new byte[300 * 1024]);
        waitFor(ra, "image:Sari:" + (300 * 1024));   // a full-size photo is relayed whole
        byte[] clip = new byte[1024 * 1024 + 123];
        clip[clip.length - 1] = 7;
        b.sendVideo(clip);
        a.sendAudio(new byte[320]);
        waitFor(rb, "audio:Budi:320");               // voice still flows while the clip is chunked
        waitFor(ra, "video-incoming:Sari");
        waitFor(ra, "video:Sari:" + clip.length + ":7");   // reassembled in order
        waitFor(ro, "video:Sari:" + clip.length + ":7");

        // A raw client claiming to be Budi is ignored.
        Socket s = new Socket(lo, Hub.PORT);
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        out.writeByte(Frame.HELLO); out.writeUTF("Eve"); out.writeInt(0);
        out.writeByte(Frame.ANNOUNCE); out.writeUTF("Budi"); byte[] fake = "palsu".getBytes("UTF-8");
        out.writeInt(fake.length); out.write(fake); out.flush();
        waitFor(ra, "join:Eve");
        Thread.sleep(500);
        assertFalse(rb.log.contains("announce:Budi:palsu"));

        // Oversized frame closes the connection.
        out.writeByte(Frame.TEXT); out.writeUTF("Eve"); out.writeInt(5 << 20); out.flush();
        waitFor(ra, "left:Eve");
        s.close();

        // Duplicate name is rejected.
        Hub dup = new Hub("Sari", null, rc);
        dup.startClient(lo);
        waitFor(rc, "rejected");

        // Leaving is noticed by the others.
        a.stop();
        waitFor(rb, "left:Budi");
        b.stop(); dup.stop(); owner.stop();
    }
}
