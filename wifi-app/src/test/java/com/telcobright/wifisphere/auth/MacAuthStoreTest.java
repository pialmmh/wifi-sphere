package com.telcobright.wifisphere.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.wifisphere.vpp.VppApplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MacAuthStoreTest {

    /** Recording applier: enabled, but the "vppctl" is a list we can assert on. */
    private static VppApplier recorder(List<String> calls) {
        return new VppApplier(true, args -> { calls.add(String.join(" ", args)); return ""; });
    }

    @Test
    void grantPushesReleaseAndExpiryPushesGarden(@TempDir Path dir) throws Exception {
        List<String> calls = new ArrayList<>();
        MacAuthStore store = new MacAuthStore(dir.resolve("auth.json").toString(),
                new ObjectMapper(), recorder(calls));

        store.grant("AA:BB:CC:DD:EE:21", 1, 101, "moghbazar", "paid", 1, "8801743801850");
        assertEquals("mac-filter-wg mac aa:bb:cc:dd:ee:21 release", calls.get(0));

        Thread.sleep(1100);   // 1s grant passes
        List<String> kicked = store.sweepExpired();
        assertEquals(List.of("aa:bb:cc:dd:ee:21"), kicked);
        assertEquals("mac-filter-wg mac aa:bb:cc:dd:ee:21 garden", calls.get(1));
        assertEquals(AuthState.Disposition.GARDEN, store.get("aa:bb:cc:dd:ee:21").disposition());
    }

    @Test
    void statePersistsAcrossRestart(@TempDir Path dir) {
        String f = dir.resolve("auth.json").toString();
        ObjectMapper m = new ObjectMapper();
        new MacAuthStore(f, m, recorder(new ArrayList<>()))
                .grant("aa:bb:cc:dd:ee:22", 1, null, null, "free", 300, null);

        MacAuthStore reborn = new MacAuthStore(f, m, recorder(new ArrayList<>()));
        AuthState s = reborn.get("aa:bb:cc:dd:ee:22");
        assertNotNull(s, "a restart must keep every session's clock");
        assertEquals(AuthState.Disposition.RELEASED, s.disposition());
        assertEquals(300, s.grantedSeconds());
    }

    @Test
    void reconcileRepairsBothDriftDirections(@TempDir Path dir) {
        List<String> calls = new ArrayList<>();
        MacAuthStore store = new MacAuthStore(dir.resolve("auth.json").toString(),
                new ObjectMapper(), recorder(calls));
        store.grant("aa:bb:cc:dd:ee:31", 1, null, null, "paid", 600, null);
        calls.clear();

        // authority says released, plane lost it; plane has a stray release nobody granted
        int repairs = store.reconcile(Map.of(
                "aa:bb:cc:dd:ee:31", "garden",
                "aa:bb:cc:dd:ee:99", "release"));
        assertEquals(2, repairs);
        assertTrue(calls.contains("mac-filter-wg mac aa:bb:cc:dd:ee:31 release"));
        assertTrue(calls.contains("mac-filter-wg mac aa:bb:cc:dd:ee:99 garden"));

        // aligned plane = zero repairs
        assertEquals(0, store.reconcile(Map.of("aa:bb:cc:dd:ee:31", "release")));
    }

    @Test
    void shadowModeNeverTouchesThePlane(@TempDir Path dir) {
        List<String> calls = new ArrayList<>();
        VppApplier shadow = new VppApplier(false, args -> { calls.add("X"); return ""; });
        MacAuthStore store = new MacAuthStore(dir.resolve("auth.json").toString(),
                new ObjectMapper(), shadow);
        store.grant("aa:bb:cc:dd:ee:41", 1, null, null, "paid", 60, null);
        store.kick("aa:bb:cc:dd:ee:41", "test");
        assertTrue(calls.isEmpty(), "shadow mode must be a strict no-op on the data plane");
        assertEquals(AuthState.Disposition.GARDEN, store.get("aa:bb:cc:dd:ee:41").disposition());
    }
}
