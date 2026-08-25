package com.telcobright.routesphere.wifimachine.v2.supervisor;

import com.telcobright.routesphere.wifimachine.v2.event.WifiEvents;
import com.telcobright.routesphere.wifimachine.v2.policy.SessionPolicy;
import com.telcobright.statewalk.v2.machine.Machine;
import com.telcobright.statewalk.v2.state.StateMap;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orthogonal child: the session's accountant (mirrors BalanceTracker).
 * METERING → SETTLING → CLOSED.
 *
 * <p>Two truths, two rules: the LEDGER (CountersDelta — money) is always
 * processed, backlog included; the PRESENT (LivenessTick from the activity
 * hash) alone decides idleness, and only when the snapshot is fresh. A
 * stale or missing snapshot NEVER ends a session.
 */
public final class UsageTracker extends Machine<UsageContext> {

    private final AtomicReference<SessionPolicy> policyRef;

    public UsageTracker(AtomicReference<SessionPolicy> policyRef) {
        this.policyRef = policyRef;
    }

    @Override
    protected StateMap defineStates() {
        return StateMap.builder()
            .initialState("METERING")

            .state("METERING")
                .interim()
                .timeout(7, TimeUnit.DAYS, "CLOSED") // safety net; the supervisor's clocks bound real life
                .stay(MeterStart.class, (self, e) -> ((UsageTracker) self).startMeters((MeterStart) e))
                .stay(WifiEvents.CountersDelta.class, (self, e) ->
                    ((UsageTracker) self).onCounters((WifiEvents.CountersDelta) e))
                .stay(WifiEvents.LivenessTick.class, (self, e) ->
                    ((UsageTracker) self).onLiveness((WifiEvents.LivenessTick) e))
                .on(SettleNow.class, "SETTLING")

            .state("SETTLING")
                .interim()
                .timeout(1, TimeUnit.SECONDS, "CLOSED")
                .onEntry(self -> ((UsageTracker) self).settle())

            .state("CLOSED")
                .finalState()
                .timeout(1, TimeUnit.SECONDS, "CLOSED")

            .build();
    }

    @Override
    protected UsageContext createContext() { return new UsageContext(); }

    // ─────────────────────────────────────────────────────────────────
    // Business steps
    // ─────────────────────────────────────────────────────────────────

    private void startMeters(MeterStart start) {
        UsageContext ctx = getContext();
        ctx.metering = true;
        ctx.establishedAtMs = start.establishedAtMs();
        ctx.minutesBudget = start.minutes();
        ctx.volumeBudgetBytes = start.volumeBytes();
        ctx.lastTrafficMs = start.establishedAtMs();
    }

    /** Ledger path — money. Processed identically live or from backlog. */
    private void onCounters(WifiEvents.CountersDelta d) {
        UsageContext ctx = getContext();
        ctx.bytesUp += Math.max(0, d.bytesUp());
        ctx.bytesDn += Math.max(0, d.bytesDn());
        if (d.bytesUp() > 0 || d.bytesDn() > 0) {
            ctx.lastTrafficMs = System.currentTimeMillis();
            ctx.idleReported = false;
        }
        checkBudgets();
    }

    /** Present-tense path — the ONLY source of the idle decision. */
    private void onLiveness(WifiEvents.LivenessTick t) {
        UsageContext ctx = getContext();
        if (!ctx.metering) return;
        checkBudgets(); // wall-clock caps are ledger-class facts — no freshness needed
        SessionPolicy p = policyRef.get();
        long now = System.currentTimeMillis();
        boolean fresh = now - t.snapshotMs() <= p.livenessStaleSec() * 1000L;
        if (!fresh) return; // stale snapshot: no idle decision, never a kill
        long idleMs = t.snapshotMs() - t.lastActiveMs();
        if (p.establishedIdleSec() > 0 && idleMs >= p.establishedIdleSec() * 1000L && !ctx.idleReported) {
            ctx.idleReported = true;
            publishEvent(new SessionIdle(idleMs));
        }
    }

    private void checkBudgets() {
        UsageContext ctx = getContext();
        if (!ctx.metering || ctx.exhaustReported) return;
        if (ctx.volumeBudgetBytes > 0 && ctx.bytesUp + ctx.bytesDn >= ctx.volumeBudgetBytes) {
            ctx.exhaustReported = true;
            publishEvent(new UsageExhausted("volume"));
            return;
        }
        long elapsed = System.currentTimeMillis() - ctx.establishedAtMs;
        if (ctx.minutesBudget > 0 && elapsed >= ctx.minutesBudget * 60_000L) {
            ctx.exhaustReported = true;
            publishEvent(new UsageExhausted("minutes"));
            return;
        }
        if (elapsed >= policyRef.get().establishedMaxSec() * 1000L) {
            ctx.exhaustReported = true;
            publishEvent(new UsageExhausted("cap")); // the 3h movie cap — event-driven like every cause
        }
    }

    private void settle() {
        UsageContext ctx = getContext();
        long activeSec = ctx.establishedAtMs > 0
            ? Math.max(0, (System.currentTimeMillis() - ctx.establishedAtMs) / 1000) : 0;
        publishEvent(new UsageSettled(ctx.bytesUp, ctx.bytesDn, activeSec));
    }
}
