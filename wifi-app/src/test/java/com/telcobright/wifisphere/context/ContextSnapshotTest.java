package com.telcobright.wifisphere.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextSnapshotTest {

    private static final String DOC = """
        { "version": 3, "gateway": "bras-1",
          "tenant": { "tenantId": 1, "name": "btcl" },
          "sites": [
            { "siteId": "moghbazar", "vlan": 101, "subnet": "10.193.96.0/20", "gwIp": "10.193.96.1",
              "zone": { "division": "dhaka", "zilla": "dhaka", "area": "moghbazar" },
              "dhcp": { "rangeStart": "10.193.96.10", "rangeEnd": "10.193.111.250",
                        "dns": ["8.8.8.8"], "leaseSec": 3600 } },
            { "siteId": "khilgaon", "vlanId": 102, "subnet": "10.193.112.0/20", "gwIp": "10.193.112.1",
              "zone": { "division": "dhaka", "zilla": "dhaka", "area": "khilgaon" } },
            { "siteId": "ramna", "vlan": null, "subnet": "10.193.128.0/20", "gwIp": "10.193.128.1",
              "zone": { "division": "dhaka", "zilla": "dhaka", "area": "ramna" } }
          ] }""";

    @Test
    void vlanTableIsTheJoinVppCannotDo() throws Exception {
        ContextSnapshot ctx = ContextSnapshot.from(new ObjectMapper().readTree(DOC));

        assertEquals(3, ctx.version());
        assertEquals(1, ctx.tenantId());
        assertEquals("btcl", ctx.tenantName());

        // vlan 101 (spelled "vlan") and 102 (spelled "vlanId") both resolve
        assertEquals("moghbazar", ctx.siteByVlan(101).siteId());
        assertEquals("dhaka/dhaka/khilgaon", ctx.siteByVlan(102).zone().key());

        // ramna has no VLAN assigned yet: business-visible, absent from the fast-path join
        assertEquals(3, ctx.sites().size());
        assertEquals(2, ctx.vlanTable().size());
        assertNotNull(ctx.siteById("ramna"));
        assertNull(ctx.siteByVlan(999));

        // dhcp binds when present, stays null when the row has none
        assertEquals("10.193.96.10", ctx.siteByVlan(101).dhcp().rangeStart());
        assertNull(ctx.siteByVlan(102).dhcp());

        // zone view: zoneKey -> siteId -> SiteCtx, O(1) both hops
        assertEquals("10.193.128.0/20", ctx.zones().get("dhaka/dhaka/ramna").get("ramna").subnet());
    }

    @Test
    void emptyDocumentYieldsEmptyButUsableSnapshot() throws Exception {
        ContextSnapshot ctx = ContextSnapshot.from(new ObjectMapper().readTree("{}"));
        assertEquals(0, ctx.version());
        assertEquals(1, ctx.tenantId());   // btcl default per ruling
        assertTrue(ctx.sites().isEmpty());
        assertNull(ctx.siteByVlan(101));
    }
}
