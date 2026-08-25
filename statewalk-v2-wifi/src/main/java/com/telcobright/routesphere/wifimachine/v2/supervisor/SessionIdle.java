package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

/** UsageTracker → supervisor: fresh liveness shows zero traffic past the idle window. */
public record SessionIdle(long idleForMs) implements StatemachineEvent {}
