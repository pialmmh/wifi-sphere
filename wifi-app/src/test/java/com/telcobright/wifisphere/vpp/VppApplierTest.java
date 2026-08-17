package com.telcobright.wifisphere.vpp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VppApplierTest {

    @Test
    void dumpParsesThePilotTableShape() throws Exception {
        String dump = """
            default: garden; report-interval 0s; 3 MACs
            garden-allow prefixes:
              10.10.1.2/32
                    mac           state    pol-up   pol-dn      permit
             4e:3e:2b:ae:07:93   release      -        -     10479/3518652
             9c:ce:88:17:10:46   garden       -        -         0/0
             aa:bb:cc:dd:ee:ff   blacklist    -        -         0/0
            """;
        VppApplier a = new VppApplier(true, args -> dump);
        Map<String, String> t = a.dumpDispositions();
        assertEquals(3, t.size());
        assertEquals("release", t.get("4e:3e:2b:ae:07:93"));
        assertEquals("garden", t.get("9c:ce:88:17:10:46"));
        assertEquals("blacklist", t.get("aa:bb:cc:dd:ee:ff"));
    }

    @Test
    void applyFailureIsContainedNotThrown() {
        VppApplier a = new VppApplier(true, args -> { throw new IllegalStateException("vpp down"); });
        assertDoesNotThrow(() -> a.release("aa:bb:cc:dd:ee:01"));   // drift sweep repairs later
    }
}
