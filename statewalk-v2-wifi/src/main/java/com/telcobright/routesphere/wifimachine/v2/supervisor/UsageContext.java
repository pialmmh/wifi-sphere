package com.telcobright.routesphere.wifimachine.v2.supervisor;

/** Per-session accounting state for the UsageTracker child. */
public class UsageContext {

    public String sessionId;
    public String mac;

    public boolean metering;
    public long establishedAtMs;
    public int minutesBudget;        // 0 = unbounded
    public long volumeBudgetBytes;   // 0 = unbounded

    public long bytesUp;
    public long bytesDn;
    public long lastTrafficMs;

    public boolean idleReported;
    public boolean exhaustReported;
}
