package android.net.wifi.aware;

import android.net.TransportInfo;
import java.net.Inet6Address;

public final class WifiAwareNetworkInfo implements TransportInfo {
    public Inet6Address getPeerIpv6Addr() { return null; }
    public int getPort() { return 0; }
}
