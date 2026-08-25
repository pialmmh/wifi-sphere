package com.telcobright.routesphere.wifimachine.v2.channel;

import com.telcobright.routesphere.wifimachine.v2.glue.WifiEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadParams;
import redis.clients.jedis.resps.StreamEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The WiFi analogue of the ESL adapter: reads the gateway's observation stream
 * ({@code wifi:evt:gateway}), translates each entry into a {@link WifiEvents}
 * record, and routes it to the right session machine. No business logic here —
 * translation and routing only.
 *
 * <p><b>Session identity.</b> Machines are keyed
 * {@code <macNoColons>-<firstSeenEpochSec>}, never the bare MAC (the registry
 * dedups terminated ids for 60 s and WiFi devices reconnect fast). This bridge
 * owns the mac → live-session-id map: deviceSeen mints a fresh id; any event
 * for a mac whose machine is gone drops the mapping so the next sighting
 * starts clean.
 *
 * <p><b>Dispatch race.</b> {@code Registry.dispatch} is async — an event fired
 * before the machine registers is silently dropped. After minting a session the
 * bridge waits (bounded) for {@code hasAny(id)} before delivering more events
 * for that mac. In live traffic the 2 s publisher cadence dwarfs this wait.
 *
 * <p>The stream read position is persisted to a file after each batch, so a
 * restart resumes where it left off instead of replaying or skipping.
 */
public final class GatewayEventBridge {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayEventBridge.class);

    private final WifiEngine engine;
    private final String redisHost;
    private final int redisPort;
    private final String stream;
    private final Path positionFile;
    private volatile boolean running = true;

    public GatewayEventBridge(WifiEngine engine, String redisHost, int redisPort,
                              String stream, Path positionFile) {
        this.engine = engine;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.stream = stream;
        this.positionFile = positionFile;
    }

    public void stop() { running = false; }

    /** Blocking consume loop; reconnects forever on Redis loss (fail-still). */
    public void run() {
        StreamEntryID position = loadPosition();
        LOG.info("[WIFI-V2] bridge start stream={} from={}", stream, position);
        while (running) {
            try (Jedis jedis = new Jedis(redisHost, redisPort)) {
                while (running) {
                    List<Map.Entry<String, List<StreamEntry>>> batches = jedis.xread(
                        XReadParams.xReadParams().block(2000).count(200),
                        Map.of(stream, position));
                    if (batches == null) continue;
                    for (Map.Entry<String, List<StreamEntry>> batch : batches) {
                        for (StreamEntry entry : batch.getValue()) {
                            handle(entry.getFields());
                            position = entry.getID();
                        }
                    }
                    savePosition(position);
                }
            } catch (Exception e) {
                if (!running) break;
                LOG.warn("[WIFI-V2] redis consume lost ({}); retry in 3s", e.toString());
                sleep(3000);
            }
        }
        LOG.info("[WIFI-V2] bridge stopped");
    }

    // ─────────────────────────────────────────────────────────────────
    // Translation + routing
    // ─────────────────────────────────────────────────────────────────

    private void handle(Map<String, String> f) {
        String type = f.get("type");
        String mac = normalize(f.get("mac"));
        if (type == null || mac == null || mac.isEmpty()) return;
        switch (type) {
            case "deviceSeen" -> engine.deviceSeen(mac, f.get("vlan"));
            case "captiveProbe" -> engine.captiveProbe(mac, f.get("ip"), f.get("probe"));
            case "dispositionChanged" -> {
                String to = f.get("to");
                if ("release".equals(to)) engine.grantObserved(mac);
                else if ("garden".equals(to)) engine.grantRevoked(mac);
            }
            case "deviceGone" -> engine.deviceGone(mac);
            default -> { /* unknown types are future growth, not errors */ }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Position + small utils
    // ─────────────────────────────────────────────────────────────────

    private StreamEntryID loadPosition() {
        try {
            String s = Files.readString(positionFile).trim();
            if (!s.isEmpty()) return new StreamEntryID(s);
        } catch (IOException ignored) { /* first run */ }
        return new StreamEntryID(); // 0-0: consume from the stream's beginning
    }

    private void savePosition(StreamEntryID position) {
        try {
            Files.writeString(positionFile, position.toString());
        } catch (IOException e) {
            LOG.warn("[WIFI-V2] cannot persist position: {}", e.toString());
        }
    }

    private static String normalize(String mac) {
        return mac == null ? null : mac.trim().toLowerCase();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
