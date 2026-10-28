package android.net.wifi.aware;

import android.net.NetworkSpecifier;

public class DiscoverySession implements AutoCloseable {
    @Override public void close() {}
    public void sendMessage(PeerHandle peerHandle, int messageId, byte[] message) {}
    public NetworkSpecifier createNetworkSpecifierOpen(PeerHandle peerHandle) { return null; }
}
