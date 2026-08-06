package com.telcobright.routesphere.wifimachine.v2.channel;

import com.telcobright.routesphere.wifimachine.v2.port.MacTablePort;
import com.telcobright.routesphere.wifimachine.v2.port.SessionRecordPort;
import com.telcobright.routesphere.wifimachine.v2.port.WifiSessionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pilot port implementations, all Redis-backed.
 *
 * <p><b>SHADOW mode (the pilot stance):</b> the machine tracks and records but
 * the legacy authorizer still grants. {@code ShadowMacTablePort} therefore
 * writes would-be grants/revokes to {@code wifi:session:shadow} instead of the
 * authoritative {@code wifi:mac:*} keys. Flipping to an enforcing port — which
 * would make the machine the macTableSync producer — is a separate,
 * architect-ratified migration step (double-writer risk).
 */
public final class RedisPorts {

    private static final Logger LOG = LoggerFactory.getLogger(RedisPorts.class);

    private RedisPorts() {}

    public static MacTablePort shadowMacTable(JedisPooled jedis, String shadowStream) {
        return new MacTablePort() {
            @Override public void grant(String mac, String policyJson) {
                LOG.info("[WIFI-V2] SHADOW grant mac={} policy={}", mac, policyJson);
                xadd(jedis, shadowStream, Map.of("op", "grant", "mac", mac, "policy", policyJson));
            }
            @Override public void revoke(String mac) {
                LOG.info("[WIFI-V2] SHADOW revoke mac={}", mac);
                xadd(jedis, shadowStream, Map.of("op", "revoke", "mac", mac));
            }
        };
    }

    public static SessionRecordPort sessionRecords(JedisPooled jedis, String recordStream) {
        return (WifiSessionRecord r) -> {
            LOG.info("[WIFI-V2] session record id={} outcome={} reason={} probes={} labels={}",
                r.sessionId(), r.outcome(), r.reason(), r.probeCount(), r.probeLabels());
            Map<String, String> f = new LinkedHashMap<>();
            f.put("sessionId", r.sessionId());
            f.put("mac", r.mac());
            f.put("outcome", r.outcome());
            f.put("reason", r.reason() == null ? "" : r.reason());
            f.put("firstSeenMs", Long.toString(r.firstSeenMs()));
            f.put("authedAtMs", Long.toString(r.authedAtMs()));
            f.put("endedAtMs", Long.toString(r.endedAtMs()));
            f.put("probeCount", Integer.toString(r.probeCount()));
            f.put("probeLabels", String.join("|", r.probeLabels()));
            xadd(jedis, recordStream, f);
        };
    }

    private static void xadd(JedisPooled jedis, String stream, Map<String, String> fields) {
        try {
            jedis.xadd(stream, XAddParams.xAddParams()
                .id(StreamEntryID.NEW_ENTRY).maxLen(100_000).approximateTrimming(), fields);
        } catch (Exception e) {
            // never let a sink failure back up into the machine's chain
            LOG.warn("[WIFI-V2] XADD {} failed: {}", stream, e.toString());
        }
    }
}
