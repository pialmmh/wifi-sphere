package com.telcobright.routesphere.wifimachine.v2.channel;

import com.telcobright.routesphere.wifimachine.v2.port.GatePort;
import com.telcobright.routesphere.wifimachine.v2.port.SdrPort;
import com.telcobright.routesphere.wifimachine.v2.port.WifiSdrRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pilot port implementations, Redis-backed. SHADOW stance: the machine
 * tracks and records but the legacy authorizer still grants —
 * {@code shadowGate} writes would-be grants/gardens to a shadow stream.
 * Flipping to an enforcing gate is a separate, architect-ratified step.
 */
public final class RedisPorts {

    private static final Logger LOG = LoggerFactory.getLogger(RedisPorts.class);

    private RedisPorts() {}

    public static GatePort shadowGate(JedisPooled jedis, String shadowStream) {
        return new GatePort() {
            @Override public void release(String mac, String sessionId, int minutes) {
                LOG.info("[WIFI-V2] SHADOW release mac={} session={} minutes={}", mac, sessionId, minutes);
                xadd(jedis, shadowStream, Map.of("op", "grant", "mac", mac,
                    "session", sessionId, "minutes", Integer.toString(minutes)));
            }
            @Override public void garden(String mac) {
                LOG.info("[WIFI-V2] SHADOW garden mac={}", mac);
                xadd(jedis, shadowStream, Map.of("op", "revoke", "mac", mac));
            }
        };
    }

    public static SdrPort sdrRecords(JedisPooled jedis, String recordStream) {
        return (WifiSdrRecord r) -> {
            LOG.info("[WIFI-V2] SDR id={} outcome={} cause={} msisdn={} bytes={}/{}",
                r.sessionId(), r.outcome(), r.releaseCause(), r.msisdn(), r.bytesUp(), r.bytesDn());
            Map<String, String> f = new LinkedHashMap<>();
            f.put("sessionId", r.sessionId());
            f.put("mac", r.mac());
            f.put("msisdn", nz(r.msisdn()));
            f.put("ip", nz(r.ip()));
            f.put("site", nz(r.site()));
            f.put("zone", nz(r.zone()));
            f.put("vlan", nz(r.vlan()));
            f.put("outcome", r.outcome());
            f.put("cause", nz(r.releaseCause()));
            f.put("firstSeenMs", Long.toString(r.firstSeenMs()));
            f.put("establishedAtMs", Long.toString(r.establishedAtMs()));
            f.put("endedAtMs", Long.toString(r.endedAtMs()));
            f.put("activeSeconds", Long.toString(r.activeSeconds()));
            f.put("bytesUp", Long.toString(r.bytesUp()));
            f.put("bytesDn", Long.toString(r.bytesDn()));
            f.put("purchaseId", nz(r.purchaseId()));
            f.put("grantedMinutes", Integer.toString(r.grantedMinutes()));
            f.put("partial", Boolean.toString(r.partialFlag()));
            f.put("probeCount", Integer.toString(r.probeCount()));
            f.put("probeLabels", String.join("|", r.probeLabels()));
            xadd(jedis, recordStream, f);
        };
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static void xadd(JedisPooled jedis, String stream, Map<String, String> fields) {
        try {
            jedis.xadd(stream, XAddParams.xAddParams().id(StreamEntryID.NEW_ENTRY), fields);
        } catch (RuntimeException e) {
            // fail-still: a Redis blip must never take a machine down with it
            LOG.warn("[WIFI-V2] xadd {} failed: {}", stream, e.toString());
        }
    }
}
