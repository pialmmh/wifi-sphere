package com.telcobright.wifisphere.auth;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * The authority's HTTP face — feeds the admin session dashboard and gives ops a kick button.
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject
    MacAuthStore store;

    @GET
    @Path("/macs")
    public Map<String, Object> macs() {
        List<AuthState> all = store.all();
        long now = System.currentTimeMillis() / 1000;
        return Map.of(
                "count", all.size(),
                "macs", all.stream().map(s -> Map.of(
                        "mac", s.mac(),
                        "disposition", s.disposition().name(),
                        "tier", s.tier() == null ? "" : s.tier(),
                        "vlanId", s.vlanId() == null ? -1 : s.vlanId(),
                        "siteId", s.siteId() == null ? "" : s.siteId(),
                        "msisdn", s.msisdn() == null ? "" : s.msisdn(),
                        "remainingSeconds", s.deadlineEpochSec() == 0 ? -1
                                : Math.max(0, s.deadlineEpochSec() - now))).toList());
    }

    @POST
    @Path("/kick")
    public Response kick(Map<String, Object> body) {
        String mac = String.valueOf(body.getOrDefault("mac", ""));
        if (mac.isBlank()) return Response.status(400).entity(Map.of("error", "mac required")).build();
        AuthState s = store.kick(mac, String.valueOf(body.getOrDefault("reason", "api")));
        return s == null
                ? Response.status(404).entity(Map.of("error", "unknown mac")).build()
                : Response.ok(Map.of("ok", true, "mac", s.mac(), "disposition", s.disposition().name())).build();
    }
}
