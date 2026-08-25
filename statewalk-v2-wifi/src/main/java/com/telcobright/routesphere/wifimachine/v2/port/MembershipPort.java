package com.telcobright.routesphere.wifimachine.v2.port;

/**
 * Fan-out door from session machines to the user/zone index views. The one
 * writer of the aggregate world; per-session ordering is free (all calls
 * for one session run on that cell's chain).
 */
@FunctionalInterface
public interface MembershipPort {

    void onMembership(MembershipChange change);
}
