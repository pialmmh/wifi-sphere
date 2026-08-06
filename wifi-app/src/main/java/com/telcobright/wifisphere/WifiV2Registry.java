package com.telcobright.wifisphere;

import com.telcobright.routesphere.wifimachine.v2.channel.GatewayEventBridge;
import com.telcobright.routesphere.wifimachine.v2.channel.RedisPorts;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSessionSupervisor;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSignaling;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiTimeouts;
import com.telcobright.seed.config.TenantConfigRegistry;
import com.telcobright.seed.config.TenantProfile;
import com.telcobright.statewalk.v2.flat.Registry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import redis.clients.jedis.JedisPooled;

import java.nio.file.Path;
import java.util.Map;

/**
 * The WiFi session machines as a bean in THE wifi-sphere Quarkus instance —
 * the {@code SmsV2Registry} pattern: flag-gated, config from the seed tenant
 * tree, machines + bridge unchanged from the pilot. SHADOW mode: grants are
 * recorded, not enforced (the enforce flip is architect-ratified, separate).
 */
@ApplicationScoped
public class WifiV2Registry {

    private static final Logger LOG = Logger.getLogger(WifiV2Registry.class);

    @ConfigProperty(name = "wifi.v2-enabled", defaultValue = "false")
    boolean enabled;

    @Inject
    TenantConfigRegistry config;

    private volatile Registry registry;
    private volatile GatewayEventBridge bridge;
    private volatile Thread bridgeThread;

    void onStart(@Observes StartupEvent ev) {
        if (!enabled) {
            LOG.info("[WIFI-V2] disabled (wifi.v2-enabled=false) — machines not started");
            return;
        }
        TenantProfile t = config.active();
        WifiTimeouts timeouts = timeoutsFrom(t);
        Map<String, Object> redisCfg = t.channel("redis", "redis-main");
        String host = str(redisCfg, "redis.host", "127.0.0.1");
        int port = intOf(redisCfg, "redis.port", 6379);
        String evtStream = str(redisCfg, "streams.gatewayEvents", "wifi:evt:gateway");
        String recStream = str(redisCfg, "streams.sessionRecords", "wifi:session:records");
        String shadowStream = str(redisCfg, "streams.shadowGrants", "wifi:session:shadow");
        Path positionFile = Path.of(str(redisCfg, "positionFile", "/var/lib/wifi-sphere/bridge-position"));

        JedisPooled jedis = new JedisPooled(host, port);
        registry = Registry.builder("wifi-v2")
            .supervisor("WifiSessionSupervisor",
                () -> new WifiSessionSupervisor(
                    RedisPorts.shadowMacTable(jedis, shadowStream),
                    RedisPorts.sessionRecords(jedis, recStream),
                    timeouts),
                intOf(t.profile(), "wifi.registry.poolSize", 512))
            .child("WifiSignaling", () -> new WifiSignaling(timeouts),
                intOf(t.profile(), "wifi.registry.poolSize", 512))
            .threads(intOf(t.profile(), "wifi.registry.threads", 2))
            .maxConcurrent(intOf(t.profile(), "wifi.registry.maxConcurrent", 5000))
            .build();

        bridge = new GatewayEventBridge(registry, host, port, evtStream, positionFile);
        bridgeThread = new Thread(bridge::run, "wifi-v2-bridge");
        bridgeThread.setDaemon(true);
        bridgeThread.start();
        LOG.infof("[WIFI-V2] READY tenant=%s redis=%s:%d stream=%s (SHADOW mode)",
            t.tenant(), host, port, evtStream);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (bridge != null) bridge.stop();
        if (registry != null) registry.shutdown();
    }

    public boolean isActive() { return enabled && registry != null; }

    // ── small config readers (dotted paths over the yaml maps) ──────────

    private static WifiTimeouts timeoutsFrom(TenantProfile t) {
        return new WifiTimeouts(
            intOf(t.profile(), "wifi.session.initTimeoutSec", 120),
            intOf(t.profile(), "wifi.session.signalingTimeoutSec", 300),
            intOf(t.profile(), "wifi.session.probingChildSec", 280),
            intOf(t.profile(), "wifi.session.sessionMaxSec", 86400));
    }

    @SuppressWarnings("unchecked")
    private static Object walk(Map<String, Object> node, String dotted) {
        Object cur = node;
        for (String hop : dotted.split("\\.")) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(hop);
            if (cur == null) return null;
        }
        return cur;
    }

    private static String str(Map<String, Object> node, String path, String def) {
        Object v = node == null ? null : walk(node, path);
        return v == null ? def : v.toString();
    }

    private static int intOf(Map<String, Object> node, String path, int def) {
        Object v = node == null ? null : walk(node, path);
        return v instanceof Number n ? n.intValue() : def;
    }
}
