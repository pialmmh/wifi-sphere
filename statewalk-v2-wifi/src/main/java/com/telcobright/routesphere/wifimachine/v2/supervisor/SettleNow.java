package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

/** Supervisor → UsageTracker: the session is ending; settle and report totals. */
public record SettleNow() implements StatemachineEvent {}
