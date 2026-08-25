package com.telcobright.routesphere.wifimachine.v2;

import com.telcobright.routesphere.wifimachine.v2.event.WifiEvents;
import com.telcobright.routesphere.wifimachine.v2.glue.GrantResult;
import com.telcobright.routesphere.wifimachine.v2.glue.WifiEngine;
import com.telcobright.routesphere.wifimachine.v2.policy.SessionPolicy;
import com.telcobright.routesphere.wifimachine.v2.port.GatePort;
import com.telcobright.routesphere.wifimachine.v2.port.LivenessSnapshotPort;
import com.telcobright.routesphere.wifimachine.v2.port.LoginStorePort;
import com.telcobright.routesphere.wifimachine.v2.port.QuotaRebindPort;
import com.telcobright.routesphere.wifimachine.v2.port.RadiusAccountingPort;
import com.telcobright.routesphere.wifimachine.v2.port.SdrPort;
import com.telcobright.routesphere.wifimachine.v2.port.WifiSdrRecord;
import com.telcobright.routesphere.wifimachine.v2.port.ZoneResolverPort;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSupervisorContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The session engine end to end through the glue: birth → captive → grant →
 * ESTABLISHED → end causes → exactly one SDR. Fakes are the test surface.
 *
 * <p>Safety invariants under test: the idle kill needs FRESH liveness
 * evidence (stale snapshot = zero kills); the ledger is money (volume
 * exhaust fires from counters, live or replayed); device limits deny with a
 * kick list; every close writes exactly one record.
 */
class WifiEngineFlowTest {

    /** Fast clocks: init 2s, captive 3s (child 2s), idle 1s, stale 60s. */
    private static final SessionPolicy FAST = new SessionPolicy(
        1, 0, "any", 2, 3, 2, 2, 60, 1, "one_click", 1, 1, 60, 300, true, 400, 90, false, 60);

    static final class FakeGate implements GatePort {
        final List<String> released = new CopyOnWriteArrayList<>();
        final List<String> gardened = new CopyOnWriteArrayList<>();
        @Override public void release(String mac, String sessionId, int minutes) { released.add(mac); }
        @Override public void garden(String mac) { gardened.add(mac); }
    }

    static final class FakeRadius implements RadiusAccountingPort {
        final List<String> starts = new CopyOnWriteArrayList<>();
        final List<String> stops = new CopyOnWriteArrayList<>();
        @Override public void acctStart(WifiSupervisorContext ctx) { starts.add(ctx.mac); }
        @Override public void acctStop(WifiSupervisorContext ctx, String cause) { stops.add(ctx.mac + ":" + cause); }
    }

    static final class FakeSdr implements SdrPort {
        final List<WifiSdrRecord> records = new CopyOnWriteArrayList<>();
        @Override public void write(WifiSdrRecord r) { records.add(r); }
    }

    static final class FakeLiveness implements LivenessSnapshotPort {
        volatile long snapshotMs;
        final Map<String, Long> lastActive = new ConcurrentHashMap<>();
        @Override public LivenessSnapshot readAll() { return new LivenessSnapshot(snapshotMs, Map.copyOf(lastActive)); }
    }

    static final class FakeLogin implements LoginStorePort {
        final Map<String, String> store = new ConcurrentHashMap<>();
        @Override public String lookup(String mac) { return store.get(mac); }
        @Override public void bind(String mac, String msisdn) { store.put(mac, msisdn); }
    }

    private FakeGate gate;
    private FakeRadius radius;
    private FakeSdr sdr;
    private FakeLiveness liveness;
    private FakeLogin login;
    private WifiEngine engine;

    @BeforeEach
    void up() {
        gate = new FakeGate();
        radius = new FakeRadius();
        sdr = new FakeSdr();
        liveness = new FakeLiveness();
        login = new FakeLogin();
        ZoneResolverPort zones = vlan -> new ZoneResolverPort.SiteZone("moghbazar", "dhaka-central");
        engine = new WifiEngine(
            new WifiEngine.Ports(gate, radius, sdr, liveness, login, zones, QuotaRebindPort.allowAll()),
            new AtomicReference<>(FAST), 64, 2, 1000);
    }

    @AfterEach
    void down() { engine.close(); }

