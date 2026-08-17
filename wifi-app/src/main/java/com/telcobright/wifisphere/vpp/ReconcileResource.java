package com.telcobright.wifisphere.vpp;

import com.telcobright.wifisphere.context.ContextCache;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.Set;

/**
 * Ops face of the site reconciler. GET /reconcile/plan is always safe (pure diff, no writes) —
 * that is how the plan is reviewed on a box before any VLAN cutover. POST /reconcile/apply
 * creates the missing sites and only when the applier is enabled (shadow = logged no-op).
 */
@Path("/reconcile")
@Produces(MediaType.APPLICATION_JSON)
public class ReconcileResource {

    @Inject
    ContextCache context;

    @Inject
    SiteReconciler reconciler;

    @GET
    @Path("/plan")
    public Response plan() {
        try {
            Set<Integer> actual = reconciler.actualVlans();
            SiteReconciler.Plan p = reconciler.plan(context.snapshot(), actual);
            return Response.ok(Map.of(
                    "contextVersion", context.version(),
                    "actualVlans", actual,
                    "create", p.create(),
                    "present", p.present(),
                    "orphaned", p.orphaned(),
                    "skippedNoVlan", p.skippedNoVlan())).build();
        } catch (Exception e) {
            return Response.status(503).entity(Map.of("error", "data plane unreadable: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/apply")
    public Response apply() {
        try {
            SiteReconciler.Plan p = reconciler.plan(context.snapshot(), reconciler.actualVlans());
            int applied = reconciler.apply(p);
            return Response.ok(Map.of("applied", applied,
                    "shadow", applied == 0 && !p.create().isEmpty(),
                    "orphaned", p.orphaned())).build();
        } catch (Exception e) {
            return Response.status(503).entity(Map.of("error", e.getMessage())).build();
        }
    }
}
