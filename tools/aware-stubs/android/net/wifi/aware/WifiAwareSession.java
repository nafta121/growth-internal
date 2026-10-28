package android.net.wifi.aware;

import android.os.Handler;

public class WifiAwareSession implements AutoCloseable {
    @Override public void close() {}
    public void publish(PublishConfig publishConfig, DiscoverySessionCallback callback, Handler handler) {}
    public void subscribe(SubscribeConfig subscribeConfig, DiscoverySessionCallback callback, Handler handler) {}
}
