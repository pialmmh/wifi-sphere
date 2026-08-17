package com.telcobright.wifisphere.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContextLoaderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unreachableUrlFallsBackToLastGoodCache(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("desired-state.json");
        Files.writeString(cache, "{\"version\": 7, \"gateway\": \"bras-1\", \"sites\": []}");
        ContextLoader l = new ContextLoader("bras-1", "http://127.0.0.1:1/nope", "",
                cache.toString(), mapper);
        l.load();
        assertEquals(7, l.version());
        assertEquals("cache", l.source());
        assertEquals("bras-1", l.document().path("gateway").asText());
    }

    @Test
    void noUrlNoCacheStartsEmptyButAlive(@TempDir Path dir) {
        ContextLoader l = new ContextLoader("bras-1", "", "",
                dir.resolve("absent.json").toString(), mapper);
        l.load();
        assertEquals(0, l.version());
        assertEquals("none", l.source());
        assertNotNull(l.document());
    }

    @Test
    void corruptCacheDoesNotKillTheLoader(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("desired-state.json");
        Files.writeString(cache, "{not json at all");
        ContextLoader l = new ContextLoader("bras-1", "", "", cache.toString(), mapper);
        assertDoesNotThrow(l::load);
        assertEquals(0, l.version());
        assertEquals("none", l.source());
    }
}
