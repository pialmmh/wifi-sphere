package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

import java.util.List;

/** Child → supervisor: the device authenticated (a grant was observed). */
public record SignalingDone(int probeCount, List<String> probeLabels) implements StatemachineEvent {}
