package com.telcobright.routesphere.wifimachine.v2.channel;

import com.telcobright.routesphere.wifimachine.v2.glue.WifiEngine;
import com.telcobright.routesphere.wifimachine.v2.policy.SessionPolicy;
import com.telcobright.routesphere.wifimachine.v2.port.LivenessSnapshotPort;
import com.telcobright.routesphere.wifimachine.v2.port.LoginStorePort;
import com.telcobright.routesphere.wifimachine.v2.port.RadiusAccountingPort;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSupervisorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pilot entry point: the session engine as a standalone SHADOW service on the
 * gateway host (systemd; JDK 21; Redis on localhost). The engine observes the
 * gateway event stream and records; the legacy authorizer still grants.
 * Liveness/login/zone ports are inert stubs here — the real feeds arrive with
 * the radius1 deployment (Kafka ledger + Redis liveness hash).
 *
 * <p>All configuration by environment (systemd Environment= lines):
 * REDIS_HOST/REDIS_PORT · EVT_STREAM · RECORD_STREAM · SHADOW_STREAM ·
 * POSITION_FILE · POOL_SIZE/MAX_CONCURRENT/THREADS.
 */
public final class WifiSessionRunner {

    private static final Logger LOG = LoggerFactory.getLogger(WifiSessionRunner.class);

    public static void main(String[] args) {
        String redisHost = env("REDIS_HOST", "127.0.0.1");
        int redisPort = Integer.parseInt(env("REDIS_PORT", "6379"));
        String evtStream = env("EVT_STREAM", "wifi:evt:gateway");
        String recordStream = env("RECORD_STREAM", "wifi:session:records");
        String shadowStream = env("SHADOW_STREAM", "wifi:session:shadow");
        Path positionFile = Path.of(env("POSITION_FILE", "/var/lib/wifi-session-service/position"));
        int poolSize = Integer.parseInt(env("POOL_SIZE", "512"));
        int maxConcurrent = Integer.parseInt(env("MAX_CONCURRENT", "5000"));
        int threads = Integer.parseInt(env("THREADS", "2"));

        JedisPooled jedis = new JedisPooled(redisHost, redisPort);

        RadiusAccountingPort noRadius = new RadiusAccountingPort() {
            @Override public void acctStart(WifiSupervisorContext ctx) { /* shadow: none */ }
            @Override public void acctStop(WifiSupervisorContext ctx, String cause) { /* shadow: none */ }
        };
        LivenessSnapshotPort staleAlways = () ->
            new LivenessSnapshotPort.LivenessSnapshot(0, Map.of()); // stale ⇒ never kills
        LoginStorePort noLogin = new LoginStorePort() {
            @Override public String lookup(String mac) { return null; }
            @Override public void bind(String mac, String msisdn) { /* shadow: none */ }
        };

        WifiEngine engine = new WifiEngine(
            new WifiEngine.Ports(
                RedisPorts.shadowGate(jedis, shadowStream),
                noRadius,
                RedisPorts.sdrRecords(jedis, recordStream),
                staleAlways,
                noLogin,
                vlan -> null,
                null), // null = the REAL registry-backed quota rebind
            new AtomicReference<>(SessionPolicy.defaults()),
            poolSize, threads, maxConcurrent);
        LOG.info("[WIFI-V2] engine READY (pool={}, maxConcurrent={}, SHADOW mode)", poolSize, maxConcurrent);

        GatewayEventBridge bridge =
            new GatewayEventBridge(engine, redisHost, redisPort, evtStream, positionFile);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            bridge.stop();
            engine.close();
        }, "wifi-v2-shutdown"));

        bridge.run(); // blocks until stopped
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
