package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.routesphere.wifimachine.v2.event.WifiEvents;
import com.telcobright.statewalk.v2.machine.Machine;
import com.telcobright.statewalk.v2.state.StateMap;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Protocol state machine for one device's captive-portal attempt. Child of
 * {@code WifiSessionSupervisor}; spawned at the supervisor's INIT.entry so it
 * is already listening when the first probe arrives.
 *
 * <p>State graph:
 * <pre>
 *   PROBING → DONE    (a grant was observed — the device authenticated)
 *           → FAILED  (probing window expired — the captive flow never finished)
 * </pre>
 *
 * <p>Probes are {@code stay} handlers: the device hammers the portal every few
 * seconds and each distinct user-agent family is logged once, in order. The
 * FIRST probe additionally reports {@link SignalingStarted} so the supervisor
 * can leave INIT.
 *
 * <p>The PROBING timeout is strictly below the supervisor's SIGNALING window
 * (see {@link WifiTimeouts}), so on a never-authenticated device this child
 * always reports {@link SignalingFailed} — with the full probe history —
 * before the supervisor's own clock can fire. That report IS the
 * which-devices-fail analytics record.
 */
public final class WifiSignaling extends Machine<WifiSignalingContext> {

    private final WifiTimeouts timeouts;

    public WifiSignaling(WifiTimeouts timeouts) {
        this.timeouts = timeouts;
    }

    @Override
    protected StateMap defineStates() {
        return StateMap.builder()
            .initialState("PROBING")

            .state("PROBING")
                .interim()
                .timeout(timeouts.probingChildSec(), TimeUnit.SECONDS, "FAILED")
                .on(WifiEvents.GrantObserved.class, "DONE")
                .stay(WifiEvents.CaptiveProbe.class, (self, e) ->
                    ((WifiSignaling) self).recordProbe((WifiEvents.CaptiveProbe) e))

            .state("DONE")
                .finalState()
                .timeout(1, TimeUnit.SECONDS, "DONE")
                .onEntry(self -> ((WifiSignaling) self).reportDone())

            .state("FAILED")
                .finalState()
                .timeout(1, TimeUnit.SECONDS, "FAILED")
                .onEntry(self -> ((WifiSignaling) self).reportFailed())

            .build();
    }

    @Override
    protected WifiSignalingContext createContext() { return new WifiSignalingContext(); }

    // ─────────────────────────────────────────────────────────────────
    // Business steps
    // ─────────────────────────────────────────────────────────────────

    private void recordProbe(WifiEvents.CaptiveProbe probe) {
        WifiSignalingContext ctx = getContext();
        long now = System.currentTimeMillis();
        if (ctx.probeCount == 0) ctx.firstProbeMs = now;
        ctx.lastProbeMs = now;
        ctx.probeCount++;
        if (probe.probeLabel() != null && !probe.probeLabel().isBlank()
            && !ctx.probeLabels.contains(probe.probeLabel())) {
            ctx.probeLabels.add(probe.probeLabel());
        }
        if (!ctx.startReported) {
            ctx.startReported = true;
            publishEvent(new SignalingStarted(probe.probeLabel(), probe.ip()));
        }
    }

    private void reportDone() {
        WifiSignalingContext ctx = getContext();
        publishEvent(new SignalingDone(ctx.probeCount, List.copyOf(ctx.probeLabels)));
    }

    private void reportFailed() {
        WifiSignalingContext ctx = getContext();
        publishEvent(new SignalingFailed(ctx.probeCount, List.copyOf(ctx.probeLabels)));
    }
}
