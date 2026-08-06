package com.telcobright.routesphere.wifimachine.v2.channel;

import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSessionSupervisor;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSignaling;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiTimeouts;
import com.telcobright.statewalk.v2.flat.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.nio.file.Path;

/**
 * Pilot entry point: WiFi session machines as a standalone service on the
 * gateway host (systemd; JDK 21; Redis on localhost). Later this wiring moves
 * into routesphere-core behind {@code routesphere.wifi.v2-enabled}, per the
 * call/SMS convention — the machines and the bridge move unchanged.
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
        WifiTimeouts timeouts = WifiTimeouts.production();

        Registry registry = Registry.builder("wifi-v2")
            .supervisor("WifiSessionSupervisor",
                () -> new WifiSessionSupervisor(
                    RedisPorts.shadowMacTable(jedis, shadowStream),
                    RedisPorts.sessionRecords(jedis, recordStream),
                    timeouts),
                poolSize)
            .child("WifiSignaling", () -> new WifiSignaling(timeouts), poolSize)
            .threads(threads)
            .maxConcurrent(maxConcurrent)
            .build();
        LOG.info("[WIFI-V2] registry READY (pool={}, maxConcurrent={}, SHADOW mode)",
            poolSize, maxConcurrent);

        GatewayEventBridge bridge =
            new GatewayEventBridge(registry, redisHost, redisPort, evtStream, positionFile);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            bridge.stop();
            registry.shutdown();
        }, "wifi-v2-shutdown"));

        bridge.run(); // blocks until stopped
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
