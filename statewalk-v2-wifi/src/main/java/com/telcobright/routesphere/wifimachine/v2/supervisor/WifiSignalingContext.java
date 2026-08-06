package com.telcobright.routesphere.wifimachine.v2.supervisor;

import java.util.ArrayList;
import java.util.List;

/** Per-session state for the signaling child: the captive-flow progress log. */
public class WifiSignalingContext {

    public String sessionId;
    public String mac;

    public int probeCount;
    public List<String> probeLabels = new ArrayList<>(); // distinct, in first-seen order
    public long firstProbeMs;
    public long lastProbeMs;
    public boolean startReported; // SignalingStarted published exactly once
}
