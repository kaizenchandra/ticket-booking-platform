# Forty-question design review

Each answer identifies the consistency boundary, failure response, scaling approach and operational cost. Extensions are
explicitly labeled.

## 1. Ticketmaster at millions

Regional cached browsing plus edge admission and home-region inventory. Admit only tested mutation capacity; maintain
transactional ownership, retry durable intents after failures. Edge infrastructure adds cost; the shipped monolith is a
small baseline.

## 2. IRCTC Tatkal

Partition authoritative runs/classes, apply versioned release/quota rules and admission before segment transactions.
Range exclusions prevent overlap; crash recovery uses durable intents. Railway is design-only and needs verified
operational policy.

## 3. Prevent double booking

Serialize current ownership checks and writes under inventory locks, with unique active booking constraints. Whole
selections roll back; retries use stable keys. Hot seats remain serialized and cannot scale linearly.

## 4. Why constraints

They arbitrate conflicting writers independently of stale application reads. A uniqueness/exclusion violation aborts the
transaction. Recover via safe conflict response; index maintenance is the operational cost.

## 5. Optimistic locking

Update WHERE version=expected and increment version; one writer wins, losers retry the entire transaction. Works best at
low contention, adds retry storms for hot seats. JDBC baseline uses pessimistic locks instead.

## 6. Pessimistic preference

Use row locks for short high-contention inventory transactions. Serialize hold/confirm/expiry, bound wait time and
release on rollback. Blocking and deadlocks require admission and monitoring.

## 7. Atomic multi-seat hold

Lock distinct UUIDs in database byte order, validate every row, then persist hold/items/ownership/idempotency in one
transaction. Any failure rolls back all seats; size cap bounds lock duration.

## 8. Redis locks insufficient

Lease expiry, partitions or stalled old clients can leave competing writers. Authoritative database ownership
checks/constraints still arbitrate. Redis failure must degrade admission/cache, never unlock sold inventory.

## 9. Temporary expiration

Persist DB expiry; bounded SKIP LOCKED scans release only owned rows. New transactions lazily reclaim expired rows.
Worker crashes retry durable state; scan throughput and DB clock discipline are operational requirements.

## 10. Old expiry worker

Compare current hold UUID in every clear operation. A reassigned row no longer matches, so stale work is harmless. Lock
inventory consistently and retry aborted transactions; never bulk-clear by seat ID alone.

## 11. Late payment

Final hold transition requires current ownership and unexpired DB deadline. If rejected, compensate with refund instead
of taking inventory back. Local refund is atomic; external compensation needs durable pending/reconciliation.

## 12. Timeout ambiguity

Persist stable payment ID before effect; query provider truth using it after timeout. Do not classify timeout as unpaid.
Bound retry/backoff; expose pending state. Simulator TIMEOUT resolves after 30s.

## 13. Duplicate charges

Require client idempotency and stable provider-side intent keys. Local UNIQUE booking payment prevents duplicate
simulator effects. External provider idempotency plus ledger reconciliation remains a release gate.

## 14. Booking Saga

Durable hold, pending booking, payment intent, conditional confirmation and outbox ticket issuance. Compensate expired
ownership by refund; replay pending steps after crash. Monolith collapses many transitions into ACID transactions.

## 15. Partial booking recovery

Workers scan due payment intents and pending outbox records. All effects have stable IDs/unique constraints. Alert on
age and reconcile manually if provider truth is uncertain; poison-message isolation is unfinished.

## 16. Transactional outbox

Insert domain state and event in one transaction; later relay processes committed events. Crash before ACK may duplicate
delivery, so consumers dedupe. Current database consumer commits notification and processed marker atomically.

## 17. Duplicate Kafka events

Future consumer records event ID and side effect in one transaction, partitions by aggregate and handles versions. Retry
boundedly then DLQ. Kafka transport is not implemented; broker semantics cannot guarantee external effects.

## 18. Idempotent ticket issuance

Unique booking/inventory ticket key and booking lock make replay safe. Only confirmed bookings issue; cancellation
invalidates existing tickets. Outbox retries recover failures without multiplying active ticket IDs.

## 19. Secure QR

UUID-only opaque payload plus HMAC-SHA256, no passenger PII. Retrieve only as booking owner and check signature/current
state server-side. Production needs versioned rotation and scan credential controls; offline revocation remains limited.

## 20. Duplicate check-in

Atomically update VALID to USED and write accepted/rejected scan audit. Concurrent scans share DB serialization; one
succeeds. Network ambiguity needs scanner reconciliation; offline scanners cannot guarantee global uniqueness.

## 21. Virtual waiting room

