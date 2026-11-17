# Requirements and acceptance ledger

The working scope is event ticketing in one authoritative PostgreSQL region. Railway is a separate design extension.
Customer identity comes from Spring Security, never a client-selected customer ID. All money is integer INR paise; the
locked seat price is the entire final price in this baseline (no taxes, fees, discounts, or exchange). Quotes remain
valid until hold expiry. No float arithmetic is used for authoritative amounts.

| Requirement                        | Implementation / verification                                                               |
|------------------------------------|---------------------------------------------------------------------------------------------|
| Exclusive hold / confirmed owner   | Inventory row lock plus state checks; partial unique active booking index; PostgreSQL tests |
| Atomic multi-seat reservation      | One transaction, ordered inventory locks, rollback test                                     |
| Duplicate requests                 | Principal/operation/key + canonical request fingerprint under advisory transaction lock     |
| Expiration                         | DB wall time; bounded indexed scans; ownership-qualified release; reclaim test              |
| Checkout and recovery              | Durable bookings/payments/outbox; simulator reconciliation and outbox replay tests          |
| Financial effects                  | One payment per booking; sandbox only; late-capture refund ledger                           |
| Secure tickets                     | HMAC opaque UUID; unique issuance; atomic scan and audit                                    |
| Roles / ownership                  | Spring Security URL rules plus customer and organizer checks                                |
| Customer/admin/organizer UI        | Functional static JS application served by Spring                                           |
| High demand                        | Waiting-room tokens, DB rate limits, connection/accept bounds; scale not established        |
| Railway, waitlist/RAC              | Design only, deliberately no pretend CRUD booking endpoints                                 |
| 99.99%, p99 <200ms, 10k bookings/s | Unverified targets, require realistic deployment/load validation                            |

Assumptions: one currency, full refund policy before/after check-in for demonstration, no external card data, immutable
occurrence pricing after holds, one published demo concert, external TLS termination for deployment. Inventory IDs are
immutable UUIDs distinct from display labels. A seat map is advisory, and a 409 rejects the entire selection. A waiting
token grants one successful hold attempt, never seat ownership.

Acceptance evidence is limited to the checks listed in verification.md. Exhaustive multi-AZ failures, external financial
reconciliation, regulatory policies, abuse/device models, and target-volume performance are release gates still open.
