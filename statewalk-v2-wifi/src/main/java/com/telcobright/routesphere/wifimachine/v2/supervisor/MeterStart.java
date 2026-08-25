package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

/** Supervisor → UsageTracker: the session established; start the meters. */
public record MeterStart(long establishedAtMs, int minutes, long volumeBytes) implements StatemachineEvent {}
