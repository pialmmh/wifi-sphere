package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

import java.util.List;

/**
 * Child → supervisor: the device probed the portal but never authenticated
 * within the probing window — THE record that makes broken-captive devices
 * visible (brand/model analytics).
 */
public record SignalingFailed(int probeCount, List<String> probeLabels) implements StatemachineEvent {}
