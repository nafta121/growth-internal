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
    static final byte VOICE_NOTE = 16;  // 4-bit IMA ADPCM recorded voice note (store-and-forward)
    static final byte CLUSTER_UPDATE = 17; // BlueRange CLUSTER_INFO_UPDATE: 16-bit clusterId + 16-bit clusterSize
    static final byte IMAGE_CHUNK = 18; // FISSURE/BlueRange 8 KB chunked image transfer (non-blocking)
    static final byte MESH_RELAY = 19;  // Cross-transport BLE <-> Hub multi-hop relay envelope
    static final byte AUDIO_HEMB = 20;  // MeshSat HeMB GF(2^8) RLNC voice parity frame

    /** Largest payload accepted per type, so a bad peer cannot exhaust memory. */
    static int maxPayload(byte type) {
        switch (type) {
            case HELLO: return 64 * 1024; // name + small JPEG avatar
            case AUDIO: return 8 * 1024;
            case AUDIO_ADPCM:
            case AUDIO_HEMB: return 2 * 1024;
            case VOICE_NOTE: return 48 * 1024;
            case TEXT:
            case ANNOUNCE:
            case REJECT:
            case MESH_RELAY: return 8 * 1024;
            case PRESENCE: return 256;
            case IMAGE: return Photos.MAX_BYTES; // one compressed JPEG
            case VIDEO_CHUNK: return Clips.HEADER + Clips.CHUNK;
            case IMAGE_CHUNK: return Clips.HEADER + Clips.IMAGE_CHUNK;
            case JOIN_INFO: return 512;
            case CLUSTER_UPDATE: return 16;
            default: return 0;
        }
    }

    final byte type;
    final String sender;
    final byte[] payload;
    final long createdMs;
    final boolean priority;

    Frame(byte type, String sender, byte[] payload) {
        this(type, sender, payload, System.currentTimeMillis(), false);
    }

    Frame(byte type, String sender, byte[] payload, boolean priority) {
        this(type, sender, payload, System.currentTimeMillis(), priority);
    }

    Frame(byte type, String sender, byte[] payload, long createdMs) {
        this(type, sender, payload, createdMs, false);
    }

    Frame(byte type, String sender, byte[] payload, long createdMs, boolean priority) {
        this.type = type;
        this.sender = sender;
        this.payload = payload == null ? new byte[0] : payload;
        this.createdMs = createdMs;
        this.priority = priority;
    }
}
