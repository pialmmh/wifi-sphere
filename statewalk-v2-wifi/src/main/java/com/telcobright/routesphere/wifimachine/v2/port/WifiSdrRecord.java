package com.telcobright.routesphere.wifimachine.v2.port;

import java.util.List;

/**
 * One finished session leg — the SDR (the call-CDR twin of this domain).
 * {@code outcome} = TERMINATED | REJECTED; {@code releaseCause} = cap,
 * minutes, volume, idle, external, coa, kick, gone, silent, noAuth, left.
 * {@code partialFlag} chains 3-hour legs exactly like long calls.
 */
public record WifiSdrRecord(
    String sessionId,
    String mac,
    String msisdn,
    String ip,
    String site,
    String zone,
    String vlan,
    String outcome,
    String releaseCause,
    long firstSeenMs,
    long establishedAtMs,   // 0 = never established
    long endedAtMs,
    long activeSeconds,
    long bytesUp,
    long bytesDn,
    String purchaseId,
    int grantedMinutes,
    boolean partialFlag,
    int probeCount,
    List<String> probeLabels) {}
