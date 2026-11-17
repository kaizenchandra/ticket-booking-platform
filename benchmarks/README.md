# Reproducible workloads

Run against an isolated dataset, with WAITING_ROOM=false for raw inventory contention. Never run load scripts against a
live sale. k6 is an external prerequisite.

```sh
k6 run -e RATE=1000 benchmarks/sale.js
k6 run -e RATE=10000 benchmarks/sale.js
k6 run -e RATE=100000 benchmarks/sale.js
k6 run -e VUS=1000 -e RUN_ID=unique -e OCCURRENCE_ID=... -e SEAT_ID=... benchmarks/contention.js
```

Repeat at 100/1,000/10,000 VUs and hold TTL longer than test duration. Use distributed generators for large workloads.
Registration includes bcrypt and contaminates contention latency; provision accounts beforehand for clean
booking-throughput measurements. k6 dropped iterations measure generator or target saturation; offered RPS is not
achieved RPS. Export JSON using `--summary-export=summary.json`. Record machine, Java, DB settings, sale distribution,
warmup, offered/achieved RPS, failures, p50/p95/p99, conflicts, DB lock waits, CPU and GC. Use PostgreSQL
`pg_stat_activity` and `pg_stat_database.deadlocks` alongside Prometheus.

After each test verify the database invariant, not just response counts:

```sql
SELECT inventory_id,count(*) FROM booking_items WHERE active GROUP BY inventory_id HAVING count(*)>1;
SELECT i.id FROM inventory i JOIN holds h ON h.id=i.hold_id WHERE i.state='SOLD' AND h.state<>'CONFIRMED';
```

Both queries must return no rows. For multiple backend instances put a load balancer in front of the same PostgreSQL
database. All throughput and latency targets in the design are UNVERIFIED. Executed JUnit contention checks are
correctness tests, not capacity benchmarks. No million-user run has been executed.
