package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.routesphere.wifimachine.v2.event.WifiEvents;
import com.telcobright.routesphere.wifimachine.v2.policy.SessionPolicy;
import com.telcobright.routesphere.wifimachine.v2.port.GatePort;
import com.telcobright.routesphere.wifimachine.v2.port.MembershipChange;
import com.telcobright.routesphere.wifimachine.v2.port.MembershipPort;
import com.telcobright.routesphere.wifimachine.v2.port.RadiusAccountingPort;
import com.telcobright.routesphere.wifimachine.v2.port.SdrPort;
import com.telcobright.routesphere.wifimachine.v2.port.WifiSdrRecord;
import com.telcobright.statewalk.v2.flat.InternalEventResolver;
import com.telcobright.statewalk.v2.flat.Supervisor;
import com.telcobright.statewalk.v2.state.StateMap;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Business state machine for one WiFi device session — the call-grade
 * treatment (mirrors CallSupervisor). One instance per session id
 * ({@code <macNoColons>-<epochSec>}, never the bare MAC).
 *
 * <pre>
 *   INIT ──1st probe──► CAPTIVE ──CaptiveDone──► ESTABLISHED ──any end cause──► EXPIRING ──► TERMINATED
 *     │ 10m silent         │ window expired          │ cap / minutes / volume /     │ 30s guard
 *     └─────────────────────┴──► REJECTED             │ idle / CoA / kick / gone
 * </pre>
 *
 * Structural invariants: exactly ONE SDR per session leg, written only from
 * final-state entry; the gate is touched at exactly two sites (ESTABLISHED
 * entry, EXPIRING entry); accounting lives in the UsageTracker child, the
 * captive flow in the CaptiveFlow child (orthogonal, call parity).
 */
public final class WifiSessionSupervisor extends Supervisor<WifiSupervisorContext> {

    public static final String CAPTIVE_CHILD = "CaptiveFlow";
    public static final String USAGE_CHILD = "UsageTracker";

    private final GatePort gate;
    private final RadiusAccountingPort radius;
    private final SdrPort sdr;
    private final MembershipPort membership;
    private final AtomicReference<SessionPolicy> policyRef;

    public WifiSessionSupervisor(GatePort gate,
                                 RadiusAccountingPort radius,
                                 SdrPort sdr,
                                 MembershipPort membership,
                                 AtomicReference<SessionPolicy> policyRef) {
        this.gate = gate;
        this.radius = radius;
        this.sdr = sdr;
        this.membership = membership;
        this.policyRef = policyRef;
    }

    // ─────────────────────────────────────────────────────────────────
    // Routing (dependency-free: runs before instance fields are set)
    // ─────────────────────────────────────────────────────────────────

    @Override
    protected void defineRoutes(InternalEventResolver r) {
        // child reports
        r.selfHandle(SignalingStarted.class);
        r.selfHandle(CaptiveDone.class);
        r.selfHandle(CaptiveFailed.class);
        r.selfHandle(UsageExhausted.class);
        r.selfHandle(SessionIdle.class);
        r.selfHandle(UsageSettled.class);

        // session-lifecycle wire events
        r.selfHandle(WifiEvents.GrantRevoked.class);
        r.selfHandle(WifiEvents.DeviceGone.class);
        r.selfHandle(WifiEvents.CoaDisconnect.class);
        r.selfHandle(WifiEvents.AdminKick.class);

        // captive-flow events → the CaptiveFlow child
        r.forwardTo(CAPTIVE_CHILD, WifiEvents.CaptiveProbe.class);
        r.forwardTo(CAPTIVE_CHILD, WifiEvents.PortalOpened.class);
        r.forwardTo(CAPTIVE_CHILD, WifiEvents.OtpSent.class);
        r.forwardTo(CAPTIVE_CHILD, WifiEvents.OtpVerified.class);
        r.forwardTo(CAPTIVE_CHILD, WifiEvents.PaymentInitiated.class);
        r.forwardTo(CAPTIVE_CHILD, WifiEvents.PaymentSettled.class);
        r.forwardTo(CAPTIVE_CHILD, WifiEvents.GrantApproved.class);
        r.forwardTo(CAPTIVE_CHILD, WifiEvents.GrantObserved.class);

        // accounting events → the UsageTracker child
        r.forwardTo(USAGE_CHILD, WifiEvents.CountersDelta.class);
        r.forwardTo(USAGE_CHILD, WifiEvents.LivenessTick.class);
        r.forwardTo(USAGE_CHILD, MeterStart.class);
        r.forwardTo(USAGE_CHILD, SettleNow.class);
    }

