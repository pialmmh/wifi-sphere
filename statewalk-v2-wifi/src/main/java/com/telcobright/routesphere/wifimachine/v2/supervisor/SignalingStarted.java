package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

/** Child → supervisor: the device's FIRST captive probe arrived — signaling has begun. */
public record SignalingStarted(String probeLabel, String ip) implements StatemachineEvent {}
