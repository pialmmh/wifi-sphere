package com.telcobright.wifisphere.context;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * Read-only view of the cached gateway context — consumed by ops, the boot chain
 * (vpp-genconfig can read /context/doc locally instead of calling the BSS itself),
 * and the admin session dashboard.
 */
@Path("/context")
@Produces(MediaType.APPLICATION_JSON)
public class ContextResource {

    @Inject
    ContextCache cache;

    @GET
    @Path("/version")
    public Map<String, Object> version() {
        return Map.of(
                "gateway", cache.gatewayId(),
                "version", cache.version(),
                "source", cache.source());
    }

    @GET
    @Path("/doc")
    public JsonNode doc() {
        return cache.document();
    }

    /** The join VPP cannot do: resolve a VLAN id to its site (+zone). 404 = unknown vlan. */
    @GET
    @Path("/site-by-vlan/{vlanId}")
    public jakarta.ws.rs.core.Response siteByVlan(@jakarta.ws.rs.PathParam("vlanId") int vlanId) {
        ContextSnapshot.SiteCtx s = cache.snapshot().siteByVlan(vlanId);
        return s == null
                ? jakarta.ws.rs.core.Response.status(404).entity(Map.of("error", "no site for vlan " + vlanId)).build()
                : jakarta.ws.rs.core.Response.ok(s).build();
    }
}
