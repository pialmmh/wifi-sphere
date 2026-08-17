package com.telcobright.wifisphere.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.config.TenantConfigRegistry;
import com.telcobright.wifisphere.vpp.VppApplier;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Wires the auth authority from the seed config tree:
 *
 *   wifi.applier:
 *     enabled: false                   # SHADOW until cutover — applies are log-only
 *     vppctl: "vppctl -s /run/vpp/cli.sock"
 *     stateFile: "/var/lib/wifi-sphere/mac-auth.json"
 *     expirySweepSeconds: 5            # the authoritative countdown
 *     driftSweepSeconds: 60            # dump-diff-repair (only when enabled)
 */
@ApplicationScoped
public class AuthPlaneProducer {

    private static final Logger log = LoggerFactory.getLogger(AuthPlaneProducer.class);

    @Inject
    TenantConfigRegistry registry;

    private MacAuthStore store;
    private VppApplier applier;
    private ScheduledExecutorService sweeper;

    @Produces
    @Singleton
    public MacAuthStore store() { return store; }

    @Produces
    @Singleton
    public VppApplier applier() { return applier; }

    void onStart(@Observes StartupEvent ev) {
        Map<String, Object> cfg = section("wifi.applier");
        boolean enabled = cfg != null && Boolean.TRUE.equals(cfg.get("enabled"));
        String vppctl = str(cfg, "vppctl", "vppctl -s /run/vpp/cli.sock");
        String[] base = vppctl.trim().split("\\s+");
        applier = new VppApplier(enabled, args -> {
            String[] cmd = new String[base.length + args.length];
            System.arraycopy(base, 0, cmd, 0, base.length);
            System.arraycopy(args, 0, cmd, base.length, args.length);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0)
                throw new IllegalStateException("vppctl failed: " + out.strip());
            return out;
        });
        store = new MacAuthStore(str(cfg, "stateFile", "/var/lib/wifi-sphere/mac-auth.json"),
                new ObjectMapper(), applier);

        long expiryS = longVal(cfg, "expirySweepSeconds", 5);
        long driftS = longVal(cfg, "driftSweepSeconds", 60);
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auth-sweeper"); t.setDaemon(true); return t;
        });
        sweeper.scheduleAtFixedRate(() -> {
            try { store.sweepExpired(); } catch (Exception e) { log.error("expiry sweep failed", e); }
        }, expiryS, expiryS, TimeUnit.SECONDS);
        if (enabled) {
            sweeper.scheduleAtFixedRate(() -> {
                try { store.reconcile(applier.dumpDispositions()); }
                catch (Exception e) { log.warn("drift sweep failed: {}", e.getMessage()); }
            }, driftS, driftS, TimeUnit.SECONDS);
        }
        log.info("auth plane up: applier {} ({} mac(s) loaded)",
                enabled ? "ENABLED" : "SHADOW", store.size());
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (sweeper != null) sweeper.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String key) {
        Object o = registry.active().get(key);
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String str(Map<String, Object> m, String k, String d) {
        Object v = m == null ? null : m.get(k);
        return v == null ? d : String.valueOf(v);
    }

    private static long longVal(Map<String, Object> m, String k, long d) {
        Object v = m == null ? null : m.get(k);
        try { return v == null ? d : Long.parseLong(String.valueOf(v)); }
        catch (NumberFormatException e) { return d; }
    }
}
