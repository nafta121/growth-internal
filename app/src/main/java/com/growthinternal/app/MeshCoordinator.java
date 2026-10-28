package com.growthinternal.app;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coordinates multi-transport mesh auto-connect decisions, cooldowns, deduplication,
 * and zero-action passive relay hub lifecycle across Wi-Fi Direct, Wi-Fi Aware,
 * Hotspot/LAN, Bluetooth L2CAP/RFCOMM, and BLE MeshAccess (0xFE12).
 */
final class MeshCoordinator {
    static final long AUTO_COOLDOWN_MS = 12000L;
    static final long DISCONNECT_PAUSE_MS = 15000L;
    private static final long DEDUP_WINDOW_MS = 10000L;

    private final Map<String, Long> autoConnectCooldown = new HashMap<String, Long>();
    private final Map<String, Long> recentMeshTexts = new LinkedHashMap<String, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 256;
        }
    };
    private long autoConnectPauseUntil;
    private Hub relayHub;

    boolean isPaused() {
        return System.currentTimeMillis() < autoConnectPauseUntil;
    }

    void pauseAfterDisconnect() {
        autoConnectPauseUntil = System.currentTimeMillis() + DISCONNECT_PAUSE_MS;
    }

    void clearPause() {
        autoConnectPauseUntil = 0;
    }

    void resetCooldowns() {
        autoConnectPauseUntil = 0;
        autoConnectCooldown.clear();
    }

    boolean canAutoConnect(String key, String peerName, String myName, Hub activeHub) {
        if (key == null || peerName == null || peerName.equals(myName)) return false;
        if (activeHub != null && activeHub.hasMember(peerName)) return false;
        long now = System.currentTimeMillis();
        if (now < autoConnectPauseUntil) return false;
        Long last = autoConnectCooldown.get(key);
        if (last != null && now - last < AUTO_COOLDOWN_MS) return false;
        autoConnectCooldown.put(key, now);
        return true;
    }

    boolean shouldAcceptMeshText(String senderKey, String text) {
        long now = System.currentTimeMillis();
        String key = senderKey + ":" + text;
        Long prev = recentMeshTexts.get(key);
        recentMeshTexts.put(key, now);
        return prev == null || now - prev > DEDUP_WINDOW_MS;
    }

    Hub ensureRelayHub(String myName, Runnable onClusterChanged) {
        if (relayHub == null) {
            relayHub = new Hub(myName + "#relay", null, Hub.NOOP_CALLBACK)
                    .asOwner(true)
                    .onClusterChanged(onClusterChanged);
        }
        return relayHub;
    }

    void stopRelayHub() {
        if (relayHub != null) {
            relayHub.stop();
            relayHub = null;
        }
    }

    int activeClusterId(Hub activeHub) {
        if (activeHub != null) return activeHub.clusterId();
        if (relayHub != null) return relayHub.clusterId();
        return 0;
    }

    /**
     * Returns 1 to auto-join peer as client, 2 to auto-start owner hub for peer,
     * or 0 to do nothing. Resolves split-brain by preferring larger groups,
     * breaking ties deterministically by name, and preventing loops via BlueRange ClusterId.
     */
    static int autoConnectDecision(boolean myInGroup, int myMemberCount, String myName,
            boolean peerInGroup, int peerMemberCount, String peerName) {
        return autoConnectDecision(myInGroup, myMemberCount, myName, 0,
                peerInGroup, peerMemberCount, peerName, 0);
    }

    static int autoConnectDecision(boolean myInGroup, int myMemberCount, String myName, int myClusterId,
            boolean peerInGroup, int peerMemberCount, String peerName, int peerClusterId) {
        if (myName == null || peerName == null || myName.equals(peerName)) return 0;
        // BlueRange ClusterId loop prevention: nodes in the same cluster are already connected multi-hop
        if (myClusterId != 0 && myClusterId == peerClusterId) return 0;
        if (peerInGroup) {
            if (!myInGroup) return 1;
            if (myMemberCount <= 1) {
                if (peerMemberCount > 1 || myName.compareTo(peerName) > 0) return 1;
            } else if (myClusterId != 0 && peerClusterId != 0 && myClusterId != peerClusterId) {
                if (peerMemberCount > myMemberCount
                        || (peerMemberCount == myMemberCount && myClusterId > peerClusterId)) {
                    return 1;
                }
            }
            return 0;
        }
        if (!myInGroup) {
            return myName.compareTo(peerName) < 0 ? 2 : 0;
        }
        return 0;
    }
}
