package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

import java.util.List;

/**
 * Child → supervisor: the captive flow finished with a grant. Carries the
 * grant facts the supervisor needs to establish the session ({@code method}
 * = portal | observed; minutes/volumeBytes 0 = unbounded by that dimension).
 */
public record CaptiveDone(String method, String msisdn, int minutes, long volumeBytes,
                          String purchaseId, int probeCount, List<String> probeLabels)
    implements StatemachineEvent {}
