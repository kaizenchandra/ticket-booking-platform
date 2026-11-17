# Operations and recovery

Local: `docker compose up -d --build`, open :8086. Enable monitoring with `--profile monitoring`. Health is
`/actuator/health`; liveness/readiness probes use `/actuator/health/liveness` and `/actuator/health/readiness`.
Readiness includes database health; process liveness remains independent of DB outages.

Deploy baseline: build/push a versioned image, replace image and issuer/host in `helm/manifests.yaml`, create managed
PostgreSQL with PITR, create ticketing-secrets and TLS certificate using your secret manager, then
`kubectl apply -f helm/manifests.yaml`. Do not deploy unchanged example URLs. HPA/PDB/zone spreading are scaffolding,
not executed cluster validation. Use backward-compatible expand/contract migrations; migrations currently run at app
startup and a dedicated migration job is preferable at scale. Avoid shrinking/removing columns until old pods are
drained. Nested `.github/workflows/verify.yml` is a standalone-project template; the root workflow runs it for this
repository.

If holds fail: inspect HTTP 409 vs 503, Hikari waiters, DB pool capacity and pg_stat_activity. Reject/load-shed at
gateway before increasing pool sizes. Never clear HELD/SOLD ownership by hand without checking hold, booking and payment
state. Expiry worker lag alone does not invalidate lazy reclaim correctness.

If checkout is stuck: query payments PENDING with due_at and bookings PAYMENT_PENDING. Reconciler retries after restart.
For an external provider adapter, query provider using stable payment ID; never generate a new charge ID as a repair.
Refund ambiguity must remain REFUND_PENDING until confirmed externally; the local simulator has no external ambiguity.

If tickets are missing: verify booking CONFIRMED, inspect pending outbox_events and worker logs. Restart worker and
reprocess the original event; UNIQUE(booking_id,inventory_id) prevents duplicate issue. A poison event currently aborts
the batch; isolate it under audited maintenance and implement bounded retry/DLQ before external broker integration.
Notifications are in-app records, not guaranteed email/SMS delivery.

After crash or DB failover: fence previous writers, establish one authoritative primary, verify migrations and integrity
queries below, then resume admissions gradually. Do not auto-promote an async replica while an old primary may still
serve writes. In-flight transaction locks disappear on connection loss; commits with lost responses are recovered by
retrying the same idempotency key.

```sql
SELECT inventory_id,count(*) FROM booking_items WHERE active GROUP BY inventory_id HAVING count(*)>1;
SELECT i.id FROM inventory i JOIN holds h ON h.id=i.hold_id WHERE i.state='SOLD' AND h.state<>'CONFIRMED';
SELECT b.id FROM bookings b LEFT JOIN payments p ON p.booking_id=b.id WHERE b.state='CONFIRMED' AND p.state IS DISTINCT FROM 'CAPTURED';
SELECT b.id FROM bookings b WHERE state='CONFIRMED' AND NOT EXISTS(SELECT 1 FROM tickets t WHERE t.booking_id=b.id);
SELECT pid,wait_event_type,wait_event,query_start FROM pg_stat_activity WHERE datname=current_database();
SELECT deadlocks FROM pg_stat_database WHERE datname=current_database();
```

First three queries must be empty. Last ticket query may transiently return rows during outbox processing; alert on age,
not momentary presence. Reconcile cancelled events until no confirmed/pending bookings remain; worker handles batches
of 100. Full-refund simulator operations are idempotent.

Proposed SLO: 99.99% eligible API availability monthly (~4.32min error budget/30 days), p99 admitted hold latency <200ms
at a declared mix, zero invariant violations. Exclude expected 409 inventory conflicts from service-error ratio, but
report user success and waiting duration separately. Alert on invariant violations immediately, payment backlog age >
2min, expiry lag >30s, outbox oldest event >1min, pool >80% with waiters, deadlock rate change and sustained 5xx >1%.
Prometheus sample rules cover only a subset. Store incident evidence and reconcile all ambiguous payment effects before
closing an incident.