    // ─────────────────────────────────────────────────────────────────
    // State graph
    // ─────────────────────────────────────────────────────────────────

    @Override
    protected StateMap defineStates() {
        SessionPolicy p = policyRef.get();
        return StateMap.builder()
            .initialState("INIT")

            .state("INIT")
                .interim()
                .timeout(p.timeoutInitSec(), TimeUnit.SECONDS, "REJECTED")
                .onEntry(self -> ((WifiSessionSupervisor) self).birth())
                .on(SignalingStarted.class, "CAPTIVE", (self, e) -> {
                    ((WifiSessionSupervisor) self).recordStart((SignalingStarted) e);
                    return true;
                })
                .on(CaptiveDone.class, "ESTABLISHED", (self, e) -> {
                    ((WifiSessionSupervisor) self).copyGrant((CaptiveDone) e);
                    return true;
                })
                .on(WifiEvents.DeviceGone.class, "REJECTED", (self, e) -> {
                    ((WifiSessionSupervisor) self).getContext().endReason = "left";
                    return true;
                })

            .state("CAPTIVE")
                .interim()
                .timeout(p.timeoutCaptiveSec(), TimeUnit.SECONDS, "REJECTED")
                .on(CaptiveDone.class, "ESTABLISHED", (self, e) -> {
                    ((WifiSessionSupervisor) self).copyGrant((CaptiveDone) e);
                    return true;
                })
                .on(CaptiveFailed.class, "REJECTED", (self, e) -> {
                    WifiSessionSupervisor sup = (WifiSessionSupervisor) self;
                    CaptiveFailed f = (CaptiveFailed) e;
                    sup.recordProbeSummary(f.probeCount(), f.probeLabels());
                    sup.getContext().endReason = "noAuth";
                    return true;
                })
                .on(WifiEvents.DeviceGone.class, "REJECTED", (self, e) -> {
                    ((WifiSessionSupervisor) self).getContext().endReason = "left";
                    return true;
                })

            .state("ESTABLISHED")
                .interim()
                // the REAL cap is UsageTracker's wall-clock check (UsageExhausted "cap");
                // this clock is the dead-man backstop if every feed dies (target must be final)
                .timeout(p.establishedMaxSec() + p.expiringGraceSec() + 60L, TimeUnit.SECONDS, "TERMINATED")
                .onEntry(self -> ((WifiSessionSupervisor) self).establish())
                .on(UsageExhausted.class, "EXPIRING", (self, e) -> {
                    ((WifiSessionSupervisor) self).getContext().endReason = ((UsageExhausted) e).cause();
                    return true;
                })
                .on(SessionIdle.class, "EXPIRING", (self, e) -> {
                    ((WifiSessionSupervisor) self).getContext().endReason = "idle";
                    return true;
                })
                .on(WifiEvents.GrantRevoked.class, "EXPIRING", (self, e) -> {
                    ((WifiSessionSupervisor) self).getContext().endReason = "external";
                    return true;
                })
                .on(WifiEvents.CoaDisconnect.class, "EXPIRING", (self, e) -> {
                    ((WifiSessionSupervisor) self).getContext().endReason = "coa";
                    return true;
                })
                .on(WifiEvents.AdminKick.class, "EXPIRING", (self, e) -> {
                    ((WifiSessionSupervisor) self).getContext().endReason = "kick";
                    return true;
                })
                .on(WifiEvents.DeviceGone.class, "EXPIRING", (self, e) -> {
                    ((WifiSessionSupervisor) self).getContext().endReason = "gone";
                    return true;
                })

            .state("EXPIRING")
                .interim()
                .timeout(p.expiringGraceSec(), TimeUnit.SECONDS, "TERMINATED")
                .onEntry(self -> ((WifiSessionSupervisor) self).teardown())
                .on(UsageSettled.class, "TERMINATED", (self, e) -> {
                    ((WifiSessionSupervisor) self).copyTotals((UsageSettled) e);
                    return true;
                })

            .state("TERMINATED")
                .finalState()
                .timeout(1, TimeUnit.SECONDS, "TERMINATED")
                .onEntry(self -> ((WifiSessionSupervisor) self).closeGranted())

            .state("REJECTED")
                .finalState()
                .timeout(1, TimeUnit.SECONDS, "REJECTED")
                .onEntry(self -> ((WifiSessionSupervisor) self).closeRejected())

            .build();
    }

    @Override
    protected WifiSupervisorContext createContext() { return new WifiSupervisorContext(); }

