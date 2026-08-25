package com.telcobright.routesphere.wifimachine.v2.port;

/**
 * The first-login identity bind: swap a session's quota keys from anonymous
 * to (user, user@zone) atomically in the registry's QuotaController.
 *
 * <p>The REAL implementation is the statewalk base extension
 * (Registry.rebindQuotaKeys — stream-e package 1). Until it lands, the
 * engine runs with an allow-all stub and the admission pipeline's own
 * read-model checks enforce the limits.
 *
 * @return null when the bind succeeded; a short cause string on reject.
 */
@FunctionalInterface
public interface QuotaRebindPort {

    String rebind(String sessionId, String msisdn, String zoneRouteKey);

    static QuotaRebindPort allowAll() { return (s, m, z) -> null; }
}
