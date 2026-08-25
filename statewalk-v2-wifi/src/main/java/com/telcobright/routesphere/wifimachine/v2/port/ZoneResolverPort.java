package com.telcobright.routesphere.wifimachine.v2.port;

/** Resolves where a device physically is, from its VLAN, at birth. */
public interface ZoneResolverPort {

    record SiteZone(String siteId, String zoneKey) {}

    SiteZone resolve(String vlan);
}
