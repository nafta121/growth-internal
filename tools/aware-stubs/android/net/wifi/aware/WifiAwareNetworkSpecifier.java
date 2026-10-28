package android.net.wifi.aware;

import android.net.NetworkSpecifier;

public final class WifiAwareNetworkSpecifier extends NetworkSpecifier {
    public static final class Builder {
        public Builder(DiscoverySession discoverySession, PeerHandle peerHandle) {}
        public Builder setPskPassphrase(String pskPassphrase) { return this; }
        public Builder setPort(int port) { return this; }
        public WifiAwareNetworkSpecifier build() { return null; }
    }
}
