package com.telcobright.routesphere.wifimachine.v2.port;

import java.util.List;

/**
 * One finished WiFi data session — the CDR of this domain.
 *
 * <p>{@code outcome} is the supervisor's final state (ENDED / REJECTED);
 * {@code reason} says why (granted-session-over, maxSession, silent, noAuth,
 * left). A REJECTED record's probe fields are the analytics payload: which
 * device families try the captive flow and never make it through.
 */
public record WifiSessionRecord(
    String sessionId,
    String mac,
    String outcome,
    String reason,
    long firstSeenMs,
    long authedAtMs,      // 0 = never authenticated
    long endedAtMs,
    int probeCount,
    List<String> probeLabels) {}
