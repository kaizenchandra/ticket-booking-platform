# Security and deployment gates

Demo mode uses BCrypt-backed Spring Security Basic authentication and local role/ownership checks. Production mode
(`DEMO=false`) accepts JWTs validated by Spring's resource server from
`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`. It maps the issuer's `roles` claim to ROLE_* and uses subject as
the immutable customer ID. Configure and verify intended audience checks at the issuer/resource-server boundary before
deployment. No external OIDC login flow has been integration-tested; the provided browser UI currently supports only
demo Basic authentication and needs a PKCE/BFF login adapter for production OIDC.

No customer ID or payment amount from request bodies controls authorization. Organizer ticket scans check event
ownership; admin scans may access all events. Public QR contains only UUID + HMAC, no attendee identity. Key
rotation/versioned tokens and short-lived scan credentials remain production work. QR signatures alone do not prove
current validity offline: cancellation and duplicate use require online DB state. Tickets expire operationally 24h after
occurrence start, with no earliest-entry restriction in this demo.

Secrets: TICKET_SECRET >=32 characters, WEBHOOK_SECRET strong independent value. Production startup rejects default
ticket/webhook secrets and webhook secrets shorter than 32 characters. Kubernetes secret `ticketing-secrets` must supply
DB_URL, DB_USER, DB_PASSWORD, TICKET_SECRET, WEBHOOK_SECRET; no actual deployment credentials are committed. TLS
terminates at ingress with `ticketing-tls`. Restrict management endpoints to an internal network and dedicated workload
identities; the demo Prometheus basic credentials must be replaced in OIDC mode.

CSRF is disabled for stateless API access; Basic mode is intended only for local development. For a cookie-based
production BFF enable CSRF and secure HttpOnly/SameSite cookies. CORS is not enabled. Passwords never persist in
localStorage; the demo page keeps input values in DOM memory only. No raw payment cards are accepted. Passenger/attendee
PII capture, encryption, deletion workflows, fraud/device scoring and regulatory compliance are not implemented.

Retention proposal: webhook/event dedupe keys must outlive provider replay window; do not delete idempotency keys while
a customer may safely retry a financial intent. Archive aged outbox/notifications with audit rules, redact payload logs,
retain scans per venue/privacy policy. Current sample has no automated retention job, per-request structured audit trail
or immutable administrative audit store. Micrometer/OTel bridging and an OTLP exporter are wired; set TRACING_ENABLED=
true and OTLP_TRACING_ENDPOINT to your collector. Export is disabled by default; no collector pipeline or cross-service
traces were validated. Dedicated failure counters and administrative audit instrumentation remain gaps.
