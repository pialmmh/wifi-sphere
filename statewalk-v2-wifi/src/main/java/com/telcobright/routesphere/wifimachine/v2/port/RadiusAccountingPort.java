package com.telcobright.routesphere.wifimachine.v2.port;

import com.telcobright.routesphere.wifimachine.v2.supervisor.WifiSupervisorContext;

/**
 * RADIUS accounting out. Implementations MUST be bounded (short timeouts)
 * — a slow RADIUS never blocks teardown (the EXPIRING grace clock wins).
 * Acct-Stop carries the true terminate cause (idle → Idle-Timeout).
 */
public interface RadiusAccountingPort {

    void acctStart(WifiSupervisorContext ctx);

    void acctStop(WifiSupervisorContext ctx, String cause);
}
