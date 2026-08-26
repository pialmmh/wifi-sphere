package com.telcobright.routesphere.wifimachine.v2.glue;

import com.telcobright.routesphere.wifimachine.v2.event.WifiEvents;
import com.telcobright.routesphere.wifimachine.v2.index.IndexContext;
import com.telcobright.routesphere.wifimachine.v2.index.IndexMachine;
import com.telcobright.routesphere.wifimachine.v2.index.MembershipEvt;
import com.telcobright.routesphere.wifimachine.v2.policy.SessionPolicy;
import com.telcobright.routesphere.wifimachine.v2.port.GatePort;
import com.telcobright.routesphere.wifimachine.v2.port.LivenessSnapshotPort;
import com.telcobright.routesphere.wifimachine.v2.port.LoginStorePort;
import com.telcobright.routesphere.wifimachine.v2.port.MembershipChange;
import com.telcobright.routesphere.wifimachine.v2.port.QuotaRebindPort;
import com.telcobright.routesphere.wifimachine.v2.port.RadiusAccountingPort;
import com.telcobright.routesphere.wifimachine.v2.port.SdrPort;
import com.telcobright.routesphere.wifimachine.v2.port.ZoneResolverPort;
import com.telcobright.routesphere.wifimachine.v2.supervisor.CaptiveFlow;
import com.telcobright.routesphere.wifimachine.v2.supervisor.UsageTracker;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSessionSupervisor;
import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSupervisorContext;
import com.telcobright.statewalk.v2.flat.Registry;
import com.telcobright.statewalk.v2.registry.api.DispatchResult;
import com.telcobright.statewalk.v2.registry.api.QuotaKeys;
import com.telcobright.statewalk.v2.registry.api.QuotaLimits;
import com.telcobright.statewalk.v2.registry.api.RejectCause;
import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The registry glue: three statewalk registries (sessions + user index +
 * zone index), the transport-neutral inbox, the admission pipeline (the ONE
 * enforcement door), the membership fan-out, and the liveness probe.
 * Bridges (Redis stream, Kafka, portal HTTP) call the inbox methods; the
 * machines never see a transport, the transports never see a machine.
 */
