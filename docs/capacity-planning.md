# Capacity assumptions — estimates, not benchmark results

Targets: 100m registered users, 1m concurrent browsers, 1m offered sale attempts/s, 10k successful booking
transactions/s, 99.99% availability, p99 hold creation <200ms. None is demonstrated by local verification.

Assume 1m browsers refresh every 10s → 100k availability reads/s; at 50KB/map that is 5GB/s (~40Gb/s) before
compression. A single occurrence's 100k-seat JSON is much larger: prefer section deltas/snapshots and edge fan-out.
Current full-map API caps at 10k rows and is suitable for small demo venues, not the target 100k seats.

| Sale profile     | Offered attempts/s | Admitted holds/s assumption | Seats / hold | Successful volume ceiling                                          |
|------------------|-------------------:|----------------------------:|-------------:|--------------------------------------------------------------------|
| Normal reserved  |              1,000 |                         100 |            2 | Payment ~80/s at 80% checkout                                      |
| Popular concert  |          1,000,000 |                       2,000 |            2 | 10k seats exhaust in ~2.5s if all held                             |
| Tatkal-like      |          1,000,000 | 5,000 across run partitions |            2 | Depends on segment fragmentation/quota policy; extension only      |
| GA               |            100,000 |                       2,000 |            4 | Slot baseline locks 8k rows/s; capacity-counter redesign advisable |
| Reserved stadium |            100,000 |                       2,000 |            2 | 4k seat row mutations/s; hot-seat contention dominates             |

These admitted rates are illustrative planning inputs, not configuration recommendations. The shipped queue defaults to
20 admissions/s for development. Real sustainable rates must be measured and admission should follow DB saturation/lock
waits, not solely CPU.

At 10k bookings/s and 80% completion, provider capacity must handle ~12.5k initiation requests/s, plus
webhooks/reconciliation/refunds. At four domain events per completed booking, 40k events/s × 1KB = 40MB/s before broker
replication; partition by booking for Saga order, inventory ID/event for ownership commands. Three replicas imply ~
120MB/s broker write traffic. Email/SMS notification capacity is ~10k/s for one notification per booking.

At 2m active holds and 300s TTL, steady-state expiry arrivals are ~6,667/s if none confirms; the current 100-row/s
worker is inadequate. Shard scans, tune batches/worker count, and measure lock cost. 2m holds × 2 items × ~200B
rows/indexes ~0.8GB raw item overhead plus hold rows and WAL; allow several GB and measure actual relation sizes.

Advisory Redis snapshots: 10k hot occurrences × 50KB ~500MB raw, allow 2–3x allocator/replica overhead. This excludes
whole 100k-seat maps. Ticket storage at 10k/s × 500B metadata = 5MB/s, ~432GB/day before indexes and replicas; generated
QR PNGs are currently on demand, so no artifact storage. 100m identity rows at 500B are ~50GB before indexes. At 1m
bookings/day × 2KB booking/payment/ticket/event metadata allow ~2GB/day plus indexes/WAL; retention and partitioning
become essential.

Pool planning: `total_connections >= replicas × pool_size + workers/maintenance reserve`; throughput upper bound is
roughly active transaction slots / average transaction duration, but hot-row serial waits invalidate naive linear
scaling. At 20 connections and 20ms average transaction time the ideal upper bound is 1k txn/s, before payment/catalog
work and contention. Virtual threads do not increase this bound. Observe pg_stat_activity wait_event, deadlocks,
acquisition timeouts, outbox age, expiry lag and latency histograms. Partition by occurrence/home region only after
measuring; a hot single seat is inherently serialized.
