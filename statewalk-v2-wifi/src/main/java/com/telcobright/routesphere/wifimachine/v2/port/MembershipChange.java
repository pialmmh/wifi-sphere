package com.telcobright.routesphere.wifimachine.v2.port;

/**
 * One membership fact from a session machine to the aggregate views.
 * {@code kind} = SEEN (birth, msisdn may be null = anonymous) ·
 * ESTABLISHED (internet granted, msisdn set when known) · CLOSED (final).
 */
public record MembershipChange(
    String kind,
    String sessionId,
    String mac,
    String msisdn,
    String zone,
    String state) {}
