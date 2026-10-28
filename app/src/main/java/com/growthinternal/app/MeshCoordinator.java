package com.growthinternal.app;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coordinates multi-transport mesh auto-connect decisions, cooldowns, deduplication,
 * RuView-inspired Welford RSSI quality gating, Fresnel-zone relay placement scoring,
 * make-before-break handover detection, and zero-action passive relay hub lifecycle.
 */
final class MeshCoordinator {
    static final long AUTO_COOLDOWN_MS = 12000L;
    static final long DISCONNECT_PAUSE_MS = 15000L;
    private static final long DEDUP_WINDOW_MS = 10000L;
    private static final long STATS_TTL_MS = 30000L;

    // RuView QualityGate thresholds: extends BLE range from -82 dBm down to -91 dBm for coherent links
    static final int MIN_COHERENT_RSSI = -91;
    static final int STRONG_RSSI = -75;
    static final double MAX_SHIFT_DB = 10.0;
    static final double MAX_WEAK_STD_DEV = 4.0;
    static final double HANDOVER_DRIFT_DB = -2.0;
    // 2.4 GHz wavelength lambda = c / f = 299792458 / 2.4e9 ~= 0.125 m
    static final double WAVELENGTH_24GHZ_M = 0.125;

    /**
     * RuView-inspired Welford online running statistics (mean, variance, stdDev) + EMA drift per peer.
     */
    static final class LinkStats {
        int count;
        double mean;
        double m2;
        double emaDrift;
        int lastRssi;
        long lastSeenMs;

        void update(int rssi, long nowMs) {
            if (lastSeenMs > 0 && nowMs - lastSeenMs > STATS_TTL_MS) {
                count = 0;
                mean = 0.0;
                m2 = 0.0;
                emaDrift = 0.0;
            }
            if (count > 0) {
                double step = rssi - lastRssi;
                emaDrift = (count == 1) ? step : (0.4 * step + 0.6 * emaDrift);
            }
            count++;
            double delta = rssi - mean;
            mean += delta / count;
            double delta2 = rssi - mean;
            m2 += delta * delta2;
            lastRssi = rssi;
            lastSeenMs = nowMs;
        }

        double stdDev() {
            return count < 2 ? 0.0 : Math.sqrt(m2 / (count - 1));
        }
    }

