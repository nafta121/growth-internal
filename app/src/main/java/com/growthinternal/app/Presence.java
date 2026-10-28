package com.growthinternal.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** What a member shares about themselves: safety status, battery and last GPS fix. */
final class Presence {
    static final int UNSET = 0;
    static final int SAFE = 1;
    static final int NEED_HELP = 2;
    static final int INJURED = 3;
    static final int SOS = 4;

    int status = UNSET;
    int battery = -1;
    boolean charging;
    boolean hasLocation;
    double lat;
    double lon;
    float accuracy;

    static String label(int status) {
        switch (status) {
            case SAFE: return "Standby";
            case NEED_HELP: return "Butuh Backup";
            case INJURED: return "Kendala";
            case SOS: return "PRIORITAS";
            default: return "Belum diatur";
        }
    }

    byte[] encode() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(1); // format version
            out.writeByte(status);
            out.writeByte(battery);
            out.writeBoolean(charging);
            out.writeBoolean(hasLocation);
            out.writeDouble(lat);
            out.writeDouble(lon);
            out.writeFloat(accuracy);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Presence decode(byte[] b) {
        Presence p = new Presence();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            in.readByte();
            p.status = in.readByte();
            p.battery = in.readByte();
            p.charging = in.readBoolean();
            p.hasLocation = in.readBoolean();
            p.lat = in.readDouble();
            p.lon = in.readDouble();
            p.accuracy = in.readFloat();
        } catch (IOException ignored) {
            // Older or truncated payload: keep what was read.
        }
        return p;
    }
}
