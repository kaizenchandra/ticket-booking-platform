# Remaining production and product work

This delivery is a working event workflow baseline. It does not fulfill every capability in the master specification,
and passing local tests does not make it production ready.

* Catalog: occurrence-local immutable seat layout, one occurrence per newly created event, no reusable physical
  venue/section editor, arbitrary seat-map import, attendee details or comprehensive organizer lifecycle. Newly created
  events are capped at 1,000 slots and availability responses at 10,000 rows.
* Pricing/quota: INR-only final seat price, no tax/fee/promotion engine, no VIP/presale/accessibility quota policies.
  Six-per-user is the implemented purchase limit. GA uses fixed slots rather than an optimized capacity ledger.
* Identity/security: production JWT resource-server configuration exists, but browser OIDC login, audience enforcement,
  issuer end-to-end testing, robust WAF/bot/device controls, key rotation, PII lifecycle and compliance evidence remain
  work. Basic identity/profile/registration is for local demonstration.
* Financial integration: deterministic local provider ledger, full refund policy, one payment per booking; no bank/card
  provider effects, partial refunds, authorization/capture separation or real provider reconciliation. External
  financial effects must gain a durable effect ledger and provider-side idempotency before integration.
* Reliability: database outbox and in-app notification consumer implemented; Kafka publishing, Redis cache/admission
  integration, email/SMS, DLQ/poison handling, replay UI, provider receipt retention, audit-grade admin logs and
  data-retention jobs remain open.
* Scale: DB waiting room is representative with polling/abandonment limitations; no million-user capacity claim.
  Admission budgets, worker throughput and connection pools need measured tuning. HTTP ingress needs a tested
  concurrency limiter in the gateway. PostgreSQL is one authoritative write region.
* Railway: segment/exclusion schema and waitlist/RAC policy design only, no rail endpoints or executed railway tests.
* Operations: Kubernetes/monitoring/CI assets supplied; actual cluster deployment, multi-AZ failover/PITR restore,
  security review, SLO/load validation and tracing collector verification are unexecuted. Dedicated counters for every
  requested operational condition are not yet present.

See requirements.md for the acceptance ledger and failure-scenarios.md for individual residual risks.
