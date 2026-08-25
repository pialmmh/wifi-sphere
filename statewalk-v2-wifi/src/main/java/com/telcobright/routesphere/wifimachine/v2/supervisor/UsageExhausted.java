package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

/** UsageTracker → supervisor: a budget ran out ({@code cause} = minutes | volume). */
public record UsageExhausted(String cause) implements StatemachineEvent {}
