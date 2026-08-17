package com.telcobright.wifisphere.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.wifisphere.vpp.VppApplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE authoritative MAC auth-state store (auth-authority ruling, 2026-08-18). Business-rich
 * state lives here; the data plane only ever receives the lean enforcement projection through
 * the {@link VppApplier}, incrementally, on state changes.
 *
 * Persistence: one JSON file, written atomically on every mutation, loaded on start — a
 * wifi-sphere restart keeps every session's clock. Expiry: the sweeper gardens released MACs
 * whose deadline passed (the authoritative countdown; the in-path deadline check in the data
 * plane remains the wire-speed backstop).
 */
public class MacAuthStore {

    private static final Logger log = LoggerFactory.getLogger(MacAuthStore.class);

    private final Map<String, AuthState> macs = new ConcurrentHashMap<>();
    private final Path stateFile;
    private final ObjectMapper mapper;
    private final VppApplier applier;

    public MacAuthStore(String stateFile, ObjectMapper mapper, VppApplier applier) {
        this.stateFile = Path.of(stateFile);
        this.mapper = mapper;
        this.applier = applier;
        load();
    }

    /** A grant becomes authoritative state + an incremental release push. Re-grant = top-up. */
    public AuthState grant(String mac, int tenantId, Integer vlanId, String siteId,
                           String tier, long seconds, String msisdn) {
        long now = Instant.now().getEpochSecond();
        AuthState s = new AuthState(norm(mac), tenantId, vlanId, AuthState.Disposition.RELEASED,
                tier, seconds, now * 1000, seconds > 0 ? now + seconds : 0, msisdn, siteId);
        macs.put(s.mac(), s);
        persist();
        applier.release(s.mac());
        return s;
    }

    /** De-authorize now (admin kick, entitlement kill, expiry). */
    public AuthState kick(String mac, String reason) {
        AuthState cur = macs.get(norm(mac));
        if (cur == null) return null;
        AuthState g = cur.gardened();
        macs.put(g.mac(), g);
        persist();
        applier.garden(g.mac());
        log.info("kicked mac {} ({})", g.mac(), reason);
        return g;
    }

    /** The authoritative countdown: garden everything whose deadline passed. Returns kicked macs. */
    public List<String> sweepExpired() {
        long now = Instant.now().getEpochSecond();
        List<String> kicked = new ArrayList<>();
        for (AuthState s : macs.values()) {
            if (s.expired(now)) {
                kick(s.mac(), "deadline");
                kicked.add(s.mac());
            }
        }
        return kicked;
    }

    /**
     * Anti-drift repair: the data plane must equal the authority's projection. Released here but
     * gardened there → re-release; released there but not authoritative → garden. Returns the
     * number of repairs (0 = no drift).
     */
    public int reconcile(Map<String, String> dataPlane) {
        int repairs = 0;
        long now = Instant.now().getEpochSecond();
        for (AuthState s : macs.values()) {
            if (s.disposition() == AuthState.Disposition.RELEASED && !s.expired(now)
                    && !"release".equals(dataPlane.get(s.mac()))) {
                applier.release(s.mac());
                repairs++;
            }
        }
        for (Map.Entry<String, String> e : dataPlane.entrySet()) {
            if ("release".equals(e.getValue())) {
                AuthState s = macs.get(e.getKey());
                if (s == null || s.disposition() != AuthState.Disposition.RELEASED || s.expired(now)) {
                    applier.garden(e.getKey());
                    repairs++;
                }
            }
        }
        if (repairs > 0) log.warn("anti-drift sweep repaired {} data-plane row(s)", repairs);
        return repairs;
    }

    public AuthState get(String mac) { return macs.get(norm(mac)); }

    public List<AuthState> all() { return new ArrayList<>(macs.values()); }

    public int size() { return macs.size(); }

    private synchronized void persist() {
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".new");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(macs));
            Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.error("auth-state persist failed: {}", e.getMessage());
        }
    }

    private void load() {
        try {
            if (Files.exists(stateFile)) {
                Map<String, AuthState> loaded = mapper.readValue(Files.readString(stateFile),
                        new TypeReference<>() { });
                macs.putAll(loaded);
                log.info("auth-state loaded: {} mac(s)", macs.size());
            }
        } catch (Exception e) {
            log.error("auth-state file unreadable ({}); starting empty", e.getMessage());
        }
    }

    private static String norm(String mac) { return mac.trim().toLowerCase(); }
}