Queue identified customers, issue short-lived signed admissions under a rate budget, then attempt inventory separately.
DB queue is representative; million-user operation requires edge state and reconnect handling.

## 22. Admission replay

User-bound signed token + expiry + consumed DB flag updated within hold transaction. Idempotent retries bypass a second
consume, failed transactions roll it back. Key rotation and multi-device abuse controls remain work.

## 23. Fair allocation

FIFO database sequence gives insertion-order fairness, not physical arrival-time fairness. Abandoned entries expire.
Random/weighted policies require committed cohorts and auditable eligibility; retries should not buy priority.

## 24. Flash sale

Cache browsing, edge-shed offered traffic, and admit only measured DB capacity. Serialize authoritative ownership while
spreading unrelated seats/events. Backpressure and jitter prevent retry amplification; targets remain unverified.

## 25. Connection exhaustion

Bound Hikari pools and statement/acquisition timeouts, reserve headroom and use gateway concurrency control. Virtual
threads do not enlarge DB capacity. On overload return 503/Retry-After; scaling pods multiplies connection demand.

## 26. Inventory partitions

Choose occurrence/home-region routing; independent events can shard independently. One seat remains coordinated. Moving
partitions requires fencing and versioned routing; avoid a split writer during failures.

## 27. Hot events/seats

Precompute advisory maps, avoid global event mutex for inventory, and reject excess work early. Shared publication locks
allow many inventory transactions, while same-seat locks serialize. Lock wait metrics drive admissions.

## 28. GA vs numbered

Numbered seats require identity-exclusive ownership; GA needs capacity arithmetic. Baseline uses exclusive internal
slots for both, safely but with O(quantity) locking. Future capacity ledger needs conditional counts and durable
allocation IDs.

## 29. Rail segment allocation

Allocate [origin,destination) on run/berth, enforced with int4range exclusion and coordination for expiry. Adjacent
intervals coexist, overlaps reject transactionally. Fragmentation and quotas complicate throughput; extension only.

## 30. Berth reuse

A→C [0,2) and C→E [2,4) have no intersection; B→D conflicts. Version routes so indices remain meaningful. Reassignment
and crash recovery must use authoritative segment state, never event-seat uniqueness.

## 31. Waitlist and RAC

Lock newly free segment capacity and next eligible entry in deterministic versioned priority order; unique promotion IDs
stop duplicate capacity use. RAC requires explicit shared-accommodation rules. Both remain unimplemented.

## 32. Quotas

Use eligibility checks and authoritative quota allocation in the same transaction as inventory, with policy
version/release time. Current implementation enforces only six seats/customer/occurrence. Eventually consistent counters
cannot enforce hard limits.

## 33. Mass cancellation/refunds

Mark event cancelled under exclusive event lock, deny new holds/captures/scans, then process bookings in bounded
cancellation batches. Local refund state is idempotent. External provider limits and ambiguous refunds require a
separate ledger/work queue.

## 34. Multiple regions

Start single-region multi-AZ, then home-region partition routing and active-passive recovery. Regional browse replicas
are advisory. Failover requires fencing and verified recovery; latency, residency and RPO/RTO are deployment choices.

## 35. Global exclusivity

One authoritative writer/consensus domain per inventory partition. Async replication alone cannot decide exclusive
ownership. Freeze mutation if leadership is uncertain; strong coordination imposes latency and availability trade-offs.

## 36. Regional outage

Serve cached discovery where safe and fail closed for writes to unavailable ownership region. Fence old primary before
failover, reconcile committed intents and payments. RPO/RTO have not been measured.

## 37. Measure correctness

Check inventory-owner relationships and unique active allocations in DB, plus outcomes of concurrent multi-instance
tests. Track conflicts separately from defects. Local passing tests are evidence, not proof for arbitrary
operators/topologies.

## 38. Benchmark throughput

Use reproducible fixed datasets, offered arrival rates and realistic checkout mixes. Report achieved RPS, dropped work
and p50/p95/p99 along with invariant queries. Provided k6 scripts are unexecuted; no throughput claim follows from JUnit
duration.

## 39. Bottleneck metrics

Correlate DB lock waits/deadlocks, pool waiters, latency, conflict rate, expiry/outbox/payment age and CPU/GC. Use
bounded labels. Baseline Micrometer/dashboard cover a subset; Kafka/Redis metrics require those actual integrations.

## 40. Production proof gates

Validate no-oversell under supported fault topology, provider-idempotent finance and compensation, authorization,
restore/failover, overload bounds and SLOs. Execute load/security/recovery tests and resolve documented gaps; current
baseline does not meet every gate.

