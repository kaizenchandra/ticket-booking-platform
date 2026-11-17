# Encore — Distributed Ticket Booking Platform

Java 21 / Spring Boot 3.5.16 modular monolith. PostgreSQL owns seat inventory. This is an executable event-ticketing
baseline with explicit production gaps; it is not a certified production service or an IRCTC policy implementation.

## Run locally

```sh
cd ticket-booking-platform
docker compose up -d --build
# Open http://localhost:8086
docker compose --profile monitoring up -d
# Prometheus :9098, Grafana :3008 (Grafana initial login admin/admin)
docker compose down
```

Demo accounts: `customer / demo-customer`, `organizer / demo-organizer`, `admin / demo-admin`. Registration creates
customers only. Credentials are local-only. The frontend is served from the application, requiring no Node build. Sign
in as organizer to create a draft, then admin to approve it, then customer to buy seats. The included published concert
supports immediate checkout. Sandbox SUCCESS/FAIL resolves asynchronously; TIMEOUT captures after 30 seconds. Late
captures are refunded rather than reclaiming sold seats. Full refunds are a deterministic local ledger operation, not a
bank transfer.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; on Linux select your Java 21 installation
mvn verify             # Docker required; PostgreSQL Testcontainers tests do not silently skip
mvn package -DskipTests
docker compose up -d postgres
java -jar target/ticket-booking-platform-1.0.0-SNAPSHOT.jar
# Native application :8080, PostgreSQL :5440
```

On OrbStack, if Testcontainers needs a socket override:

```sh
export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
mvn verify
```

## Implemented scope

* Event discovery, occurrences, organizer-created reserved/GA inventory, publication approval, blocking available seats,
  asynchronous event cancellation/refunds.
* Transactional all-or-nothing holds, 1–6 seats, six-seat per-user occurrence limit, durable request
  fingerprint/idempotency, bounded expiry scans, safe lazy reclaim.
* Booking history, sandbox payments, signed timestamped webhooks, deduplication, terminal state ordering, late capture
  compensation, durable reconciliation.
* Outbox-driven idempotent ticket issuance and in-app notifications, signed opaque QR tokens, owner-authorized
  retrieval, organizer-authorized atomic check-in, duplicate scan audit, cancellation.
* Customer UI, organizer studio, operations portal, registration and basic demo identity; JWT resource-server mode for
  an external OIDC issuer.
* Database-backed FIFO admission with signed single-use checkout tokens, reservation rate limiting, Micrometer,
  Prometheus/Grafana assets, Docker, Kubernetes baseline, CI.

## Source map

`src/main/java/com/example/ticketing`: `catalog`, `reservation`, `booking`, `payment`, `ticket`, `waitingroom`,
`notification`, `security`, `shared`; `Api.java` is the thin REST adapter. JDBC is intentional: inventory lock order and
conditional SQL are visible. `src/main/resources/db/migration` holds executable constraints; `static` holds the working
portals. `src/test` contains PostgreSQL concurrency and workflow tests. `docs/openapi.json` specifies the API.
`benchmarks` contains unmeasured k6 workloads. `helm/manifests.yaml` is plain Kubernetes YAML, not a Helm chart.

## Verification and limitations

See [known limitations](docs/known-limitations.md), [verification](docs/verification.md), [architecture](docs/architecture.md), [failure matrix](docs/failure-scenarios.md), [40-question review](docs/design-review.md),
and [runbook](docs/runbook.md). No target-scale benchmark has been executed. Railway, RAC, waitlists, configurable
quotas, promotions/taxes, external payment effects, Redis caching, and Kafka delivery remain extensions. The implemented
GA baseline uses individually allocated capacity slots. The physical venue layout is occurrence-local and immutable
after creation; reusable venue/section authoring and multiple occurrences per newly created event need further product
work.

Spring Boot compatibility was verified against https://docs.spring.io/spring-boot/3.5/system-requirements.html (Java
17–25 includes Java 21), then built/tested using Java 21.0.13.0.3. Major dependency versions follow the existing
repository convention. All dependencies still require deployment-time vulnerability review.
