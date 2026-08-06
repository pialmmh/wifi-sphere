package com.telcobright.routesphere.wifimachine.v2.supervisor;

/**
 * The session clocks, injectable so tests can run in seconds. Child probing
 * strictly dominates below the supervisor's SIGNALING window (SMS rule:
 * the child always reports first, so timeout attribution is exact).
 */
public record WifiTimeouts(
    int initSec,          // silent device: seen but never probed
    int signalingSec,     // probed but never authenticated
    int probingChildSec,  // child window, MUST be < signalingSec
    int sessionMaxSec) {  // hard cap on an authenticated session

    public static WifiTimeouts production() {
        return new WifiTimeouts(120, 300, 280, 24 * 3600);
    }

    public WifiTimeouts {
        if (probingChildSec >= signalingSec)
            throw new IllegalArgumentException(
                "probingChildSec must be strictly below signalingSec (attribution rule)");
    }
}
