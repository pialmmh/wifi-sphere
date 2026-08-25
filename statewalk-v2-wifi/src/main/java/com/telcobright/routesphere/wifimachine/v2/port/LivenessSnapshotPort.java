package com.telcobright.routesphere.wifimachine.v2.port;

import java.util.Map;

/**
 * Reads the present-tense activity hash the agent overwrites every tick.
 * Hash semantics: current state only — backlog is impossible by
 * construction. A stale snapshot yields NO ticks and therefore no kills.
 */
public interface LivenessSnapshotPort {

    record LivenessSnapshot(long snapshotMs, Map<String, Long> lastActiveByMac) {}

    LivenessSnapshot readAll();
}
