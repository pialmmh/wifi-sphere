package com.telcobright.routesphere.wifimachine.v2.glue;

import java.util.List;

/** Outcome of the admission pipeline for one grant attempt. */
public record GrantResult(boolean granted, String cause, List<String> kickList) {

    public static GrantResult ok() { return new GrantResult(true, null, List.of()); }

    public static GrantResult denied(String cause, List<String> kickList) {
        return new GrantResult(false, cause, kickList == null ? List.of() : kickList);
    }
}
