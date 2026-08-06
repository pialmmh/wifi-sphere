package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.routesphere.wifimachine.v2.event.WifiEvents;
import com.telcobright.routesphere.wifimachine.v2.port.MacTablePort;
import com.telcobright.routesphere.wifimachine.v2.port.SessionRecordPort;
import com.telcobright.routesphere.wifimachine.v2.port.WifiSessionRecord;
import com.telcobright.statewalk.v2.flat.InternalEventResolver;
import com.telcobright.statewalk.v2.flat.Supervisor;
import com.telcobright.statewalk.v2.state.StateMap;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Business state machine for one WiFi data session (mirrors
 * {@code CallSupervisor}/{@code SmsSupervisor} — a data session gets the same
 * treatment a call gets). One instance per session id
 * ({@code <macNoColons>-<firstSeenEpochSec>} — never the bare MAC: terminated
 * ids are deduped for 60s and WiFi devices reconnect fast).
 *
 * <pre>
 *   INIT ──────────→ SIGNALING ──────→ AUTHENTICATED ──→ ENDED
 *     │ (1st probe)      │ (grant)          │ (revoke/gone/24h)
 *     │ 120s silent      │ child window     │
 *     └──→ REJECTED ←────┘ expired          │
 *            ↑ (device left mid-flow) ──────┘
 * </pre>
 *
 * <p>Two structural invariants:
 * <ul>
 *   <li>the session record (the data CDR) is written ONLY from ENDED/REJECTED
 *       entry — a session cannot finish without leaving exactly one record;</li>
 *   <li>the MAC-table port is called ONLY from AUTHENTICATED (grant) and
 *       ENDED (revoke) entries — in shadow mode those are recorded, not
 *       enforced, while the legacy authorizer still grants.</li>
 * </ul>
 */
public final class WifiSessionSupervisor extends Supervisor<WifiSupervisorContext> {

    private final MacTablePort macTable;
    private final SessionRecordPort records;
    private final WifiTimeouts timeouts;

    public WifiSessionSupervisor(MacTablePort macTable,
                                 SessionRecordPort records,
                                 WifiTimeouts timeouts) {
        this.macTable = macTable;
        this.records = records;
        this.timeouts = timeouts;
    }

    // ─────────────────────────────────────────────────────────────────
    // Routing
    // ─────────────────────────────────────────────────────────────────

    @Override
    protected void defineRoutes(InternalEventResolver r) {
        // Child → supervisor reports
        r.selfHandle(SignalingStarted.class);
        r.selfHandle(SignalingDone.class);
        r.selfHandle(SignalingFailed.class);

        // Session-lifecycle wire events — the supervisor owns these
        r.selfHandle(WifiEvents.GrantRevoked.class);
        r.selfHandle(WifiEvents.DeviceGone.class);

        // Captive-flow wire events — the signaling child owns these
        r.forwardTo("WifiSignaling", WifiEvents.CaptiveProbe.class);
        r.forwardTo("WifiSignaling", WifiEvents.GrantObserved.class);
    }

    // ─────────────────────────────────────────────────────────────────
    // State graph
    // ─────────────────────────────────────────────────────────────────

