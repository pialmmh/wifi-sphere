package com.telcobright.wifisphere.vpp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The incremental push half of the auth-authority ruling: wifi-sphere owns MAC auth state and
 * applies ONLY the lean enforcement projection to the data plane, one MAC at a time, as events
 * happen. No callback path exists by design — an unknown MAC's wire-speed answer is already
 * "garden" — so the data plane never asks anything.
 *
 * v1 speaks vppctl (same contract the pilot authorizer uses); the binary API can replace the
 * runner later without touching callers. SHADOW MODE (enabled=false) makes every apply a no-op
 * log line — the store still tracks state, nothing touches the data plane. That is how this
 * runs beside the pilot authorizer until cutover.
 */
public class VppApplier {

    /** Injectable for tests; production runner shells the configured vppctl. */
    @FunctionalInterface
    public interface CommandRunner {
        String run(String... args) throws Exception;
    }

    private static final Logger log = LoggerFactory.getLogger(VppApplier.class);

    private final boolean enabled;
    private final CommandRunner runner;

    public VppApplier(boolean enabled, CommandRunner runner) {
        this.enabled = enabled;
        this.runner = runner;
    }

    public boolean enabled() { return enabled; }

    public void release(String mac) { apply(mac, "release"); }

    public void garden(String mac) { apply(mac, "garden"); }

    public void forget(String mac) { apply(mac, "forget"); }

    private void apply(String mac, String disp) {
        if (!enabled) {
            log.info("[shadow] would apply: mac {} -> {}", mac, disp);
            return;
        }
        try {
            runner.run("mac-filter-wg", "mac", mac.toLowerCase(Locale.ROOT), disp);
            log.info("applied: mac {} -> {}", mac, disp);
        } catch (Exception e) {
            log.error("apply FAILED mac {} -> {} ({}) — drift until the sweep repairs it",
                    mac, disp, e.getMessage());
        }
    }

    /**
     * Anti-drift half: parse the data-plane table dump into mac -> disposition.
     * Dump line shape (pilot plugin): " aa:bb:cc:dd:ee:ff   release   - - ...".
     */
    public Map<String, String> dumpDispositions() throws Exception {
        Map<String, String> out = new HashMap<>();
        String dump = runner.run("show", "mac-filter-wg");
        for (String line : dump.split("\n")) {
            String[] c = line.trim().split("\\s+");
            if (c.length >= 2 && c[0].contains(":") && c[0].length() == 17
                    && (c[1].equals("release") || c[1].equals("garden") || c[1].equals("blacklist"))) {
                out.put(c[0].toLowerCase(Locale.ROOT), c[1]);
            }
        }
        return out;
    }
}
