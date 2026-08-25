package com.telcobright.routesphere.wifimachine.v2.port;

/**
 * The engine's hands on the gateway MAC table. Called from exactly two
 * places: ESTABLISHED entry (release) and EXPIRING entry (garden) — the
 * terminal-only-side-effects invariant is structural. A SHADOW
 * implementation records instead of enforcing; the authority flip is a
 * port swap, never a machine change.
 */
public interface GatePort {

    /** The session established — this MAC gets internet. */
    void release(String mac, String sessionId, int minutes);

    /** The session ended — this MAC goes back behind the walled garden. */
    void garden(String mac);
}
