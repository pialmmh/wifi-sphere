package com.telcobright.wifisphere.auth;

/**
 * One MAC's authoritative auth state (lifecycle B — mutable, event-driven, persisted).
 * This is the business-rich record wifi-sphere OWNS; only its lean enforcement projection
 * {disposition, rate, quota, deadline} is ever pushed to the data plane.
 */
public record AuthState(
        String mac,
        int tenantId,
        Integer vlanId,          // null on the untagged pilot lanes
        Disposition disposition,
        String tier,             // free | paid
        long grantedSeconds,
        long startedAtMs,
        long deadlineEpochSec,   // 0 = no deadline (legacy permanent release)
        String msisdn,           // nullable until the BSS binding lands
        String siteId) {         // resolved via the vlanTable at grant time; null when unknown

    public enum Disposition { RELEASED, GARDEN, BLACKLIST }

    public boolean expired(long nowEpochSec) {
        return disposition == Disposition.RELEASED && deadlineEpochSec > 0 && nowEpochSec >= deadlineEpochSec;
    }

    public AuthState gardened() {
        return new AuthState(mac, tenantId, vlanId, Disposition.GARDEN, tier, grantedSeconds,
                startedAtMs, deadlineEpochSec, msisdn, siteId);
    }
}
