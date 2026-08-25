package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.routesphere.wifimachine.v2.event.WifiEvents;
import com.telcobright.routesphere.wifimachine.v2.policy.SessionPolicy;
import com.telcobright.statewalk.v2.machine.Machine;
import com.telcobright.statewalk.v2.state.StateMap;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orthogonal child: one device's sign-in attempt (mirrors CallSignaling).
 *
 * <pre>
 *   PROBING ──page──► PORTAL ──otp──► OTP_PENDING ──verified──► PORTAL
 *      │                │ ──pay──► PAYMENT_PENDING ──settled(stay)──┐
 *      │                │                                          │
 *      └── any state: GrantApproved / GrantObserved ──► GRANTED ◄──┘
 *      └── window expired ──► FAILED (probe history = the analytics record)
 * </pre>
 *
 * The child window is strictly below the supervisor's CAPTIVE window
 * (attribution rule: the child always reports first).
 */
public final class CaptiveFlow extends Machine<CaptiveFlowContext> {

    private final AtomicReference<SessionPolicy> policyRef;

    public CaptiveFlow(AtomicReference<SessionPolicy> policyRef) {
        this.policyRef = policyRef;
    }

    @Override
    protected StateMap defineStates() {
        SessionPolicy p = policyRef.get();
        int window = Math.max(1, p.timeoutCaptiveSec() - 1);
        return StateMap.builder()
            .initialState("PROBING")

            .state("PROBING")
                .interim()
                .timeout(window, TimeUnit.SECONDS, "FAILED")
                .stay(WifiEvents.CaptiveProbe.class, (self, e) ->
                    ((CaptiveFlow) self).recordProbe((WifiEvents.CaptiveProbe) e))
                .on(WifiEvents.PortalOpened.class, "PORTAL")
                .on(WifiEvents.GrantApproved.class, "GRANTED", (self, e) -> {
                    ((CaptiveFlow) self).copyApproved((WifiEvents.GrantApproved) e);
                    return true;
                })
                .on(WifiEvents.GrantObserved.class, "GRANTED", (self, e) -> {
                    ((CaptiveFlow) self).markObserved();
                    return true;
                })

            .state("PORTAL")
                .interim()
                .timeout(window, TimeUnit.SECONDS, "FAILED")
                .stay(WifiEvents.CaptiveProbe.class, (self, e) ->
                    ((CaptiveFlow) self).recordProbe((WifiEvents.CaptiveProbe) e))
                .on(WifiEvents.OtpSent.class, "OTP_PENDING", (self, e) -> {
                    ((CaptiveFlow) self).getContext().msisdn = ((WifiEvents.OtpSent) e).msisdn();
                    return true;
                })
                .on(WifiEvents.PaymentInitiated.class, "PAYMENT_PENDING", (self, e) -> {
                    ((CaptiveFlow) self).getContext().purchaseId = ((WifiEvents.PaymentInitiated) e).purchaseId();
                    return true;
                })
                .on(WifiEvents.GrantApproved.class, "GRANTED", (self, e) -> {
                    ((CaptiveFlow) self).copyApproved((WifiEvents.GrantApproved) e);
                    return true;
                })
                .on(WifiEvents.GrantObserved.class, "GRANTED", (self, e) -> {
                    ((CaptiveFlow) self).markObserved();
                    return true;
                })

            .state("OTP_PENDING")
                .interim()
                .timeout(p.timeoutOtpSec(), TimeUnit.SECONDS, "FAILED")
                .on(WifiEvents.OtpVerified.class, "PORTAL", (self, e) -> {
                    ((CaptiveFlow) self).getContext().msisdn = ((WifiEvents.OtpVerified) e).msisdn();
                    return true;
                })
                .on(WifiEvents.GrantApproved.class, "GRANTED", (self, e) -> {
                    ((CaptiveFlow) self).copyApproved((WifiEvents.GrantApproved) e);
                    return true;
                })
                .on(WifiEvents.GrantObserved.class, "GRANTED", (self, e) -> {
                    ((CaptiveFlow) self).markObserved();
                    return true;
                })

            .state("PAYMENT_PENDING")
                .interim()
                .timeout(p.timeoutPaymentSec(), TimeUnit.SECONDS, "FAILED")
                .stay(WifiEvents.PaymentSettled.class, (self, e) ->
                    ((CaptiveFlow) self).getContext().purchaseId = ((WifiEvents.PaymentSettled) e).purchaseId())
                .on(WifiEvents.GrantApproved.class, "GRANTED", (self, e) -> {
                    ((CaptiveFlow) self).copyApproved((WifiEvents.GrantApproved) e);
                    return true;
                })
                .on(WifiEvents.GrantObserved.class, "GRANTED", (self, e) -> {
                    ((CaptiveFlow) self).markObserved();
                    return true;
                })

            .state("GRANTED")
                .finalState()
                .timeout(1, TimeUnit.SECONDS, "GRANTED")
                .onEntry(self -> ((CaptiveFlow) self).reportDone())

            .state("FAILED")
                .finalState()
                .timeout(1, TimeUnit.SECONDS, "FAILED")
                .onEntry(self -> ((CaptiveFlow) self).reportFailed())

            .build();
    }

    @Override
    protected CaptiveFlowContext createContext() { return new CaptiveFlowContext(); }

    // ─────────────────────────────────────────────────────────────────
    // Business steps
    // ─────────────────────────────────────────────────────────────────

    private void recordProbe(WifiEvents.CaptiveProbe probe) {
        CaptiveFlowContext ctx = getContext();
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

    private void copyApproved(WifiEvents.GrantApproved g) {
        CaptiveFlowContext ctx = getContext();
        ctx.method = "portal";
        ctx.msisdn = g.msisdn();
        ctx.minutes = g.minutes();
        ctx.volumeBytes = g.volumeBytes();
        if (g.purchaseId() != null) ctx.purchaseId = g.purchaseId();
    }

    private void markObserved() {
        CaptiveFlowContext ctx = getContext();
        ctx.method = "observed";
    }

    private void reportDone() {
        CaptiveFlowContext ctx = getContext();
        publishEvent(new CaptiveDone(ctx.method, ctx.msisdn, ctx.minutes, ctx.volumeBytes,
            ctx.purchaseId, ctx.probeCount, List.copyOf(ctx.probeLabels)));
    }

    private void reportFailed() {
        CaptiveFlowContext ctx = getContext();
        publishEvent(new CaptiveFailed(ctx.probeCount, List.copyOf(ctx.probeLabels)));
    }
}
