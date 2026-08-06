package com.telcobright.routesphere.wifimachine.v2.port;

/**
 * The machine's write path toward the gateway MAC table (the macTableSync
 * producer role). Implementations decide whether that write is REAL (the
 * wifi:mac:* keys the gateway's config agent consumes) or SHADOW (a
 * would-have-written record while the legacy authorizer still grants).
 *
 * <p>Called ONLY from terminal/authenticated state entries — the
 * terminal-only-side-effects invariant is structural, not conventional.
 */
public interface MacTablePort {

    /** The session authenticated — this MAC gets internet with this policy. */
    void grant(String mac, String policyJson);

    /** The session ended — this MAC goes back behind the walled garden. */
    void revoke(String mac);
}