    // ─────────────────────────────────────────────────────────────────
    // Business steps
    // ─────────────────────────────────────────────────────────────────

    private void birth() {
        WifiSupervisorContext ctx = getContext();
        CaptiveFlowContext cfc = new CaptiveFlowContext();
        cfc.sessionId = ctx.sessionId;
        cfc.mac = ctx.mac;
        resolver.spawnChild(CAPTIVE_CHILD, cfc);
        UsageContext uc = new UsageContext();
        uc.sessionId = ctx.sessionId;
        uc.mac = ctx.mac;
        resolver.spawnChild(USAGE_CHILD, uc);
        membership.onMembership(new MembershipChange(
            "SEEN", ctx.sessionId, ctx.mac, ctx.msisdn, ctx.zone, ctx.site, ctx.gwId, "INIT"));
    }

    private void recordStart(SignalingStarted started) {
        if (started.ip() != null) getContext().ip = started.ip();
    }

    private void recordProbeSummary(int probeCount, List<String> probeLabels) {
        WifiSupervisorContext ctx = getContext();
        ctx.probeCount = probeCount;
        ctx.probeLabels = probeLabels;
    }

    private void copyGrant(CaptiveDone done) {
        WifiSupervisorContext ctx = getContext();
        ctx.method = done.method();
        if (done.msisdn() != null) ctx.msisdn = done.msisdn();
        ctx.grantedMinutes = done.minutes();
        ctx.volumeBudgetBytes = done.volumeBytes();
        ctx.purchaseId = done.purchaseId();
        recordProbeSummary(done.probeCount(), done.probeLabels());
    }

    private void establish() {
        WifiSupervisorContext ctx = getContext();
        ctx.establishedAtMs = System.currentTimeMillis();
        gate.release(ctx.mac, ctx.sessionId, ctx.grantedMinutes);
        radius.acctStart(ctx);
        membership.onMembership(new MembershipChange(
            "ESTABLISHED", ctx.sessionId, ctx.mac, ctx.msisdn, ctx.zone, ctx.site, ctx.gwId, "ESTABLISHED"));
        publishEvent(new MeterStart(ctx.establishedAtMs, ctx.grantedMinutes, ctx.volumeBudgetBytes));
    }

    private void teardown() {
        WifiSupervisorContext ctx = getContext();
        if (ctx.endReason == null) ctx.endReason = "cap";
        ctx.tornDown = true;
        gate.garden(ctx.mac);
        radius.acctStop(ctx, ctx.endReason);
        publishEvent(new SettleNow());
    }

    private void copyTotals(UsageSettled settled) {
        WifiSupervisorContext ctx = getContext();
        ctx.bytesUp = settled.bytesUp();
        ctx.bytesDn = settled.bytesDn();
        ctx.activeSeconds = settled.activeSeconds();
    }

    private void closeGranted() {
        WifiSupervisorContext ctx = getContext();
        ctx.endedAtMs = System.currentTimeMillis();
        if (!ctx.tornDown) { // dead-man backstop path: EXPIRING never ran
            ctx.endReason = ctx.endReason == null ? "cap-backstop" : ctx.endReason;
            gate.garden(ctx.mac);
            radius.acctStop(ctx, ctx.endReason);
        }
        boolean partial = "cap".equals(ctx.endReason) && policyRef.get().sdrPartialAtCap();
        writeSdr("TERMINATED", partial);
    }

    private void closeRejected() {
        WifiSupervisorContext ctx = getContext();
        ctx.endedAtMs = System.currentTimeMillis();
        if (ctx.endReason == null) ctx.endReason = ctx.probeCount > 0 ? "noAuth" : "silent";
        writeSdr("REJECTED", false);
    }

    private void writeSdr(String outcome, boolean partial) {
        WifiSupervisorContext ctx = getContext();
        sdr.write(new WifiSdrRecord(
            ctx.sessionId, ctx.operator, ctx.gwId, ctx.mac, ctx.msisdn, ctx.ip, ctx.site, ctx.zone, ctx.vlan,
            outcome, ctx.endReason, ctx.firstSeenMs, ctx.establishedAtMs, ctx.endedAtMs,
            ctx.activeSeconds, ctx.bytesUp, ctx.bytesDn, ctx.purchaseId, ctx.grantedMinutes,
            partial, ctx.probeCount, List.copyOf(ctx.probeLabels)));
        membership.onMembership(new MembershipChange(
            "CLOSED", ctx.sessionId, ctx.mac, ctx.msisdn, ctx.zone, ctx.site, ctx.gwId, outcome));
    }
}
