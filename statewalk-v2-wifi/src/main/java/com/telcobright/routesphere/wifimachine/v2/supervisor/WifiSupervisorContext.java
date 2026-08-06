package com.telcobright.routesphere.wifimachine.v2.supervisor;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-session state for one WiFi data session (public-field POJO per statewalk
 * convention; the machine classes themselves hold no mutable fields).
 *
 * <p>Nothing here re-runs on rehydration — the pilot runs without persistence,
 * like the call and SMS registries do in production.
 */
public class WifiSupervisorContext {

    public String sessionId;      // <macNoColons>-<firstSeenEpochSec> — NEVER the bare MAC
    public String mac;
    public String ip;             // last ip seen in a probe, may stay null
    public long firstSeenMs;

    public long authedAtMs;       // 0 until AUTHENTICATED
    public long endedAtMs;

    /** why the session finished: granted-session-over, maxSession, silent, noAuth, left */
    public String endReason;

    // probe summary reported by the WifiSignaling child (may be partial when
    // the device left mid-signaling — the raw event stream keeps everything)
    public int probeCount;
    public List<String> probeLabels = new ArrayList<>();
}
