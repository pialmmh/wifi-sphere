package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

import java.util.List;

/** Child → supervisor: the captive flow never finished; probe history attached. */
public record CaptiveFailed(int probeCount, List<String> probeLabels) implements StatemachineEvent {}
