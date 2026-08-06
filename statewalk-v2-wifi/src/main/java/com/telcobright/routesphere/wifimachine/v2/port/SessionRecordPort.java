package com.telcobright.routesphere.wifimachine.v2.port;

/**
 * Where finished sessions go — the data-session CDR sink. Exactly one record
 * per session, written from a final state's entry and nowhere else.
 */
@FunctionalInterface
public interface SessionRecordPort {

    void record(WifiSessionRecord record);
}
