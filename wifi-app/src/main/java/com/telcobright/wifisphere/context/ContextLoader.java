package com.telcobright.wifisphere.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Fetch-with-fallback for the versioned gateway-config document (plain class — unit-testable
 * without CDI). The fallback chain mirrors the boot-time vpp-genconfig contract:
 * fetch ok → cache to disk + serve; fetch fails → serve last-good disk copy; nothing → empty
 * document, version 0 (the service still starts; consumers see source="none").
 */
final class ContextLoader {

    private static final Logger log = LoggerFactory.getLogger(ContextLoader.class);

    private final String gatewayId;
    private final String configUrl;     // "" = no remote (cache/off-line mode)
    private final String tokenFile;     // bearer token, root-only file; "" = no auth header
    private final Path cacheFile;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private volatile JsonNode doc;
    private volatile ContextSnapshot snapshot;
    private volatile long version = 0;
    private volatile String source = "none";

    ContextLoader(String gatewayId, String configUrl, String tokenFile, String cacheFile, ObjectMapper mapper) {
        this.gatewayId = gatewayId;
        this.configUrl = configUrl == null ? "" : configUrl;
        this.tokenFile = tokenFile == null ? "" : tokenFile;
        this.cacheFile = Path.of(cacheFile);
        this.mapper = mapper;
        this.doc = mapper.createObjectNode();
        this.snapshot = ContextSnapshot.from(this.doc);
    }

    synchronized void load() {
        if (!configUrl.isBlank()) {
            try {
                JsonNode fresh = fetch();
                long v = fresh.path("version").asLong(-1);
                if (v < 0) throw new IOException("document has no integer 'version'");
                writeCache(fresh);
                if (v != version) log.info("context updated: version {} -> {} (fetched)", version, v);
                doc = fresh;
                snapshot = ContextSnapshot.from(fresh);
                version = v;
                source = "fetched";
                return;
            } catch (Exception e) {
                log.warn("context fetch failed ({}) — falling back to last-good cache", e.getMessage());
            }
        }
        if (!"fetched".equals(source)) loadCache();
    }

    private JsonNode fetch() throws IOException, InterruptedException {
        HttpRequest.Builder rq = HttpRequest.newBuilder()
                .uri(URI.create(configUrl + "?gw=" + gatewayId))
                .timeout(Duration.ofSeconds(10)).GET();
        if (!tokenFile.isBlank() && Files.exists(Path.of(tokenFile)))
            rq.header("Authorization", "Bearer " + Files.readString(Path.of(tokenFile)).trim());
        HttpResponse<String> rs = http.send(rq.build(), HttpResponse.BodyHandlers.ofString());
        if (rs.statusCode() != 200) throw new IOException("HTTP " + rs.statusCode());
        return mapper.readTree(rs.body());
    }

    private void loadCache() {
        try {
            if (Files.exists(cacheFile)) {
                JsonNode cached = mapper.readTree(Files.readString(cacheFile));
                doc = cached;
                snapshot = ContextSnapshot.from(cached);
                version = cached.path("version").asLong(0);
                source = "cache";
                log.info("context served from last-good cache: version {}", version);
            }
        } catch (Exception e) {
            log.error("last-good cache unreadable ({}); starting with empty context", e.getMessage());
        }
    }

    private void writeCache(JsonNode fresh) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".new");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fresh));
            Files.move(tmp, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("could not persist last-good cache: {}", e.getMessage());
        }
    }

    JsonNode document() { return doc; }
    ContextSnapshot snapshot() { return snapshot; }
    long version() { return version; }
    String gatewayId() { return gatewayId; }
    String source() { return source; }
}
