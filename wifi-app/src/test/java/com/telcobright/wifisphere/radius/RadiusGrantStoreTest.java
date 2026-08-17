package com.telcobright.wifisphere.radius;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RadiusGrantStoreTest {

    @Test
    void grantIsConsumedExactlyOnce() {
        RadiusGrantStore store = new RadiusGrantStore();
        store.stage("AA:BB:CC:DD:EE:02", 420, "paid");

        RadiusGrantStore.Grant g = store.consume("aa:bb:cc:dd:ee:02");   // case-insensitive
        assertNotNull(g);
        assertEquals(420, g.seconds());
        assertEquals("paid", g.tier());

        assertNull(store.consume("aa:bb:cc:dd:ee:02"), "second consume must find nothing");
    }

    @Test
    void unknownMacAnswersNull() {
        assertNull(new RadiusGrantStore().consume("00:00:00:00:00:00"));
    }
}
