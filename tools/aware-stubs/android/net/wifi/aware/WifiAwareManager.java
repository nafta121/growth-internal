package android.net.wifi.aware;

import android.os.Handler;

public class WifiAwareManager {
    public static final String ACTION_WIFI_AWARE_STATE_CHANGED =
            "android.net.wifi.aware.action.WIFI_AWARE_STATE_CHANGED";

    public boolean isAvailable() { return false; }
    public void attach(AttachCallback attachCallback, Handler handler) {}
}
