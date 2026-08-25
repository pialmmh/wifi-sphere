package com.telcobright.routesphere.wifimachine.v2.index;

import com.telcobright.routesphere.wifimachine.v2.port.MembershipChange;
import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

/**
 * One membership fact addressed to one index machine ({@code key} = the
 * msisdn for the user index, the zoneKey for the zone index). isFirst so
 * the first fact for a new key births the index machine (registry
 * first-event spawn — index machines are never cell children).
 */
public record MembershipEvt(String key, MembershipChange change) implements StatemachineEvent {
    @Override public boolean isFirst() { return true; }
}
