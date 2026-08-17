package com.telcobright.wifisphere.radius;

import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staged session grants for the RADIUS round-trip: the authorizer (or pay-watcher) stages
 * {mac -> seconds} just before its Access-Request; FreeRADIUS (rlm_rest) consumes it within the
 * same request, and the ACCEPT's Session-Timeout carries the purchased duration.
 *
 * A grant is consumed exactly once, and a stale one (Access-Request never arrived) dies by TTL.
 * When the entitlement cache (BSS msisdn-keyed + mac binding) lands, lookups fall back to it
 * before answering "no grant".
 */
@Singleton
public class RadiusGrantStore {

    public record Grant(int seconds, String tier, long stagedAtMs) { }

    private static final long TTL_MS = 60_000;

    private final Map<String, Grant> pending = new ConcurrentHashMap<>();

    public void stage(String mac, int seconds, String tier) {
        pending.put(norm(mac), new Grant(seconds, tier == null ? "paid" : tier, System.currentTimeMillis()));
    }

    /** Consume-once semantics; expired entries answer null. */
    public Grant consume(String mac) {
        Grant g = pending.remove(norm(mac));
        if (g == null) return null;
        return (System.currentTimeMillis() - g.stagedAtMs()) < TTL_MS ? g : null;
    }

    public int size() { return pending.size(); }

    private static String norm(String mac) {
        return mac == null ? "" : mac.trim().toLowerCase();
    }
}
