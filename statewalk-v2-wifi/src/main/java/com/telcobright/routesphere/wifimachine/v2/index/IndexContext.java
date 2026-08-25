package com.telcobright.routesphere.wifimachine.v2.index;

import java.util.LinkedHashMap;
import java.util.Map;

/** Aggregate view held by one index machine (one user or one zone). */
public class IndexContext {

    public String key;

    /** mac → row for every live session under this key. */
    public Map<String, MemberRow> members = new LinkedHashMap<>();

    public long seenTotal;
    public long establishedTotal;
    public long closedTotal;

    public record MemberRow(String sessionId, String mac, String msisdn, String state) {}
}
