# Ticket issuance and validation

```mermaid
sequenceDiagram
 Outbox->>Booking: Lock confirmed booking
 Outbox->>TicketDB: Insert unique booking/inventory tickets
 Customer->>API: Fetch owned ticket and QR PNG
 API-->>Customer: UUID + HMAC-SHA256, no passenger PII
 Scanner->>API: Authenticated organizer + ticket token
 API->>Event: Shared publication lock, ownership check
 API->>Booking: Lock to coordinate cancellation
 API->>TicketDB: UPDATE VALID to USED, audit accepted/rejected scan
 TicketDB-->>Scanner: At most one accepted scan
```

Online validation rejects invalid signatures, other organizers, cancelled events, expired validity (start +24h),
cancelled tickets, and previously used tickets. Ticket issuance is eventually processed through the outbox; a paid
booking can briefly have an empty ticket list. Replaying issuance cannot duplicate the same booking/seat ticket. The QR
renderer is verified by decoding the generated PNG in the integration test. The browser test verifies the PNG is
displayed. Offline readers can verify HMAC only if trusted with the signing key, which is not recommended; asymmetric
signatures and cached revocation with explicit offline duplicate-use risk would be a separate design.
