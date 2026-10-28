package android.net.wifi.aware;

public final class PublishConfig {
    public static final int PUBLISH_TYPE_UNSOLICITED = 0;

    public static final class Builder {
        public Builder() {}
        public Builder setServiceName(String serviceName) { return this; }
        public Builder setServiceSpecificInfo(byte[] serviceSpecificInfo) { return this; }
        public Builder setPublishType(int publishType) { return this; }
        public PublishConfig build() { return null; }
    }
}
