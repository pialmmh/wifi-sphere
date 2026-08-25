package com.telcobright.routesphere.wifimachine.v2.policy;

/**
 * The 19 admin-portal session knobs (the frozen "session" block of the
 * per-gateway runtime doc). All durations in seconds. Hot reload swaps the
 * whole record atomically via an AtomicReference; state clocks are baked
 * per pooled machine instance at construction, handler-side checks (idle,
 * budgets, freshness) always read the current record.
 */
public record SessionPolicy(
    int maxDevicesPerUser,
    int maxDevicesPerUserPerZone,   // 0 = off
    String zoneScope,               // any | single_zone
    int timeoutInitSec,
    int timeoutCaptiveSec,
    int timeoutOtpSec,
    int timeoutPaymentSec,
    int establishedMaxSec,          // the 3h cap
    int establishedIdleSec,         // 0 = off
    String renewMode,               // one_click | relogin
    int expiringGraceSec,
    int terminatedLingerSec,
    int userDormantEvictSec,
    int radiusInterimSec,
    boolean sdrPartialAtCap,
    int sdrRetentionDays,
    int siglogRetentionDays,
    boolean siglogDebug,
    int livenessStaleSec) {

    /** The ratified production defaults (2026-08-26). */
    public static SessionPolicy defaults() {
        return new SessionPolicy(3, 0, "any", 600, 900, 300, 1200, 10800, 1800,
            "one_click", 30, 60, 86400, 300, true, 400, 90, false, 180);
    }

    public SessionPolicy {
        if (maxDevicesPerUser < 1) throw new IllegalArgumentException("maxDevicesPerUser >= 1");
        if (establishedMaxSec < 1) throw new IllegalArgumentException("establishedMaxSec >= 1");
        if (!"any".equals(zoneScope) && !"single_zone".equals(zoneScope))
            throw new IllegalArgumentException("zoneScope must be any|single_zone");
    }
}
