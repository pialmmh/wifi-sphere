package com.telcobright.wifisphere.context;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The IMMUTABLE config context — lifecycle A. Built once per fetched gateway-config document and
 * swapped atomically; a re-fetch never touches session/binding state (lifecycle B).
 *
 * The shapes mirror the orchestrix wifi-admin entities (WifiNetDtos) verbatim — no parallel
 * schema is invented on the gateway side. The one index this class exists for:
 *
 *   vlanTable: vlanId -> SiteCtx      — the join VPP cannot do.
 *
 * VPP's world is the tuple (tenantId, vlanId, mac); everything business-facing (site, zone,
 * package) is resolved HERE. vlan null/absent = site not yet assigned a VLAN (BTCL pending);
 * such sites exist in {@link #sites()} but not in the vlan table.
 */
public final class ContextSnapshot {

    public record Zone(String division, String zilla, String area) {
        public String key() { return division + "/" + zilla + "/" + area; }
    }

    public record Dhcp(String rangeStart, String rangeEnd, List<String> dns, int leaseSec) { }

    public record SiteCtx(String siteId, Integer vlanId, String subnet, String gwIp,
                          Dhcp dhcp, Zone zone, String gatewayId) { }

    private final long version;
    private final int tenantId;
    private final String tenantName;
    private final Map<Integer, SiteCtx> vlanTable;
    private final Map<String, SiteCtx> sites;
    private final Map<String, List<String>> zones;   // "division/zilla/area" -> [siteId]
    private final JsonNode gateway;                  // throttle/boot/interfaces, raw doc section

    private ContextSnapshot(long version, int tenantId, String tenantName,
                            Map<Integer, SiteCtx> vlanTable, Map<String, SiteCtx> sites,
                            Map<String, List<String>> zones, JsonNode gateway) {
        this.version = version;
        this.tenantId = tenantId;
        this.tenantName = tenantName;
        this.vlanTable = Collections.unmodifiableMap(vlanTable);
        this.sites = Collections.unmodifiableMap(sites);
        this.zones = Collections.unmodifiableMap(zones);
        this.gateway = gateway;
    }

    /**
     * Build from a gateway-config document. Tolerates both field spellings in circulation:
     * the gateway's desired-state uses {@code vlan}, the wifi-admin DTOs use {@code vlanId}.
     */
    public static ContextSnapshot from(JsonNode doc) {
        long version = doc.path("version").asLong(0);
        int tenantId = doc.path("tenant").path("tenantId").asInt(1);
        String tenantName = doc.path("tenant").path("name").asText("btcl");

        Map<Integer, SiteCtx> vlanTable = new HashMap<>();
        Map<String, SiteCtx> sites = new HashMap<>();
        Map<String, List<String>> zones = new HashMap<>();

        for (JsonNode s : doc.path("sites")) {
            JsonNode z = s.path("zone");
            Zone zone = new Zone(z.path("division").asText(""), z.path("zilla").asText(""),
                    z.path("area").asText(""));
            JsonNode d = s.path("dhcp");
            List<String> dns = new ArrayList<>();
            d.path("dns").forEach(n -> dns.add(n.asText()));
            Dhcp dhcp = d.isMissingNode() || d.isNull() ? null
                    : new Dhcp(d.path("rangeStart").asText(""), d.path("rangeEnd").asText(""),
                               dns, d.path("leaseSec").asInt(3600));

            JsonNode vlanNode = s.has("vlanId") ? s.get("vlanId") : s.get("vlan");
            Integer vlanId = (vlanNode == null || vlanNode.isNull()) ? null : vlanNode.asInt();

            SiteCtx site = new SiteCtx(s.path("siteId").asText(""), vlanId,
                    s.path("subnet").asText(""), s.path("gwIp").asText(""), dhcp, zone,
                    s.path("gatewayId").asText(doc.path("gateway").asText("")));

            sites.put(site.siteId(), site);
            if (vlanId != null) vlanTable.put(vlanId, site);
            zones.computeIfAbsent(zone.key(), k -> new ArrayList<>()).add(site.siteId());
        }

        return new ContextSnapshot(version, tenantId, tenantName, vlanTable, sites, zones,
                doc.path("throttle").isMissingNode() ? doc.path("gatewaySettings") : doc);
    }

    /** The join VPP cannot do: the site a (tenant, vlan, mac) tuple belongs to; null = unknown vlan. */
    public SiteCtx siteByVlan(int vlanId) { return vlanTable.get(vlanId); }

    public SiteCtx siteById(String siteId) { return sites.get(siteId); }

    public long version() { return version; }
    public int tenantId() { return tenantId; }
    public String tenantName() { return tenantName; }
    public Map<Integer, SiteCtx> vlanTable() { return vlanTable; }
    public Map<String, SiteCtx> sites() { return sites; }
    public Map<String, List<String>> zones() { return zones; }
    public JsonNode gateway() { return gateway; }
}
