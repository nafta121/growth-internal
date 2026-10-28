package android.net.wifi.aware;

public final class SubscribeConfig {
    public static final int SUBSCRIBE_TYPE_PASSIVE = 0;

    public static final class Builder {
        public Builder() {}
        public Builder setServiceName(String serviceName) { return this; }
        public Builder setSubscribeType(int subscribeType) { return this; }
        public SubscribeConfig build() { return null; }
    }
}
