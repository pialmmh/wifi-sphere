package com.telcobright.wifisphere;

import com.telcobright.seed.config.TenantConfigRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The 5-line seed-config wiring (see routesphere-seed docs/DESIGN.md): the
 * tenant/profile/channel tree becomes one injectable registry. A deployed box
 * overrides the baked-in tree with -Dseed.config.dir=/etc/wifi-sphere.
 */
@ApplicationScoped
public class SeedConfigProducer {

    @Produces
    @Singleton
    TenantConfigRegistry tenantConfig() {
        return TenantConfigRegistry.load();
    }
}
