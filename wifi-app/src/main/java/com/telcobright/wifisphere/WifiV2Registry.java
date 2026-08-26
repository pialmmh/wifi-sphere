package com.telcobright.wifisphere;

import com.telcobright.routesphere.wifimachine.v2.channel.GatewayEventBridge;
import com.telcobright.routesphere.wifimachine.v2.channel.RedisPorts;
import com.telcobright.routesphere.wifimachine.v2.glue.WifiEngine;
import com.telcobright.routesphere.wifimachine.v2.policy.SessionPolicy;
import com.telcobright.routesphere.wifimachine.v2.port.LivenessSnapshotPort;
import com.telcobright.routesphere.wifimachine.v2.port.LoginStorePort;
import com.telcobright.routesphere.wifimachine.v2.port.RadiusAccountingPort;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSupervisorContext;
import com.telcobright.seed.config.TenantConfigRegistry;
import com.telcobright.seed.config.TenantProfile;
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

    private volatile WifiEngine engine;
    private volatile GatewayEventBridge bridge;
    private volatile Thread bridgeThread;

    void onStart(@Observes StartupEvent ev) {
        if (!enabled) {
            LOG.info("[WIFI-V2] disabled (wifi.v2-enabled=false) — machines not started");
            return;
        }
        TenantProfile t = config.active();
        SessionPolicy policy = policyFrom(t);
        Map<String, Object> redisCfg = t.channel("redis", "redis-main");
        String host = str(redisCfg, "redis.host", "127.0.0.1");
        int port = intOf(redisCfg, "redis.port", 6379);
        String evtStream = str(redisCfg, "streams.gatewayEvents", "wifi:evt:gateway");
        String recStream = str(redisCfg, "streams.sessionRecords", "wifi:session:records");
        String shadowStream = str(redisCfg, "streams.shadowGrants", "wifi:session:shadow");
        Path positionFile = Path.of(str(redisCfg, "positionFile", "/var/lib/wifi-sphere/bridge-position"));

        JedisPooled jedis = new JedisPooled(host, port);
        RadiusAccountingPort noRadius = new RadiusAccountingPort() {
            @Override public void acctStart(WifiSupervisorContext ctx) { /* shadow */ }
            @Override public void acctStop(WifiSupervisorContext ctx, String cause) { /* shadow */ }
        };
        LivenessSnapshotPort staleAlways = () ->
            new LivenessSnapshotPort.LivenessSnapshot(0, Map.of()); // stale => never kills
        LoginStorePort noLogin = new LoginStorePort() {
            @Override public String lookup(String mac) { return null; }
            @Override public void bind(String mac, String msisdn) { /* shadow */ }
        };
        engine = new WifiEngine(
            new WifiEngine.Ports(
                RedisPorts.shadowGate(jedis, shadowStream),
                noRadius,
                RedisPorts.sdrRecords(jedis, recStream),
                staleAlways,
                noLogin,
                vlan -> null,
                null), // null = the REAL registry-backed quota rebind
            new WifiEngine.EngineIdentity(
                str(t.profile(), "wifi.operator", "btcl"),
                str(t.profile(), "wifi.gwId", "wifi-gw1")),
            new java.util.concurrent.atomic.AtomicReference<>(policy),
            intOf(t.profile(), "wifi.registry.poolSize", 512),
            intOf(t.profile(), "wifi.registry.threads", 2),
            intOf(t.profile(), "wifi.registry.maxConcurrent", 5000));

        bridge = new GatewayEventBridge(engine, host, port, evtStream, positionFile);
        bridgeThread = new Thread(bridge::run, "wifi-v2-bridge");
        bridgeThread.setDaemon(true);
        bridgeThread.start();
        LOG.infof("[WIFI-V2] READY tenant=%s redis=%s:%d stream=%s (SHADOW mode)",
            t.tenant(), host, port, evtStream);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (bridge != null) bridge.stop();
        if (engine != null) engine.close();
    }

    public boolean isActive() { return enabled && engine != null; }

    // ── small config readers (dotted paths over the yaml maps) ──────────

    /** The seed-profile overrides ride on the ratified defaults (full set = the runtime doc's session block). */
    private static SessionPolicy policyFrom(TenantProfile t) {
        SessionPolicy d = SessionPolicy.defaults();
        return new SessionPolicy(
            intOf(t.profile(), "wifi.session.maxDevicesPerUser", d.maxDevicesPerUser()),
            intOf(t.profile(), "wifi.session.maxDevicesPerUserPerZone", d.maxDevicesPerUserPerZone()),
            str(t.profile(), "wifi.session.zoneScope", d.zoneScope()),
            intOf(t.profile(), "wifi.session.initTimeoutSec", d.timeoutInitSec()),
            intOf(t.profile(), "wifi.session.captiveTimeoutSec", d.timeoutCaptiveSec()),
            intOf(t.profile(), "wifi.session.otpTimeoutSec", d.timeoutOtpSec()),
            intOf(t.profile(), "wifi.session.paymentTimeoutSec", d.timeoutPaymentSec()),
            intOf(t.profile(), "wifi.session.establishedMaxSec", d.establishedMaxSec()),
            intOf(t.profile(), "wifi.session.establishedIdleSec", d.establishedIdleSec()),
            str(t.profile(), "wifi.session.renewMode", d.renewMode()),
            intOf(t.profile(), "wifi.session.expiringGraceSec", d.expiringGraceSec()),
            intOf(t.profile(), "wifi.session.terminatedLingerSec", d.terminatedLingerSec()),
            intOf(t.profile(), "wifi.session.userDormantEvictSec", d.userDormantEvictSec()),
            intOf(t.profile(), "wifi.session.radiusInterimSec", d.radiusInterimSec()),
            d.sdrPartialAtCap(),
            d.sdrRetentionDays(),
            d.siglogRetentionDays(),
            d.siglogDebug(),
            intOf(t.profile(), "wifi.session.livenessStaleSec", d.livenessStaleSec()));
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