    @Test
    void paidFlowEstablishesThenIdleEndsWithOneSdr() {
        String mac = "aa:bb:cc:00:00:01";
        engine.deviceSeen(mac, "3200");
        engine.captiveProbe(mac, "10.193.96.55", "Android (gms)");
        engine.portalOpened(mac);
        engine.otpSent(mac, "01711000001");
        engine.otpVerified(mac, "01711000001");
        GrantResult r = engine.grantSession(mac, "01711000001", 30, 0, "p-1");
        assertTrue(r.granted(), "grant should pass admission: " + r.cause());

        assertTrue(await(() -> gate.released.contains(mac)), "gate must release on ESTABLISHED");
        assertTrue(await(() -> radius.starts.contains(mac)), "Acct-Start on ESTABLISHED");
        assertTrue(await(() -> {
            WifiEngine.SessionMeta m = engine.sessionOf(mac);
            return m != null && "ESTABLISHED".equals(m.state());
        }));

        // traffic flows, then goes quiet — fresh liveness shows old lastActive
        engine.usageBatch(List.of(new WifiEvents.CountersDelta(mac, 5000, 20000, System.currentTimeMillis())));
        sleep(300);
        liveness.snapshotMs = System.currentTimeMillis();
        liveness.lastActive.put(mac, System.currentTimeMillis() - 5000); // idle >= 1s policy
        engine.livenessTick();

        assertTrue(await(() -> gate.gardened.contains(mac)), "idle must garden the MAC");
        assertTrue(await(() -> sdr.records.size() == 1), "exactly one SDR");
        WifiSdrRecord rec = sdr.records.get(0);
        assertEquals("TERMINATED", rec.outcome());
        assertEquals("idle", rec.releaseCause());
        assertEquals("8801711000001", rec.msisdn());
        assertEquals(25000, rec.bytesUp() + rec.bytesDn());
        assertTrue(await(() -> radius.stops.stream().anyMatch(s -> s.equals(mac + ":idle"))));
        assertTrue(await(() -> engine.sessionOf(mac) == null), "read-model row cleared on close");
    }

    @Test
    void silentDeviceRejectsWithOneRecordAndNoGateCalls() {
        String mac = "aa:bb:cc:00:00:02";
        engine.deviceSeen(mac, "3200");
        assertTrue(await(() -> sdr.records.size() == 1, 6000), "silent device must reject on the init clock");
        WifiSdrRecord rec = sdr.records.get(0);
        assertEquals("REJECTED", rec.outcome());
        assertEquals("silent", rec.releaseCause());
        assertTrue(gate.released.isEmpty(), "a rejected session never touched the gate");
        assertTrue(gate.gardened.isEmpty());
    }

    @Test
    void staleLivenessNeverKills() {
        String mac = "aa:bb:cc:00:00:03";
        engine.deviceSeen(mac, "3200");
        engine.captiveProbe(mac, "10.193.96.56", "iPhone");
        assertTrue(engine.grantSession(mac, "01711000002", 30, 0, "p-2").granted());
        assertTrue(await(() -> gate.released.contains(mac)));
        sleep(300);

        liveness.snapshotMs = System.currentTimeMillis() - 120_000; // stale vs 60s policy
        liveness.lastActive.put(mac, 1L);                            // "idle forever" — but stale
        engine.livenessTick();
        sleep(1200);
        assertTrue(gate.gardened.isEmpty(), "a stale snapshot must never end sessions");
        assertEquals(0, sdr.records.size());
        assertEquals("ESTABLISHED", engine.sessionOf(mac).state());
    }

    @Test
    void deviceLimitDeniesWithKickList() {
        String macA = "aa:bb:cc:00:00:04";
        String macB = "aa:bb:cc:00:00:05";
        engine.deviceSeen(macA, "3200");
        engine.captiveProbe(macA, "10.193.96.57", "Android");
        assertTrue(engine.grantSession(macA, "01711000003", 30, 0, "p-3").granted());
        assertTrue(await(() -> gate.released.contains(macA)));

        engine.deviceSeen(macB, "3200");
        engine.captiveProbe(macB, "10.193.96.58", "Android");
        GrantResult r = engine.grantSession(macB, "01711000003", 30, 0, "p-4");
        assertFalse(r.granted(), "second device must hit the limit (max 1)");
        assertEquals("device-limit", r.cause());
        assertEquals(List.of(macA), r.kickList());
    }

    @Test
    void volumeBudgetExhaustEndsSession() {
        String mac = "aa:bb:cc:00:00:06";
        engine.deviceSeen(mac, "3200");
        engine.captiveProbe(mac, "10.193.96.59", "Android");
        assertTrue(engine.grantSession(mac, "01711000004", 0, 1000, "p-5").granted());
        assertTrue(await(() -> gate.released.contains(mac)));
        sleep(300);

        engine.usageBatch(List.of(new WifiEvents.CountersDelta(mac, 600, 600, System.currentTimeMillis())));
        assertTrue(await(() -> sdr.records.size() == 1), "volume exhaust must close the session");
        assertEquals("volume", sdr.records.get(0).releaseCause());
        assertTrue(gate.gardened.contains(mac));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static boolean await(BooleanSupplier cond) { return await(cond, 4000); }

    private static boolean await(BooleanSupplier cond, long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            sleep(25);
        }
        return cond.getAsBoolean();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
