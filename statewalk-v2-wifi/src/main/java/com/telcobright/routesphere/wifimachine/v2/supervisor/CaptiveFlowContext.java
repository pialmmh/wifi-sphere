package com.telcobright.routesphere.wifimachine.v2.supervisor;

import java.util.ArrayList;
import java.util.List;

/** Per-attempt state for the captive-flow child: sign-in progress + probe log. */
public class CaptiveFlowContext {

    public String sessionId;
    public String mac;

    public int probeCount;
    public List<String> probeLabels = new ArrayList<>();
    public long firstProbeMs;
    public long lastProbeMs;
    public boolean startReported;

    public String msisdn;
    public int minutes;
    public long volumeBytes;
    public String purchaseId;
    public String method;   // portal | observed
}
