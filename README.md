# wifi-sphere

The carrier-WiFi product on the **routesphere-seed** framework. ONE Quarkus
instance (`wifi-app`) hosts the pillars as CDI beans; the same build compiles
to a JVM jar or a **GraalVM native binary** (Quarkus native) for the shipped
box. Design canon: the wifi repo's `docs/wifi-session-machine-design.md` +
routesphere-seed `docs/DESIGN.md`.

| Module | What |
|---|---|
| `statewalk-v2-wifi` | the session machines (INIT → SIGNALING → AUTHENTICATED \| REJECTED, + ENDED) — moved from the routesphere repo, coordinates kept |
| `statewalk-v2-wifi-channel` | the Redis bridge + ports (+ the standalone pilot runner, a dev convenience) |
| `wifi-app` | THE instance: seed-config producer + `WifiV2Registry` bean (flag `wifi.v2-enabled`, SHADOW mode) |

```bash
mvn clean install                          # machines tests + the Quarkus build
java -jar wifi-app/target/quarkus-app/quarkus-run.jar
# deployed boxes override config without rebuilding:
#   -Dseed.config.dir=/etc/wifi-sphere   (external tenant tree, file-by-file)
```

Config lives in the seed convention: `application.properties` (tenants +
active) and `config/tenants/btcl/dev/` (`profile-dev.yml`,
`channels/redis/redis-main.yml`).

Integration rule (standing): nothing talks to the machines from outside the
process except through the streams — `wifi:evt:gateway` in, `wifi:mac:*` +
`wifi:session:records` out.
