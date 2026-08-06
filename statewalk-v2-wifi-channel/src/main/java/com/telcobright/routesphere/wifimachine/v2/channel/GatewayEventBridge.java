package com.telcobright.routesphere.wifimachine.v2.channel;

import com.telcobright.routesphere.wifimachine.v2.event.WifiEvents;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSupervisorContext;
import com.telcobright.statewalk.v2.flat.Registry;
import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;
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
import java.util.concurrent.ConcurrentHashMap;
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

    private final Registry registry;
    private final String redisHost;
    private final int redisPort;
    private final String stream;
    private final Path positionFile;
    private final Map<String, String> liveSessionByMac = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public GatewayEventBridge(Registry registry, String redisHost, int redisPort,
                              String stream, Path positionFile) {
        this.registry = registry;
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
        if (type == null) return;
        switch (type) {
            case "deviceSeen" -> {
                if (mac == null) return;
                openSession(mac, f.getOrDefault("disp", "garden"));
            }
            case "captiveProbe" -> {
                // a probe can arrive for a device that predates the publisher
                // baseline — it is still a live signaling device: open for it
                if (mac == null || mac.isEmpty()) return;
                String id = liveSessionId(mac);
                if (id == null) id = openSession(mac, "garden");
                deliver(id, mac, new WifiEvents.CaptiveProbe(mac, f.get("ip"), f.get("probe")));
            }
            case "dispositionChanged" -> {
                if (mac == null) return;
                String to = f.get("to");
                String id = liveSessionId(mac);
                if ("release".equals(to)) {
                    if (id == null) id = openSession(mac, "release");
                    deliver(id, mac, new WifiEvents.GrantObserved(mac));
                } else if (id != null && "garden".equals(to)) {
                    deliver(id, mac, new WifiEvents.GrantRevoked(mac));
                }
            }
            case "deviceGone" -> {
                if (mac == null) return;
                String id = liveSessionId(mac);
                if (id != null) deliver(id, mac, new WifiEvents.DeviceGone(mac));
            }
            default -> { /* unknown types are future growth, not errors */ }
        }
    }

    private String openSession(String mac, String disp) {
        String id = mac.replace(":", "") + "-" + (System.currentTimeMillis() / 1000);
        WifiSupervisorContext ctx = new WifiSupervisorContext();
        ctx.sessionId = id;
        ctx.mac = mac;
        ctx.firstSeenMs = System.currentTimeMillis();
        registry.dispatch(id, ctx);
        awaitRegistered(id);
        liveSessionByMac.put(mac, id);
        LOG.info("[WIFI-V2] session open id={} (deviceSeen disp={})", id, disp);
        return id;
    }

    /** Live session id for this mac, or null (and mapping dropped) if its machine is gone. */
    private String liveSessionId(String mac) {
        String id = liveSessionByMac.get(mac);
        if (id == null) return null;
        if (!registry.hasAny(id)) {
            liveSessionByMac.remove(mac, id);
            return null;
        }
        return id;
    }

    private void deliver(String id, String mac, StatemachineEvent event) {
        try {
            registry.onInboundEvent(id, event);
        } catch (Exception e) {
            // delivery raced teardown — the machine finished concurrently; the
            // next sighting of this mac opens a fresh session
            LOG.warn("[WIFI-V2] delivery raced teardown id={} event={} ({})",
                id, event.getClass().getSimpleName(), e.getMessage());
            liveSessionByMac.remove(mac, id);
        }
    }

    private void awaitRegistered(String id) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (!registry.hasAny(id) && System.nanoTime() < deadline) sleep(10);
        if (!registry.hasAny(id)) LOG.warn("[WIFI-V2] dispatch not registered in 500ms id={}", id);
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
