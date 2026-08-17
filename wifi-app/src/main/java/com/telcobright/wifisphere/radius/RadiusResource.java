package com.telcobright.wifisphere.radius;

import com.telcobright.wifisphere.auth.MacAuthStore;
import com.telcobright.wifisphere.context.ContextCache;
import com.telcobright.wifisphere.context.ContextSnapshot;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * The RADIUS brain's HTTP face — wire-compatible with the authorizer's /radius/lookup shim, so
 * cutting FreeRADIUS over is ONE connect_uri line in the rest module.
 *
 *   POST /radius/grant  {"mac": "...", "seconds": 420, "tier": "paid"}   <- authorizer/pay-watcher
 *   GET  /radius/lookup?mac=...   -> rlm_rest attribute JSON | 404       <- FreeRADIUS
 */
@Path("/radius")
@Produces(MediaType.APPLICATION_JSON)
public class RadiusResource {

    @Inject
    RadiusGrantStore grants;

    @Inject
    MacAuthStore auth;

    @Inject
    ContextCache context;

    @POST
    @Path("/grant")
    public Response grant(Map<String, Object> body) {
        String mac = String.valueOf(body.getOrDefault("mac", ""));
        int seconds = (int) Double.parseDouble(String.valueOf(body.getOrDefault("seconds", "0")));
        if (mac.isBlank() || seconds <= 0)
            return Response.status(400).entity(Map.of("error", "mac and seconds>0 required")).build();
        String tier = String.valueOf(body.getOrDefault("tier", "paid"));
        grants.stage(mac, seconds, tier);
        // The authority records the session too (vlan resolved to site when known). While the
        // pilot authorizer still owns the box this is a SHADOW record; after cutover it IS the truth.
        Integer vlanId = body.get("vlanId") == null ? null
                : (int) Double.parseDouble(String.valueOf(body.get("vlanId")));
        ContextSnapshot.SiteCtx site = vlanId == null ? null : context.snapshot().siteByVlan(vlanId);
        auth.grant(mac, context.snapshot().tenantId(), vlanId,
                site == null ? null : site.siteId(), tier, seconds,
                body.get("msisdn") == null ? null : String.valueOf(body.get("msisdn")));
        return Response.ok(Map.of("ok", true, "mac", mac.toLowerCase(), "seconds", seconds)).build();
    }

    @GET
    @Path("/lookup")
    public Response lookup(@QueryParam("mac") String mac) {
        RadiusGrantStore.Grant g = grants.consume(mac);
        if (g == null) return Response.status(404).entity(Map.of()).build();
        return Response.ok(Map.of(
                "reply:Session-Timeout", Map.of("op", ":=", "value", new int[]{g.seconds()}),
                "reply:Reply-Message", Map.of("op", ":=", "value", new String[]{g.tier()})
        )).build();
    }
}
