package com.telcobright.routesphere.wifimachine.v2.index;

import com.telcobright.routesphere.wifimachine.v2.port.MembershipChange;
import com.telcobright.statewalk.v2.flat.InternalEventResolver;
import com.telcobright.statewalk.v2.flat.Supervisor;
import com.telcobright.statewalk.v2.state.StateMap;

import java.util.concurrent.TimeUnit;


/**
 * ONE class, indexed twice — "same machine, just indexed": an instance per
 * user (key = msisdn) in the user-index registry and an instance per zone
 * (key = zoneKey) in the zone-index registry. Born lazily by its first
 * membership event; display/query only — enforcement lives in the
 * admission pipeline, never here.
 */
public final class IndexMachine extends Supervisor<IndexContext> {

    private final long dormantEvictSec;

    public IndexMachine(long dormantEvictSec) {
        this.dormantEvictSec = dormantEvictSec;
    }

    @Override
    protected void defineRoutes(InternalEventResolver r) {
        r.selfHandle(MembershipEvt.class);
    }

    @Override
    protected StateMap defineStates() {
        return StateMap.builder()
            .initialState("ACTIVE")
            .state("ACTIVE")
                .interim()
                // base law: every state has a clock landing in a final state. The evict
                // clock runs from BIRTH (stay events never reset it); the next membership
                // event REBIRTHS the index and the engine prefills the fresh context from
                // its read-model — eviction is lossless for the member view.
                .timeout(dormantEvictSec, TimeUnit.SECONDS, "DORMANT")
                .stay(MembershipEvt.class, (self, e) ->
                    ((IndexMachine) self).apply(((MembershipEvt) e).change()))
            .state("DORMANT")
                .finalState()
                .timeout(1, TimeUnit.SECONDS, "DORMANT")
            .build();
    }

    @Override
    protected IndexContext createContext() { return new IndexContext(); }

    private void apply(MembershipChange c) {
        IndexContext ctx = getContext();
        switch (c.kind()) {
            case "SEEN" -> {
                ctx.seenTotal++;
                ctx.members.put(c.mac(),
                    new IndexContext.MemberRow(c.sessionId(), c.mac(), c.msisdn(), c.state()));
            }
            case "ESTABLISHED" -> {
                ctx.establishedTotal++;
                ctx.members.put(c.mac(),
                    new IndexContext.MemberRow(c.sessionId(), c.mac(), c.msisdn(), c.state()));
            }
            case "CLOSED" -> {
                ctx.closedTotal++;
                IndexContext.MemberRow row = ctx.members.get(c.mac());
                if (row != null && row.sessionId().equals(c.sessionId())) ctx.members.remove(c.mac());
            }
            default -> { /* future kinds are growth, not errors */ }
        }
    }
}