public final class WifiEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WifiEngine.class);

    /**
     * Everything the machines need injected — build one, hand it in.
     * {@code quotaRebind} null = the REAL registry-backed rebind (the statewalk
     * base extension); non-null overrides it (test fakes, deny simulations).
     */
    public record Ports(GatePort gate,
                        RadiusAccountingPort radius,
                        SdrPort sdr,
                        LivenessSnapshotPort liveness,
                        LoginStorePort loginStore,
                        ZoneResolverPort zoneResolver,
                        QuotaRebindPort quotaRebind) {}

    /** The deployment's place in the hierarchy: operator → zone → site; the bras SERVES the zone. */
    public record EngineIdentity(String operator, String gwId) {}

    /** Read-model row per live session (display + admission reads; ONE writer = onMembership). */
    public record SessionMeta(String sessionId, String mac, String msisdn, String zone,
                              String site, String gwId, String state) {}

    private final Ports ports;
    private final EngineIdentity identity;
    private final AtomicReference<SessionPolicy> policyRef;
    private final Registry sessions;
    private final Registry userIndex;
    private final Registry zoneIndex;

    private final Map<String, String> liveSessionByMac = new ConcurrentHashMap<>();
    private final Map<String, SessionMeta> metaByMac = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> macsByUser = new ConcurrentHashMap<>();

    private final ScheduledExecutorService clock =
        Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "wifi-liveness-probe"));
    private volatile boolean livenessStarted;
    private volatile long lastStaleWarnMs;

    public WifiEngine(Ports ports, EngineIdentity identity, AtomicReference<SessionPolicy> policyRef,
                      int poolSize, int threads, int maxConcurrent) {
        this.ports = ports;
        this.identity = identity;
        this.policyRef = policyRef;
        SessionPolicy p = policyRef.get();

        this.sessions = Registry.builder("wifi-sessions")
            .supervisor("WifiSessionSupervisor",
                () -> new WifiSessionSupervisor(ports.gate(), ports.radius(), ports.sdr(),
                    this::onMembership, policyRef),
                poolSize)
            .child(WifiSessionSupervisor.CAPTIVE_CHILD, () -> new CaptiveFlow(policyRef), poolSize)
            .child(WifiSessionSupervisor.USAGE_CHILD, () -> new UsageTracker(policyRef), poolSize)
            .threads(threads)
            .maxConcurrent(maxConcurrent)
            .quotaKeysExtractor(task -> {
                if (task instanceof WifiSupervisorContext ctx && ctx.msisdn != null) {
                    return QuotaKeys.of(ctx.msisdn, ctx.msisdn + "@" + ctx.zone);
                }
                return QuotaKeys.NONE; // anonymous birth — binds at first login
            })
            .quotaLimits(new QuotaLimits(p.maxDevicesPerUser(), p.maxDevicesPerUserPerZone(), 0, 0))
            .build();

        long evictSec = p.userDormantEvictSec();
        this.userIndex = Registry.builder("wifi-user-index")
            .supervisor("UserIndex", () -> new IndexMachine(evictSec), poolSize)
            .threads(1)
            .createFromFirstEvent(this::userIndexContextFor)
            .build();
        this.zoneIndex = Registry.builder("wifi-zone-index")
            .supervisor("ZoneIndex", () -> new IndexMachine(evictSec), Math.max(64, poolSize / 8))
            .threads(1)
            .createFromFirstEvent(this::zoneIndexContextFor)
            .build();
    }

    /** Rebirth-with-memory: a fresh user index starts from the read-model, so eviction loses nothing. */
    private Object userIndexContextFor(StatemachineEvent first) {
        IndexContext ctx = new IndexContext();
        ctx.key = ((MembershipEvt) first).key();
        for (String mac : macsByUser.getOrDefault(ctx.key, Set.of())) {
            SessionMeta m = metaByMac.get(mac);
            if (m != null) ctx.members.put(mac,
                new IndexContext.MemberRow(m.sessionId(), m.mac(), m.msisdn(), m.state()));
        }
        return ctx;
    }

    private Object zoneIndexContextFor(StatemachineEvent first) {
        IndexContext ctx = new IndexContext();
        ctx.key = ((MembershipEvt) first).key();
        for (SessionMeta m : metaByMac.values()) {
            if (ctx.key.equals(m.zone())) ctx.members.put(m.mac(),
                new IndexContext.MemberRow(m.sessionId(), m.mac(), m.msisdn(), m.state()));
        }
        return ctx;
    }

    // ─────────────────────────────────────────────────────────────────
    // Inbox — gateway side
    // ─────────────────────────────────────────────────────────────────

    /** First sighting of a MAC. Returns the session id, or null when admission refused it. */
    public String deviceSeen(String mac, String vlan) {
        String norm = normalize(mac);
        String existing = liveSessionId(norm);
        if (existing != null) return existing;

        WifiSupervisorContext ctx = new WifiSupervisorContext();
        ctx.sessionId = norm.replace(":", "") + "-" + (System.currentTimeMillis() / 1000);
        ctx.mac = norm;
        ctx.vlan = vlan;
        ctx.firstSeenMs = System.currentTimeMillis();
        ctx.operator = identity.operator();
        ctx.gwId = identity.gwId();
        ctx.msisdn = ports.loginStore().lookup(norm); // returning device binds at birth
        ZoneResolverPort.SiteZone sz = ports.zoneResolver().resolve(vlan);
        if (sz != null) {
            ctx.site = sz.siteId();
            ctx.zone = sz.zoneKey();
        }
        DispatchResult r = sessions.dispatch(ctx.sessionId, ctx);
        if (!r.accepted()) {
            LOG.warn("[ENGINE] birth refused mac={} cause={}", norm, r.rejectCause());
            return null;
        }
        awaitRegistered(ctx.sessionId);
        liveSessionByMac.put(norm, ctx.sessionId);
        return ctx.sessionId;
    }

    public void captiveProbe(String mac, String ip, String probeLabel) {
        String id = liveOrBirth(mac);
        if (id != null) deliver(id, mac, new WifiEvents.CaptiveProbe(normalize(mac), ip, probeLabel));
    }

    public void portalOpened(String mac) { deliverLive(mac, new WifiEvents.PortalOpened(normalize(mac))); }

    public void otpSent(String mac, String msisdn) {
        deliverLive(mac, new WifiEvents.OtpSent(normalize(mac), normalizeMsisdn(msisdn)));
    }

    public void otpVerified(String mac, String msisdn) {
        deliverLive(mac, new WifiEvents.OtpVerified(normalize(mac), normalizeMsisdn(msisdn)));
    }

    public void paymentInitiated(String mac, String purchaseId) {
        deliverLive(mac, new WifiEvents.PaymentInitiated(normalize(mac), purchaseId));
    }

    public void paymentSettled(String mac, String purchaseId) {
        deliverLive(mac, new WifiEvents.PaymentSettled(normalize(mac), purchaseId));
    }

    /** SHADOW path: an external authority granted this MAC. */
    public void grantObserved(String mac) {
        String id = liveOrBirth(mac);
        if (id != null) deliver(id, mac, new WifiEvents.GrantObserved(normalize(mac)));
    }

    public void grantRevoked(String mac) { deliverLive(mac, new WifiEvents.GrantRevoked(normalize(mac))); }

    public void deviceGone(String mac) { deliverLive(mac, new WifiEvents.DeviceGone(normalize(mac))); }

    public void coaDisconnect(String mac) { deliverLive(mac, new WifiEvents.CoaDisconnect(normalize(mac))); }

    public void adminKick(String mac, String reason) {
        deliverLive(mac, new WifiEvents.AdminKick(normalize(mac), reason));
    }

    /** Ledger batch in (money — call for live AND replayed batches alike). */
    public void usageBatch(List<WifiEvents.CountersDelta> rows) {
        for (WifiEvents.CountersDelta d : rows) {
            deliverLive(d.mac(), d);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Admission pipeline — the ONE enforcement door
    // ─────────────────────────────────────────────────────────────────

    /**
     * Grant internet to a signed-in device. Steps: device-count caps →
     * zone-scope rule → quota re-key (the statewalk base extension; stub
     * until it lands) → durable login bind → GrantApproved into the machine.
     */
    public GrantResult grantSession(String mac, String msisdn, int minutes, long volumeBytes,
                                    String purchaseId) {
        String norm = normalize(mac);
        String user = normalizeMsisdn(msisdn);
        String id = liveSessionId(norm);
        if (id == null) return GrantResult.denied("no-session", null);
        SessionMeta meta = metaByMac.get(norm);
        String zone = meta != null ? meta.zone() : null;
        SessionPolicy p = policyRef.get();

        Set<String> owned = macsByUser.getOrDefault(user, Set.of());
        // step 1 — the zone-scope SET rule (counting quotas cannot express it)
        if ("single_zone".equals(p.zoneScope()) && zone != null) {
            boolean otherZoneLive = owned.stream()
                .map(metaByMac::get)
                .filter(m -> m != null && m.zone() != null)
                .anyMatch(m -> !zone.equals(m.zone()));
            if (otherZoneLive) return GrantResult.denied("zone-scope", new ArrayList<>(owned));
        }
        // step 2 — the COUNTS, enforced by the base QuotaController (both
        // dimensions in one atomic decision; idempotent for a top-up)
        String rebindReject = rebindPort().rebind(id, user, user + "@" + zone);
        if (rebindReject != null) return GrantResult.denied(rebindReject, new ArrayList<>(owned));

        ports.loginStore().bind(norm, user);
        deliver(id, norm, new WifiEvents.GrantApproved(norm, user, minutes, volumeBytes, purchaseId));
        return GrantResult.ok();
    }

    /** Test override, or the REAL thing: the statewalk base extension rebindQuotaKeys. */
    private QuotaRebindPort rebindPort() {
        if (ports.quotaRebind() != null) return ports.quotaRebind();
        return (sessionId, msisdn, zoneRouteKey) -> {
            try {
                RejectCause r = sessions.rebindQuotaKeys(sessionId, QuotaKeys.of(msisdn, zoneRouteKey));
                if (r == null) return null;
                return switch (r) {
                    case PARTNER_CONCURRENCY_EXCEEDED -> "device-limit";
                    case ROUTE_CONCURRENCY_EXCEEDED -> "zone-device-limit";
                    default -> r.name().toLowerCase();
                };
            } catch (IllegalStateException gone) {
                return "no-session"; // raced a teardown — next sighting starts clean
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────
    // Liveness probe — present tense only
    // ─────────────────────────────────────────────────────────────────

    /** One probe pass; also callable directly by tests. */
    public void livenessTick() {
        LivenessSnapshotPort.LivenessSnapshot snap;
        try {
            snap = ports.liveness().readAll();
        } catch (RuntimeException e) {
            warnStale("liveness read failed: " + e);
            return;
        }
        if (snap == null) return;
        long ageMs = System.currentTimeMillis() - snap.snapshotMs();
        if (ageMs > policyRef.get().livenessStaleSec() * 1000L) {
            warnStale("liveness snapshot stale (" + ageMs + " ms) — no idle decisions, sessions kept");
            return;
        }
        for (Map.Entry<String, SessionMeta> e : metaByMac.entrySet()) {
            if (!"ESTABLISHED".equals(e.getValue().state())) continue;
            Long lastActive = snap.lastActiveByMac().get(e.getKey());
            if (lastActive == null) continue;
            deliverLive(e.getKey(), new WifiEvents.LivenessTick(e.getKey(), lastActive, snap.snapshotMs()));
        }
    }

    public void startLivenessProbe(long periodSec) {
        if (livenessStarted) return;
        livenessStarted = true;
        clock.scheduleWithFixedDelay(() -> {
            try {
                livenessTick();
            } catch (Throwable t) {
                LOG.error("[ENGINE] liveness probe tick failed", t);
            }
        }, periodSec, periodSec, TimeUnit.SECONDS);
    }

    private void warnStale(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastStaleWarnMs > 60_000) { // alarm once a minute, not per tick
            lastStaleWarnMs = now;
            LOG.warn("[ENGINE] PIPELINE-STALE: {}", msg);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Membership fan-out (the one writer of the aggregate world)
    // ─────────────────────────────────────────────────────────────────

    private void onMembership(MembershipChange c) {
        if ("CLOSED".equals(c.kind())) {
            metaByMac.computeIfPresent(c.mac(),
                (k, m) -> m.sessionId().equals(c.sessionId()) ? null : m);
            liveSessionByMac.remove(c.mac(), c.sessionId());
            if (c.msisdn() != null) {
                macsByUser.computeIfPresent(c.msisdn(), (k, set) -> {
                    set.remove(c.mac());
                    return set.isEmpty() ? null : set;
                });
            }
        } else {
            metaByMac.put(c.mac(),
                new SessionMeta(c.sessionId(), c.mac(), c.msisdn(), c.zone(), c.site(), c.gwId(), c.state()));
            if (c.msisdn() != null) {
                macsByUser.computeIfAbsent(c.msisdn(), k -> ConcurrentHashMap.newKeySet()).add(c.mac());
            }
        }
        if (c.msisdn() != null) {
            userIndex.onInboundEvent(c.msisdn(), new MembershipEvt(c.msisdn(), c));
        }
        if (c.zone() != null) {
            zoneIndex.onInboundEvent(c.zone(), new MembershipEvt(c.zone(), c));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Queries (v1: read-model; index machines are the event-sourced views)
    // ─────────────────────────────────────────────────────────────────

    public List<SessionMeta> userView(String msisdn) {
        return macsByUser.getOrDefault(normalizeMsisdn(msisdn), Set.of()).stream()
            .map(metaByMac::get).filter(java.util.Objects::nonNull).toList();
    }

    public List<SessionMeta> zoneView(String zone) {
        return metaByMac.values().stream().filter(m -> zone.equals(m.zone())).toList();
    }

    public SessionMeta sessionOf(String mac) { return metaByMac.get(normalize(mac)); }

    public void applyPolicy(SessionPolicy p) { policyRef.set(p); }

    // ─────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────

    private String liveOrBirth(String mac) {
        String norm = normalize(mac);
        String id = liveSessionId(norm);
        return id != null ? id : deviceSeen(norm, null);
    }

    private void deliverLive(String mac, StatemachineEvent event) {
        String id = liveSessionId(normalize(mac));
        if (id != null) deliver(id, normalize(mac), event);
    }

    private String liveSessionId(String mac) {
        String id = liveSessionByMac.get(mac);
        if (id == null) return null;
        if (!sessions.hasAny(id)) {
            liveSessionByMac.remove(mac, id);
            return null;
        }
        return id;
    }

    private void deliver(String id, String mac, StatemachineEvent event) {
        try {
            sessions.onInboundEvent(id, event);
        } catch (RuntimeException e) {
            LOG.warn("[ENGINE] delivery raced teardown id={} event={} ({})",
                id, event.getClass().getSimpleName(), e.getMessage());
            liveSessionByMac.remove(mac, id);
        }
    }

    private void awaitRegistered(String id) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (!sessions.hasAny(id) && System.nanoTime() < deadline) {
            try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
        if (!sessions.hasAny(id)) LOG.warn("[ENGINE] dispatch not registered in 500ms id={}", id);
    }

    private static String normalize(String mac) { return mac == null ? null : mac.trim().toLowerCase(); }

    private static String normalizeMsisdn(String msisdn) {
        if (msisdn == null) return null;
        String d = msisdn.replaceAll("[^0-9]", "");
        if (d.startsWith("880")) return d;
        if (d.startsWith("0")) return "88" + d;
        return "880" + d;
    }

    @Override
    public void close() {
        clock.shutdownNow();
        sessions.shutdown();
        userIndex.shutdown();
        zoneIndex.shutdown();
    }
}
