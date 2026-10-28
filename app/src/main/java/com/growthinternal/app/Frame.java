package com.growthinternal.app;

/** One message on the wire: type + original sender name + payload. */
final class Frame {
    static final byte HELLO = 1;
    static final byte TEXT = 2;
    static final byte AUDIO = 3;
    static final byte TALK_START = 4;
    static final byte TALK_END = 5;
    static final byte LEAVE = 6;
    static final byte PING = 7;
    static final byte PRESENCE = 8;
    static final byte ANNOUNCE = 9;
    static final byte REJECT = 10;
    static final byte IMAGE = 11;
    static final byte VIDEO_CHUNK = 12;
    static final byte BRIDGE = 13;     // "I relay for phones behind me", sent once by a bridge
    static final byte JOIN_INFO = 14;  // owner -> direct member: how to reach this group as a bridge
    static final byte AUDIO_ADPCM = 15; // 4-bit IMA ADPCM audio for low-bandwidth Bluetooth links

    /** Largest payload accepted per type, so a bad peer cannot exhaust memory. */
    static int maxPayload(byte type) {
        switch (type) {
            case HELLO: return 64 * 1024; // name + small JPEG avatar
            case AUDIO: return 8 * 1024;
            case AUDIO_ADPCM: return 2 * 1024;
            case TEXT:
            case ANNOUNCE:
            case REJECT: return 8 * 1024;
            case PRESENCE: return 256;
            case IMAGE: return Photos.MAX_BYTES; // one compressed JPEG
            case VIDEO_CHUNK: return Clips.HEADER + Clips.CHUNK;
            case JOIN_INFO: return 512;
            default: return 0;
        }
    }

    final byte type;
    final String sender;
    final byte[] payload;

    Frame(byte type, String sender, byte[] payload) {
        this.type = type;
        this.sender = sender;
        this.payload = payload == null ? new byte[0] : payload;
    }
}