    private final Map<String, Long> autoConnectCooldown = new HashMap<String, Long>();
    private final Map<String, Long> recentMeshTexts = new LinkedHashMap<String, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 256;
        }
    };
    private final Map<String, LinkStats> peerStats = new LinkedHashMap<String, LinkStats>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, LinkStats> eldest) {
            return size() > 128;
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
        peerStats.clear();
    }

    /**
     * RuView 3-filter QualityGate (Structural + Shift/Multipath + Welford Coherence):
     * - Passes strong/normal beacons (>= -82 dBm) unless rejected as a sudden multipath spike.
     * - Extends effective BLE range down to -91 dBm when >= 2 samples show low stdDev (<= 4.0 dB).
     */
    boolean evaluateRssi(String peerName, int rssi) {
        return evaluateRssi(peerName, rssi, System.currentTimeMillis());
    }

    boolean evaluateRssi(String peerName, int rssi, long nowMs) {
        if (peerName == null || rssi >= 0) return false;
        LinkStats st = peerStats.get(peerName);
        if (st == null) {
            st = new LinkStats();
            peerStats.put(peerName, st);
        }
        double prevMean = st.mean;
        int prevCount = st.count;
        st.update(rssi, nowMs);
        if (rssi < MIN_COHERENT_RSSI) return false;
        // Filter 2 (Shift/Multipath spike filter): reject sudden jump > 10 dB from weak baseline
        if (prevCount >= 2 && rssi < STRONG_RSSI && Math.abs(rssi - prevMean) > MAX_SHIFT_DB) {
            return false;
        }
        // Filter 3 (Evidence & Coherence filter): immediate pass >= -82 dBm; coherent pass -91..-83 dBm
        if (rssi >= Ble.MIN_AUTO_RSSI) return true;
        return st.count >= 2 && st.stdDev() <= MAX_WEAK_STD_DEV;
    }

    /**
     * Returns true when a peer's RSSI is in the weak edge zone (<= -82 dBm) with consistent
     * negative drift (<= -2.0 dB), signaling a proactive Make-Before-Break BLE handover.
     */
    boolean shouldHandoverToBle(String peerName) {
        if (peerName == null) return false;
        LinkStats st = peerStats.get(peerName);
        return st != null && st.count >= 2
                && st.lastRssi >= MIN_COHERENT_RSSI
                && st.lastRssi <= Ble.MIN_AUTO_RSSI
                && st.emaDrift <= HANDOVER_DRIFT_DB;
    }

    boolean canAutoConnect(String key, String peerName, String myName, Hub activeHub) {
        if (key == null || peerName == null || peerName.equals(myName)) return false;
        if (activeHub != null && activeHub.hasMember(peerName)) {
            boolean upgradeToWifi = (key.startsWith("aware:") || key.startsWith("lan:"))
                    && activeHub.hasLowBandwidthLink(peerName);
            boolean handoverToBle = key.startsWith("bt:")
                    && !activeHub.hasLowBandwidthLink(peerName)
                    && shouldHandoverToBle(peerName);
            if (!upgradeToWifi && !handoverToBle) return false;
        }
        long now = System.currentTimeMillis();
        if (now < autoConnectPauseUntil) return false;
        Long last = autoConnectCooldown.get(key);
        if (last != null && now - last < AUTO_COOLDOWN_MS) return false;
        autoConnectCooldown.put(key, now);
        return true;
    }

    /**
     * RuView Fresnel-zone first radius F1 = sqrt(lambda * d1 * d2 / (d1 + d2)) at 2.4 GHz.
     */
    static double fresnelRadiusMeters(double d1Meters, double d2Meters) {
        if (d1Meters <= 0.0 || d2Meters <= 0.0) return 0.0;
        return Math.sqrt((WAVELENGTH_24GHZ_M * d1Meters * d2Meters) / (d1Meters + d2Meters));
    }

    /**
     * Scores how well a candidate node sits inside the 2.4 GHz Fresnel relay corridor between A and B.
     * Returns a score in [0.0, 1.0], where 1.0 is the exact line-of-sight midpoint.
     */
    static double relayPlacementScore(Presence a, Presence b, Presence candidate) {
        if (a == null || b == null || candidate == null
                || !a.hasLocation || !b.hasLocation || !candidate.hasLocation) {
            return 0.0;
        }
        double midLatRad = Math.toRadians((a.lat + b.lat) * 0.5);
        double mPerDegLat = 111132.0;
        double mPerDegLon = 111319.5 * Math.cos(midLatRad);
        double bx = (b.lon - a.lon) * mPerDegLon;
        double by = (b.lat - a.lat) * mPerDegLat;
        double dSq = bx * bx + by * by;
        double distAb = Math.sqrt(dSq);
        if (distAb < 30.0) return 0.0;

        double cx = (candidate.lon - a.lon) * mPerDegLon;
        double cy = (candidate.lat - a.lat) * mPerDegLat;
        double t = (cx * bx + cy * by) / dSq;
        if (t < 0.15 || t > 0.85) return 0.0;

        double d1 = Math.sqrt(cx * cx + cy * cy);
        double d2 = Math.sqrt((bx - cx) * (bx - cx) + (by - cy) * (by - cy));
        double crossTrackM = Math.abs(bx * cy - by * cx) / distAb;
        double f1 = fresnelRadiusMeters(d1, d2);
        double corridorRadiusM = Math.max(12.0, 4.0 * f1 + 8.0);
        double centrality = 1.0 - 2.0 * Math.abs(t - 0.5);
        double clearance = 1.0 / (1.0 + (crossTrackM / corridorRadiusM) * (crossTrackM / corridorRadiusM));
        return centrality * clearance;
    }

    /**
     * Selects the member name with the highest Fresnel corridor placement score
     * between myPresence and the furthest/SOS peer in presences, or null if no relay is needed.
     */
    static String selectBestRelayCandidate(Presence myPresence, Map<String, Presence> presences) {
        if (myPresence == null || !myPresence.hasLocation || presences == null || presences.isEmpty()) {
            return null;
        }
        String targetName = null;
        Presence targetPresence = null;
        float maxDist = 0f;
        for (Map.Entry<String, Presence> e : presences.entrySet()) {
            Presence p = e.getValue();
            if (p == null || !p.hasLocation) continue;
            float d = myPresence.distanceTo(p);
            boolean priority = p.status == Presence.SOS && (targetPresence == null || targetPresence.status != Presence.SOS);
            if (priority || ((targetPresence == null || targetPresence.status == p.status) && d > maxDist)) {
                maxDist = d;
                targetName = e.getKey();
                targetPresence = p;
            }
        }
        if (targetPresence == null || maxDist < 50f) return null;

        String bestCandidate = null;
        double bestScore = 0.25;
        for (Map.Entry<String, Presence> e : presences.entrySet()) {
            if (e.getKey().equals(targetName)) continue;
            double score = relayPlacementScore(myPresence, targetPresence, e.getValue());
            if (score > bestScore) {
                bestScore = score;
                bestCandidate = e.getKey();
            }
        }
        return bestCandidate;
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
