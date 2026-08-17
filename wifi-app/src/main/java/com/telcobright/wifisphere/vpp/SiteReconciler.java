package com.telcobright.wifisphere.vpp;

import com.telcobright.wifisphere.context.ContextSnapshot;
import com.telcobright.wifisphere.context.ContextSnapshot.SiteCtx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Build-order step 5: the per-site instance renderer/reconciler. Consumes the snapshot's
 * vlanTable (the vlan→site join) and produces the data-plane rows that make a site exist:
 *
 *   trunk subif (dot1q vlan, tag-rewrite pop 1, symmetric) → bridge domain → BVI (site gwIp)
 *
 * Conventions (fixed here, documented in the topology note):
 *   bridge-domain id = 1000 + vlanId       loopback instance = vlanId  (loop&lt;vlan&gt;)
 *
 * DIFF-APPLY, CREATE-ONLY (v1): only missing pieces are created; a site that disappeared from
 * the document is REPORTED, never torn down automatically (the drain rule — removal is a
 * human decision until sessions are tracked per site). DHCP wiring is deliberately absent
 * until the relay/Kea scope-selection lab proves the giaddr model (tracked TODO).
 *
 * Shadow-safe: {@link #plan} is pure (testable with no VPP anywhere); {@link #apply} goes
 * through the same enabled-gated applier as MAC pushes.
 */
public class SiteReconciler {

    /** One renderable site = the exact vppctl lines that create it. */
    public record SitePlan(String siteId, int vlanId, List<String> commands) { }

    /** The reconcile outcome: what to create, what already exists, what vanished. */
    public record Plan(List<SitePlan> create, List<String> present, List<String> orphaned,
                       List<String> skippedNoVlan) { }

    private static final Logger log = LoggerFactory.getLogger(SiteReconciler.class);

    private final String accessIface;
    private final VppApplier.CommandRunner runner;
    private final boolean enabled;

    public SiteReconciler(String accessIface, boolean enabled, VppApplier.CommandRunner runner) {
        this.accessIface = accessIface;
        this.enabled = enabled;
        this.runner = runner;
    }

    /** Pure: desired (snapshot) vs actual (existing subif vlans on the trunk) → the plan. */
    public Plan plan(ContextSnapshot ctx, Set<Integer> actualVlans) {
        List<SitePlan> create = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<Integer> desired = new TreeSet<>();

        for (SiteCtx s : ctx.sites().values()) {
            if (s.vlanId() == null) { skipped.add(s.siteId()); continue; }
            desired.add(s.vlanId());
            if (actualVlans.contains(s.vlanId())) {
                present.add(s.siteId());
            } else {
                create.add(new SitePlan(s.siteId(), s.vlanId(), renderSite(s)));
            }
        }
        List<String> orphaned = new ArrayList<>();
        for (int v : actualVlans) {
            if (!desired.contains(v)) orphaned.add("vlan " + v + " exists in the data plane but no site claims it");
        }
        return new Plan(create, present, orphaned, skipped);
    }

    /** The exact creation recipe for one site (the topology note's site-instance model). */
    List<String> renderSite(SiteCtx s) {
        int v = s.vlanId();
        int bd = 1000 + v;
        String subif = accessIface + "." + v;
        String gw = s.gwIp() + "/" + prefixLen(s.subnet());
        return List.of(
                "create sub-interfaces " + accessIface + " " + v,
                "set interface l2 tag-rewrite " + subif + " pop 1",
                "create loopback interface instance " + v,
                "set interface l2 bridge loop" + v + " " + bd + " bvi",
                "set interface ip address loop" + v + " " + gw,
                "set interface state loop" + v + " up",
                "set interface l2 bridge " + subif + " " + bd,
                "set interface state " + subif + " up");
    }

    /** Actual state: the dot1q subif vlans already present on the trunk. */
    public Set<Integer> actualVlans() throws Exception {
        Set<Integer> vlans = new TreeSet<>();
        String out = runner.run("show", "interface");
        for (String line : out.split("\n")) {
            String name = line.trim().split("\\s+")[0];
            if (name.startsWith(accessIface + ".")) {
                try { vlans.add(Integer.parseInt(name.substring(accessIface.length() + 1))); }
                catch (NumberFormatException ignored) { }
            }
        }
        return vlans;
    }

    /** Applies the create half of a plan through vppctl. Orphans are only ever logged. */
    public int apply(Plan plan) {
        if (!enabled) {
            plan.create().forEach(sp -> log.info("[shadow] would create site {} (vlan {}): {} cmds",
                    sp.siteId(), sp.vlanId(), sp.commands().size()));
            plan.orphaned().forEach(o -> log.warn("[shadow] {}", o));
            return 0;
        }
        int applied = 0;
        for (SitePlan sp : plan.create()) {
            try {
                for (String cmd : sp.commands()) runner.run(cmd.split("\\s+"));
                log.info("site created: {} (vlan {})", sp.siteId(), sp.vlanId());
                applied++;
            } catch (Exception e) {
                log.error("site create FAILED {} (vlan {}): {}", sp.siteId(), sp.vlanId(), e.getMessage());
            }
        }
        plan.orphaned().forEach(o -> log.warn("NOT touching: {} (drain rule — human decision)", o));
        return applied;
    }

    static int prefixLen(String cidr) {
        int i = cidr.indexOf('/');
        return i < 0 ? 32 : Integer.parseInt(cidr.substring(i + 1).trim());
    }

    public String accessIface() { return accessIface.toLowerCase(Locale.ROOT); }
}
