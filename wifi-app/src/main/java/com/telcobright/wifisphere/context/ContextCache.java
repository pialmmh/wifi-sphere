package com.telcobright.wifisphere.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.config.TenantConfigRegistry;
import com.telcobright.seed.config.TenantProfile;
import com.telcobright.seed.configclient.ConfigDoorbell;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * The gateway's dynamic-context cache — the runtime half of the config plane.
 *
 * Boot: fetch the versioned gateway-config document from the BSS (bearer auth), fall back to the
 * last-good copy on disk; the gateway can serve on cached context with the BSS down.
 * Runtime: a payload-less doorbell on the (existing BTCL) Kafka rings after any change; the cache
 * re-fetches once per quiet period. A slow poll is the reliable floor under lost doorbells.
 *
 * Consumers: the RADIUS resource, the VPP reconciler (later step), the session/dashboard API.
 */
@ApplicationScoped
public class ContextCache {

    private static final Logger log = LoggerFactory.getLogger(ContextCache.class);

    @Inject
    TenantConfigRegistry registry;

    private ContextLoader loader;
    private ConfigDoorbell doorbell;
    private Thread pollThread;
    private volatile boolean running = true;

    void onStart(@Observes StartupEvent ev) {
        TenantProfile p = registry.active();
        Map<String, Object> cp = section(p, "wifi.contextPlane");
        loader = new ContextLoader(
                str(cp, "gatewayId", "bras-1"),
                str(cp, "configUrl", ""),
                str(cp, "tokenFile", ""),
                str(cp, "cacheFile", "/var/lib/wifi-sphere/desired-state.json"),
                new ObjectMapper());
        loader.load();

        Map<String, Object> bell = p.channel("kafka", "doorbell-main");
        if (bell != null && Boolean.TRUE.equals(bell.get("enabled"))) {
            doorbell = ConfigDoorbell.builder()
                    .kafka(String.valueOf(bell.get("brokers")), String.valueOf(bell.get("topic")))
                    .debounceMs(longVal(bell.get("debounceMs"), 3_000))
                    .onRing(loader::load)
                    .build()
                    .start();
        } else {
            log.info("doorbell disabled for {}/{} — poll only", p.tenant(), p.profileName());
        }

        long pollSec = longVal(cp == null ? null : cp.get("pollSeconds"), 300);
        pollThread = new Thread(() -> pollLoop(pollSec), "context-poll");
        pollThread.setDaemon(true);
        pollThread.start();
        log.info("context plane up: gateway={} version={} source={}",
                loader.gatewayId(), loader.version(), loader.source());
    }

    void onStop(@Observes ShutdownEvent ev) {
        running = false;
        if (doorbell != null) doorbell.close();
        if (pollThread != null) pollThread.interrupt();
    }

    private void pollLoop(long pollSec) {
        while (running) {
            try {
                Thread.sleep(pollSec * 1_000);
                loader.load();
            } catch (InterruptedException ie) {
                return;
            } catch (Exception e) {
                log.warn("context poll failed: {}", e.getMessage());
            }
        }
    }

    public JsonNode document() { return loader.document(); }

    /** The immutable typed view (lifecycle A): vlanTable, sites, zones, tenant. */
    public ContextSnapshot snapshot() { return loader.snapshot(); }

    public long version() { return loader.version(); }

    public String gatewayId() { return loader.gatewayId(); }

    public String source() { return loader.source(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(TenantProfile p, String key) {
        Object o = p.get(key);
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m == null ? null : m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static long longVal(Object v, long def) {
        if (v == null) return def;
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return def; }
    }
}
