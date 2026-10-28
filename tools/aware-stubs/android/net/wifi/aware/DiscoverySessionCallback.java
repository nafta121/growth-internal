package android.net.wifi.aware;

import java.util.List;

public class DiscoverySessionCallback {
    public DiscoverySessionCallback() {}
    public void onPublishStarted(PublishDiscoverySession session) {}
    public void onSubscribeStarted(SubscribeDiscoverySession session) {}
    public void onServiceDiscovered(PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter) {}
    public void onMessageReceived(PeerHandle peerHandle, byte[] message) {}
    public void onSessionTerminated() {}
}
