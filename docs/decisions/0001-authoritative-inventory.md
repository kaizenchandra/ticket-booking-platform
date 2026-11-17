# ADR 0001 — PostgreSQL ownership, modular deployment

Accepted: Java 21/Spring Boot 3.5.16, JDBC, PostgreSQL READ COMMITTED with ordered inventory locks and conditional
transitions; one transaction handles an all-or-nothing selection. Partial unique active booking index provides an
additional confirmed-owner constraint. No distributed lock grants ownership. Bounded database workers handle expiry,
payment simulator reconciliation and transactional outbox processing.

Benefits: inspectable SQL, simple crash rollback, deterministic financial simulation, small local footprint. Costs: one
authoritative write region, cross-domain transaction coupling, polling and DB contention, no Kafka transport/Redis cache
scaling evidence. Preserve module boundaries and event envelopes for future extraction; introduce actual services only
with provider idempotency, contract tests and durable cross-service compensations.
