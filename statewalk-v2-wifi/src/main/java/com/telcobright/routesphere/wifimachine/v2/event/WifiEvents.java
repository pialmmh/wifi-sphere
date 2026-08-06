package com.telcobright.routesphere.wifimachine.v2.event;

import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

/**
 * Wire-side events for the WiFi v2 machines — one record per gateway
 * observation on the {@code wifi:evt:gateway} stream. The channel bridge
 * translates stream entries into these; the machines never see Redis.
 *
 * <p>Naming is business-side ("GrantObserved", not "dispositionChanged
 * garden→release") — the bridge does the translating, like the ESL adapter
 * does for calls.
 */
public final class WifiEvents {

    private WifiEvents() {}

    /**
     * A MAC appeared in the gateway's learned table — the device is behind the
     * walled garden and is now "being authenticated" (signaling). Starts a new
     * session machine.
     */
    public record DeviceSeen(String mac, String disposition) implements StatemachineEvent {
        @Override public boolean isFirst() { return true; }
    }

    /**
     * A gardened device hit the captive portal. {@code probeLabel} is the
     * user-agent family the portal parsed (e.g. "Firefox (canonical)",
     * "Android (gms)") — the raw material for which-brands-fail analytics.
     */
    public record CaptiveProbe(String mac, String ip, String probeLabel) implements StatemachineEvent {}

    /** The gateway flipped this MAC garden→release: somebody granted internet. */
    public record GrantObserved(String mac) implements StatemachineEvent {}

    /** The gateway flipped this MAC release→garden: the session's internet ended. */
    public record GrantRevoked(String mac) implements StatemachineEvent {}

    /** The MAC left the learned table entirely (idle-aged or table reset). */
    public record DeviceGone(String mac) implements StatemachineEvent {}
}
