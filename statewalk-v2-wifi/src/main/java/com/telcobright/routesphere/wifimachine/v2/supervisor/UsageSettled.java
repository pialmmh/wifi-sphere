package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

/** UsageTracker → supervisor: final totals for the SDR. */
public record UsageSettled(long bytesUp, long bytesDn, long activeSeconds) implements StatemachineEvent {}
