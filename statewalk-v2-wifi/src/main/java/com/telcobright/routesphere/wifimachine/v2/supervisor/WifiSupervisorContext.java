package com.telcobright.routesphere.wifimachine.v2.supervisor;

import java.util.ArrayList;
import java.util.List;

/** Per-session state for one device session (public-field POJO per statewalk convention). */
public class WifiSupervisorContext {

    public String sessionId;      // <macNoColons>-<firstSeenEpochSec> — NEVER the bare MAC
    public String mac;
    public String vlan;
    public String ip;
    public String site;           // resolved from vlan at birth
    public String zone;           // resolved from vlan at birth
    public String msisdn;         // null while anonymous; set at birth (returning MAC) or first login

    public long firstSeenMs;
    public long establishedAtMs;  // 0 until ESTABLISHED
    public long endedAtMs;

    public int grantedMinutes;    // 0 = unbounded by minutes
    public long volumeBudgetBytes;// 0 = unbounded by volume
    public String purchaseId;
    public String method;         // portal | observed

    /** cap, minutes, volume, idle, external, coa, kick, gone, silent, noAuth, left */
    public String endReason;
    public boolean tornDown;   // EXPIRING ran its teardown (gate + acct-stop)

    public long bytesUp;
    public long bytesDn;
    public long activeSeconds;

    public int probeCount;
    public List<String> probeLabels = new ArrayList<>();
}
