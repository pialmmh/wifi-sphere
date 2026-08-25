package com.telcobright.routesphere.wifimachine.v2.port;

/** The durable mac → msisdn memory that lets a returning device bind its user at birth. */
public interface LoginStorePort {

    /** @return the user's msisdn, or null if this MAC never logged in. */
    String lookup(String mac);

    void bind(String mac, String msisdn);
}