    @Override
    protected StateMap defineStates() {
        return StateMap.builder()
            .initialState("INIT")

            // ── INIT — device seen, child listening, nothing heard yet ──
            .state("INIT")
                .interim()
                .timeout(timeouts.initSec(), TimeUnit.SECONDS, "REJECTED")
                .onEntry(self -> ((WifiSessionSupervisor) self).spawnSignalingChild())
                .on(SignalingStarted.class, "SIGNALING", (self, e) -> {
                    ((WifiSessionSupervisor) self).recordSignalingStart((SignalingStarted) e);
                    return true;
                })
                // a grant can land before any probe (e.g. admin grant) — still a valid session
                .on(SignalingDone.class, "AUTHENTICATED", (self, e) -> {
                    ((WifiSessionSupervisor) self).recordProbeSummary(((SignalingDone) e).probeCount(),
                        ((SignalingDone) e).probeLabels());
                    return true;
                })
                .on(WifiEvents.DeviceGone.class, "REJECTED", (self, e) -> {
                    ((WifiSessionSupervisor) self).getContext().endReason = "left";
                    return true;
                })

            // ── SIGNALING — the device is actively trying the captive flow ──
            .state("SIGNALING")
                .interim()
                .timeout(timeouts.signalingSec(), TimeUnit.SECONDS, "REJECTED")
                .on(SignalingDone.class, "AUTHENTICATED", (self, e) -> {
                    ((WifiSessionSupervisor) self).recordProbeSummary(((SignalingDone) e).probeCount(),
                        ((SignalingDone) e).probeLabels());
                    return true;
                })
                .on(SignalingFailed.class, "REJECTED", (self, e) -> {
                    WifiSessionSupervisor sup = (WifiSessionSupervisor) self;
                    SignalingFailed f = (SignalingFailed) e;
                    sup.recordProbeSummary(f.probeCount(), f.probeLabels());
                    sup.getContext().endReason = "noAuth";
                    return true;
                })
                .on(WifiEvents.DeviceGone.class, "REJECTED", (self, e) -> {
                    ((WifiSessionSupervisor) self).getContext().endReason = "left";
                    return true;
                })

            // ── AUTHENTICATED — the device has internet ──
            .state("AUTHENTICATED")
                .interim()
                .timeout(timeouts.sessionMaxSec(), TimeUnit.SECONDS, "ENDED")
                .onEntry(self -> ((WifiSessionSupervisor) self).grantInternet())
                .on(WifiEvents.GrantRevoked.class, "ENDED", (self, e) -> {
                    ((WifiSessionSupervisor) self).getContext().endReason = "granted-session-over";
                    return true;
                })
                .on(WifiEvents.DeviceGone.class, "ENDED", (self, e) -> {
                    ((WifiSessionSupervisor) self).getContext().endReason = "granted-session-over";
                    return true;
                })

            // ── ENDED — a granted session finished; revoke + one record ──
            .state("ENDED")
                .finalState()
                .timeout(1, TimeUnit.SECONDS, "ENDED")
                .onEntry(self -> ((WifiSessionSupervisor) self).closeGrantedSession())

            // ── REJECTED — never got internet; one record with probe history ──
            .state("REJECTED")
                .finalState()
                .timeout(1, TimeUnit.SECONDS, "REJECTED")
                .onEntry(self -> ((WifiSessionSupervisor) self).closeRejectedSession())

            .build();
    }

    @Override
    protected WifiSupervisorContext createContext() { return new WifiSupervisorContext(); }

    // ─────────────────────────────────────────────────────────────────
    // Business steps
    // ─────────────────────────────────────────────────────────────────

    private void spawnSignalingChild() {
        WifiSupervisorContext ctx = getContext();
        WifiSignalingContext sigCtx = new WifiSignalingContext();
        sigCtx.sessionId = ctx.sessionId;
        sigCtx.mac = ctx.mac;
        resolver.spawnChild("WifiSignaling", sigCtx);
    }

    private void recordSignalingStart(SignalingStarted started) {
        WifiSupervisorContext ctx = getContext();
        if (started.ip() != null) ctx.ip = started.ip();
    }

    private void recordProbeSummary(int probeCount, List<String> probeLabels) {
        WifiSupervisorContext ctx = getContext();
        ctx.probeCount = probeCount;
        ctx.probeLabels = probeLabels;
    }

    private void grantInternet() {
        WifiSupervisorContext ctx = getContext();
        ctx.authedAtMs = System.currentTimeMillis();
        if (macTable != null) {
            macTable.grant(ctx.mac, "{\"disp\":\"release\",\"name\":\"" + ctx.sessionId + "\"}");
        }
    }

    private void closeGrantedSession() {
        WifiSupervisorContext ctx = getContext();
        ctx.endedAtMs = System.currentTimeMillis();
        if (ctx.endReason == null) ctx.endReason = "maxSession"; // only the 24h cap leaves it unset
        if (macTable != null) macTable.revoke(ctx.mac);
        writeRecord("ENDED");
    }

    private void closeRejectedSession() {
        WifiSupervisorContext ctx = getContext();
        ctx.endedAtMs = System.currentTimeMillis();
        if (ctx.endReason == null) ctx.endReason = ctx.probeCount > 0 ? "noAuth" : "silent";
        writeRecord("REJECTED");
    }

    private void writeRecord(String outcome) {
        WifiSupervisorContext ctx = getContext();
        if (records == null) return;
        records.record(new WifiSessionRecord(
            ctx.sessionId, ctx.mac, outcome, ctx.endReason,
            ctx.firstSeenMs, ctx.authedAtMs, ctx.endedAtMs,
            ctx.probeCount, List.copyOf(ctx.probeLabels)));
    }
}
