package com.telcobright.routesphere.wifimachine.v2.port;

/** Where finished sessions go. Exactly one record per session leg, from final-state entry only. */
@FunctionalInterface
public interface SdrPort {

    void write(WifiSdrRecord record);
}
