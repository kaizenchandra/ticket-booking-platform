# Inventory consistency protocol

Each occurrence inventory row has one state and at most one owning hold ID. A sold row retains its hold owner. A second
database rule rejects any two active booking_items for the same inventory UUID:

```sql
CREATE UNIQUE INDEX exclusive_confirmed_seat ON booking_items(inventory_id) WHERE active;
```

The executable migration also enforces state/owner/expiry shape, unique occurrence labels and foreign keys. The
application performs legal transitions; a privileged SQL operator can violate cross-table semantic relationships.
Restrict application DB privileges, audit manual repairs and use runbook reconciliation. Database constraints are
defense in depth, not a substitute for correct transactions.

Hold creation: take per-principal idempotency advisory lock, event shared lock, customer/occurrence quota advisory lock,
then inventory locks in PostgreSQL UUID byte order. Validate all rows before inserting the hold. Any conflict aborts the
entire transaction. A held row whose DB expiry has elapsed can be reassigned directly under its inventory lock without
taking the previous hold lock. The old hold may remain ACTIVE until cleanup, but cannot confirm because confirmation
checks its expiry and current item ownership.

All booking mutations lock the hold, then booking, then sorted inventory, then payment as needed. Confirmation first
takes a shared event lock to coordinate cancellation. Expiry locks holds using SKIP LOCKED and then inventory in the
same order. No release clears a row merely by seat ID: `WHERE hold_id=?` protects reassigned inventory. Cancellation
first deactivates booking_items, then returns owned inventory within the same transaction.

| Technique            | Appropriate role / cost                                                                             |
|----------------------|-----------------------------------------------------------------------------------------------------|
| Pessimistic row lock | Baseline under high contention; clear serialization; lock waits need protection                     |
| Optimistic @Version  | Useful at low contention; one version update wins; retry whole aggregate; no JPA version field here |
| Conditional SQL      | Final state/expiry transition; can also implement compare-and-set ownership                         |
| Unique constraint    | Independent final-owner backstop; transaction rolls back violations                                 |
| Redis lock           | Optional contention hint; leases, partitions and expired clients cannot authorize ownership         |
| Partitioned commands | Future event-key serialization; durable idempotency/fencing still required                          |

Conflicts are 409. Lock/statement/pool transient failures return 503 + Retry-After; clients retry the same idempotency
key with bounded exponential backoff and jitter (at most three attempts). No unbounded automatic retry loop holds a
connection. An ambiguous commit is recovered by the idempotency record. Deadlocks abort one transaction; retry must
start a fresh transaction. Ordered locking reduces deadlocks but does not guarantee absence when operator/external
transactions use other orders.

General admission is implemented as a fixed set of internal capacity slots, each with the same ownership protocol. This
is safe but locks O(quantity) rows; a category capacity row with `held+sold+requested<=capacity` and durable allocation
records would be more efficient for very large GA sales. Never infer remaining capacity from Redis counters. Current
quotas are only six-per-customer; category/VIP/accessibility eligibility and release policies remain extensions.
