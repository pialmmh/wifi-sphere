package com.telcobright.wifisphere.vpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.wifisphere.context.ContextSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SiteReconcilerTest {

    private static final String DOC = """
        { "version": 5,
          "sites": [
            { "siteId": "moghbazar", "vlan": 101, "subnet": "10.193.96.0/20", "gwIp": "10.193.96.1",
              "zone": { "division": "dhaka", "zilla": "dhaka", "area": "moghbazar" } },
            { "siteId": "khilgaon", "vlanId": 102, "subnet": "10.193.112.0/20", "gwIp": "10.193.112.1",
              "zone": { "division": "dhaka", "zilla": "dhaka", "area": "khilgaon" } },
            { "siteId": "ramna", "vlan": null, "subnet": "10.193.128.0/20", "gwIp": "10.193.128.1",
              "zone": { "division": "dhaka", "zilla": "dhaka", "area": "ramna" } }
          ] }""";

    private ContextSnapshot ctx() throws Exception {
        return ContextSnapshot.from(new ObjectMapper().readTree(DOC));
    }

    @Test
    void planCreatesMissingKeepsPresentReportsOrphansAndVlanless() throws Exception {
        SiteReconciler r = new SiteReconciler("access", true, args -> "");
        // vlan 102 already exists; vlan 999 exists but no site claims it
        SiteReconciler.Plan p = r.plan(ctx(), Set.of(102, 999));

        assertEquals(1, p.create().size());
        assertEquals("moghbazar", p.create().get(0).siteId());
        assertEquals(List.of("khilgaon"), p.present());
        assertEquals(1, p.orphaned().size());
        assertTrue(p.orphaned().get(0).contains("999"));
        assertEquals(List.of("ramna"), p.skippedNoVlan());
    }

    @Test
    void siteRecipeMatchesTheTopologyModel() throws Exception {
        SiteReconciler r = new SiteReconciler("access", true, args -> "");
        List<String> cmds = r.plan(ctx(), Set.of()).create().stream()
                .filter(sp -> sp.siteId().equals("moghbazar")).findFirst().orElseThrow().commands();
        assertEquals(List.of(
                "create sub-interfaces access 101",
                "set interface l2 tag-rewrite access.101 pop 1",
                "create loopback interface instance 101",
                "set interface l2 bridge loop101 1101 bvi",
                "set interface ip address loop101 10.193.96.1/20",
                "set interface state loop101 up",
                "set interface l2 bridge access.101 1101",
                "set interface state access.101 up"), cmds);
    }

    @Test
    void actualVlansParsesShowInterface() throws Exception {
        String show = """
              Name               Idx    State  MTU
            access                2      up    9000
            access.101            5      up    0
            access.202            6      up    0
            loop101               7      up    9000
            wan                   1      up    9000
            """;
        SiteReconciler r = new SiteReconciler("access", true, args -> show);
        assertEquals(Set.of(101, 202), r.actualVlans());
    }

    @Test
    void shadowApplyRunsNothingButReportsPlan() throws Exception {
        List<String> ran = new ArrayList<>();
        SiteReconciler r = new SiteReconciler("access", false, args -> { ran.add("X"); return ""; });
        int applied = r.apply(r.plan(ctx(), Set.of()));
        assertEquals(0, applied);
        assertTrue(ran.isEmpty(), "shadow reconcile must not touch the data plane");
    }

    @Test
    void enabledApplyRunsEveryCommandOfEveryCreate() throws Exception {
        List<String> ran = new ArrayList<>();
        SiteReconciler r = new SiteReconciler("access", true,
                args -> { ran.add(String.join(" ", args)); return ""; });
        int applied = r.apply(r.plan(ctx(), Set.of()));
        assertEquals(2, applied);                 // moghbazar + khilgaon (ramna vlan-less)
        assertEquals(16, ran.size());             // 8 cmds each
        assertTrue(ran.contains("create sub-interfaces access 102"));
    }
}
