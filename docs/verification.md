# Verification evidence — 2026-11-14

Environment: macOS ARM64, Java 21.0.13.0.3, Maven 3.10.0, Spring Boot 3.5.16, Docker 29.8.2/OrbStack, PostgreSQL 17.12
(postgres:17-alpine). These are local correctness checks, not production capacity results.

## Executed

`JAVA_HOME=$(/usr/libexec/java_home -v 21) DOCKER_HOST=unix:///Users/MWGBH/.orbstack/run/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f ticket-booking-platform/pom.xml verify -q`

20 tests, zero failures/errors/skips: 19 PostgreSQL/Spring integration test invocations plus one token unit test. Final
reported PlatformTest duration was 9.187s, TokenTest 0.002s. These suite durations include setup and are not
latency/throughput benchmarks. Four Flyway migrations ran in Testcontainers. Surefire evidence is regenerated under
target/surefire-reports by the command.

| Offered same-seat calls | Holds committed | Inventory conflicts | Bounded-pool overload rejections |
|------------------------:|----------------:|--------------------:|---------------------------------:|
|                       2 |               1 |                   1 |                                0 |
|                     100 |               1 |                  99 |                                0 |
|                   1,000 |               1 |                 999 |                                0 |
|                  10,000 |               1 |               9,015 |                              984 |

Calls were offered from a virtual-thread executor with a simultaneous start gate into the real transaction boundary.
They do not represent 10,000 active database connections or HTTP requests per second. The pool admits at most 20
connections. Each test additionally checks persisted inventory/hold counts; the database owner invariant is the
assertion, not just response success. Outcomes vary with machine scheduling.

Other executed coverage: two independent Spring application contexts sharing PostgreSQL, all-or-nothing rollback,
lost-response replay, request-fingerprint mismatch, late capture after safe reassignment, active/expired confirmation
races, payment failure release, webhook signatures and deduplication, out-of-order state notifications, deferred outbox
recovery, idempotent issuance, generated PNG QR decoding, duplicate scan, duplicate cancellation/refund, GA capacity
slots, event cancellation before capture, authentication/ownership/RBAC, admission signature/replay checks, and a
deliberately induced PostgreSQL deadlock whose victim retries in a fresh transaction.

The 10,000-call test initially exposed unmapped CannotCreateTransactionException during pool exhaustion. The application
now maps transaction-start exhaustion to 503 with Retry-After. Excess calls may be rejected; they do not bypass
inventory ownership.

`docker compose up -d --build backend` built and started the application and PostgreSQL. The Dockerfile uses a normal
package build with a BuildKit Maven cache; broad dependency:go-offline prefetch was removed because it fetched unrelated
transitive plugin descriptors.

A real headless installed Chrome session ran the browser-tests/smoke.cjs workflow: event discovery → seat selection →
hold → sandbox SUCCESS payment → displayed QR ticket → cancellation/refund; organizer draft creation → admin approval →
published discovery. No browser JavaScript errors were recorded. A QR ticket screenshot was visually inspected. The
Playwright cached Chromium installation was incomplete; installed Chrome was used instead. See browser-tests/README.md
for portable rerun commands.

`node --check src/main/resources/static/app.js` passed. Java files were formatted using google-java-format 1.25.2.

## Not established

No k6 workload, sustained booking throughput, tail-latency target, 100k browsing RPS, million-user queue, external
payment/refund integration, Redis/Kafka fault test, Kubernetes deployment, real OIDC issuer flow, collector/export
pipeline, real pod kill, PostgreSQL failover, multi-region fencing or production security audit was executed. The
failure matrix marks these separately. Script availability and manifests are not proof of those properties.

## Final packaged-container checks

After the final rebuild, the browser workflow passed again, including organizer creation and admin approval, with no
JavaScript errors. The ticket list now refreshes while asynchronous ticket issuance is pending; account-switch tests
wait for the new identity before navigating. `/actuator/health/readiness` returned `UP`; Prometheus exposed active hold,
expiration lag, payment backlog and outbox backlog gauges. Both direct ownership queries returned zero rows, and Flyway
reported versions 1, 2, 3 and 4 successful in the Compose database. OpenAPI and Grafana JSON parsed, and all OpenAPI
schema references resolved (31 paths / 36 operations). No standalone Kubernetes YAML schema validation ran; PyYAML was
unavailable. Compose YAML was exercised by actual deployment.
