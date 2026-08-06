package com.telcobright.routesphere.wifimachine.v2;

import com.telcobright.routesphere.wifimachine.v2.event.WifiEvents;
import com.telcobright.routesphere.wifimachine.v2.port.WifiSessionRecord;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSessionSupervisor;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSignaling;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSupervisorContext;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiTimeouts;
import com.telcobright.statewalk.v2.flat.Registry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WiFi v2 session lifecycle on the flat design (mirrors {@code SmsV2FlowTest}).
 *
 * <p>Business invariants under test:
 * <ul>
 *   <li>grant → exactly one MAC grant; session end → exactly one revoke and
 *       exactly one ENDED record;</li>
 *   <li>probed-but-never-authed → NO grant, one REJECTED record carrying the
 *       full probe history (the device-analytics payload);</li>
 *   <li>silent / departed devices reject with the right reason;</li>
 *   <li>every path retires its cells (pool reuse, no leaks).</li>
 * </ul>
 */
class WifiV2FlowTest {

    /** Short clocks so timeout paths run in test time (child 2s < signaling 3s). */
    private static final WifiTimeouts FAST = new WifiTimeouts(2, 3, 2, 3600);

    private Registry registry;

    private final List<String> grants = new CopyOnWriteArrayList<>();   // "mac|policy"
    private final List<String> revokes = new CopyOnWriteArrayList<>();  // "mac"
    private final List<WifiSessionRecord> records = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        registry = Registry.builder("wifi-v2-test")
            .supervisor("WifiSessionSupervisor",
                () -> new WifiSessionSupervisor(
                    new com.telcobright.routesphere.wifimachine.v2.port.MacTablePort() {
                        @Override public void grant(String mac, String policyJson) { grants.add(mac + "|" + policyJson); }
                        @Override public void revoke(String mac) { revokes.add(mac); }
                    },
                    records::add,
                    FAST),
                4)
            .child("WifiSignaling", () -> new WifiSignaling(FAST), 4)
            .threads(2)
            .build();
    }

    @AfterEach
    void tearDown() { if (registry != null) registry.shutdown(); }

    private String openSession(String mac) throws InterruptedException {
        return openSessionAs(mac, mac.replace(":", "") + "-" + (System.currentTimeMillis() / 1000));
    }

    private String openSessionAs(String mac, String sessionId) throws InterruptedException {
        WifiSupervisorContext ctx = new WifiSupervisorContext();
        ctx.sessionId = sessionId;
        ctx.mac = mac;
        ctx.firstSeenMs = System.currentTimeMillis();
        registry.dispatch(sessionId, ctx);
        assertTrue(registry.awaitIdle(2, TimeUnit.SECONDS));
        return sessionId;
    }

    private void probe(String id, String mac, String label) throws InterruptedException {
        registry.onInboundEvent(id, new WifiEvents.CaptiveProbe(mac, "10.10.2.50", label));
        assertTrue(registry.awaitIdle(2, TimeUnit.SECONDS));
    }

    @Test
    void granted_session_grants_once_then_ends_with_one_record() throws InterruptedException {
        String mac = "02:fa:de:00:00:01";
        String id = openSession(mac);
        assertEquals(2, registry.activeCellCount()); // supervisor + signaling child

        probe(id, mac, "Android (gms)");
        probe(id, mac, "Android (gms)");            // duplicate label — logged once
        probe(id, mac, "Samsung Internet");

        registry.onInboundEvent(id, new WifiEvents.GrantObserved(mac));
        assertTrue(registry.awaitIdle(2, TimeUnit.SECONDS));
        assertEquals(1, grants.size());
        assertTrue(grants.get(0).startsWith(mac + "|"));
        assertTrue(registry.hasAny(id));            // session is live in AUTHENTICATED

        registry.onInboundEvent(id, new WifiEvents.GrantRevoked(mac));
        assertTrue(registry.awaitIdle(2, TimeUnit.SECONDS));

        assertEquals(List.of(mac), revokes);
        assertEquals(1, records.size());
        WifiSessionRecord r = records.get(0);
        assertEquals("ENDED", r.outcome());
        assertEquals("granted-session-over", r.reason());
        assertEquals(3, r.probeCount());
        assertEquals(List.of("Android (gms)", "Samsung Internet"), r.probeLabels());
        assertTrue(r.authedAtMs() > 0);
        assertEquals(0, registry.activeCellCount()); // everything retired
    }

    @Test
    void probed_but_never_authed_rejects_with_probe_history_and_no_grant() throws InterruptedException {
        String mac = "02:fa:de:00:00:02";
        String id = openSession(mac);

        probe(id, mac, "Xiaomi (miui)");
        probe(id, mac, "Android (gms)");

        // child probing window (2s) expires -> SignalingFailed -> REJECTED
        Thread.sleep(2500);
        assertTrue(registry.awaitIdle(3, TimeUnit.SECONDS));

        assertEquals(0, grants.size());
        assertEquals(0, revokes.size());
        assertEquals(1, records.size());
        WifiSessionRecord r = records.get(0);
        assertEquals("REJECTED", r.outcome());
        assertEquals("noAuth", r.reason());
        assertEquals(2, r.probeCount());
        assertEquals(List.of("Xiaomi (miui)", "Android (gms)"), r.probeLabels());
        assertEquals(0, r.authedAtMs());
        assertEquals(0, registry.activeCellCount());
    }

    @Test
    void silent_device_rejects_after_init_timeout() throws InterruptedException {
        String mac = "02:fa:de:00:00:03";
        openSession(mac);

        Thread.sleep(2500); // INIT timeout (2s) with no probe at all
        assertTrue(registry.awaitIdle(3, TimeUnit.SECONDS));

        assertEquals(1, records.size());
        assertEquals("REJECTED", records.get(0).outcome());
        assertEquals("silent", records.get(0).reason());
        assertEquals(0, records.get(0).probeCount());
        assertEquals(0, registry.activeCellCount());
    }

    @Test
    void device_leaving_mid_signaling_rejects_as_left() throws InterruptedException {
        String mac = "02:fa:de:00:00:04";
        String id = openSession(mac);
        probe(id, mac, "iPhone (CNA)");

        registry.onInboundEvent(id, new WifiEvents.DeviceGone(mac));
        assertTrue(registry.awaitIdle(2, TimeUnit.SECONDS));

        assertEquals(1, records.size());
        assertEquals("REJECTED", records.get(0).outcome());
        assertEquals("left", records.get(0).reason());
        assertEquals(0, grants.size());
        assertEquals(0, registry.activeCellCount());
    }

    @Test
    void pool_reuse_over_repeated_sessions_leaves_no_cells() throws InterruptedException {
        // Full lifecycle per iteration, awaiting idle between stimuli — the
        // deep-call-test pattern (ITER over one registry exercises pool reuse).
        // Firing events in the same instant as dispatch races the async machine
        // registration; the bridge waits for hasAny(id) for exactly this reason.
        for (int i = 0; i < 25; i++) {
            String mac = String.format("02:fa:de:10:00:%02x", i);
            String id = openSessionAs(mac, mac.replace(":", "") + "-" + i);
            probe(id, mac, "Android (gms)");
            registry.onInboundEvent(id, new WifiEvents.GrantObserved(mac));
            assertTrue(registry.awaitIdle(2, TimeUnit.SECONDS));
            registry.onInboundEvent(id, new WifiEvents.GrantRevoked(mac));
            assertTrue(registry.awaitIdle(2, TimeUnit.SECONDS));
        }
        assertEquals(25, grants.size());
        assertEquals(25, revokes.size());
        assertEquals(25, records.stream().filter(r -> "ENDED".equals(r.outcome())).count());
        assertEquals(0, registry.activeCellCount());
    }
}
