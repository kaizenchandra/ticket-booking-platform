# MASTER PROMPT: Design and Develop a Production-Grade Distributed Ticket Booking System Like Ticketmaster or IRCTC Using Java 21 and Spring Boot

## 1. ROLE AND ENGINEERING RESPONSIBILITIES

Act as a **Principal Software Engineer, Distinguished Distributed Systems Architect, Solution Architect, Java 21
Concurrency Expert, Database Architect, Payment Systems Engineer, Cloud Architect, Security Architect, and Site
Reliability Engineer** with 15+ years of experience designing high-concurrency ticketing, reservation, and payment
platforms.

Your task is to **design, develop, test, benchmark, document, and deploy an end-to-end Distributed Ticket Booking System
inspired by Ticketmaster, IRCTC, and BookMyShow**, using Java 21 and Spring Boot.

Treat this as a real enterprise engineering project, not merely a system design interview exercise.

Your responsibilities include:

1. Requirements analysis
2. High-Level Design (HLD)
3. Low-Level Design (LLD)
4. Event, train, venue, and inventory modeling
5. Seat availability and discovery
6. Distributed seat reservation
7. Temporary seat holds
8. Seat-lock expiration
9. High-concurrency booking
10. Payment integration
11. Booking confirmation
12. Ticket generation
13. Cancellation and refund workflows
14. Waitlist and RAC-style allocation where applicable
15. Virtual waiting room
16. Flash-sale and Tatkal-like booking traffic
17. Distributed transactions and Saga orchestration
18. Idempotency and duplicate booking prevention
19. Event-driven processing
20. Security and fraud prevention
21. Observability and operational recovery
22. Automated testing and performance engineering
23. Docker/Kubernetes deployment
24. Functional customer and administrative interfaces
25. Complete source code and documentation

**Do not stop after HLD, LLD, pseudocode, or isolated code examples. Deliver a runnable, maintainable, tested
implementation with backend, functional frontend, infrastructure, and operational documentation.**

### Mandatory engineering principles

- Use Java 21 and a verified compatible Spring Boot version.
- Follow existing repository instructions, dependency versions, and coding conventions.
- Apply DDD, SOLID, Clean Architecture, and Hexagonal Architecture pragmatically.
- Prefer clear bounded contexts and cohesive modules over premature microservice decomposition.
- Keep critical inventory invariants enforced by an authoritative transactional system.
- Address concurrency, idempotency, authorization, transaction isolation, retries, and failure recovery.
- Use bounded queues, connection pools, thread pools, timeouts, and retries.
- Use `BigDecimal` or integer minor units for money, with explicit currency and rounding policies.
- Never use client-side state or Redis cache alone as proof of final seat ownership.
- Never assume a distributed lock by itself prevents double booking.
- Do not claim exactly-once external payment effects without provider-side idempotency or reconciliation.
- Never fabricate executed tests, benchmark results, or production-readiness claims.
- Distinguish implemented behavior from future architectural extensions.
- Make routine implementation choices independently and document major trade-offs.

---

# PART A — PRODUCT OBJECTIVE

## 2. PRIMARY OBJECTIVE

Build a horizontally scalable ticket booking platform supporting two reservation models:

### Model A — Event Ticketing (Ticketmaster / BookMyShow)

- Concerts
- Movies
- Sports events
- Theater performances
- Reserved seating
- General admission
- Venue layouts
- Ticket categories
- Event-specific pricing

### Model B — Railway Ticketing (IRCTC-like)

- Train routes
- Stations
- Train schedules
- Travel dates
- Coaches
- Berths
- Journey segments
- Passenger reservations
- Waitlists
- RAC-style allocation
- Tatkal-like high-demand booking

**Implement event ticketing as the initial fully functional vertical slice while designing railway segment-based
inventory as a separate, explicit domain extension.**

Do not force railway reservations and event seats into one overly generic inventory model.

If railway booking is within the agreed implementation scope, implement a functional railway workflow with real
segment-overlap checks, not merely CRUD APIs.

---

## 3. EXAMPLE END-TO-END SCENARIO

A concert has 10,000 seats.

At ticket-sale opening, 500,000 customers attempt to purchase tickets simultaneously.

The system must:

1. Authenticate customers.
2. Admit traffic through a controlled waiting room where configured.
3. Display available ticket categories and seats.
4. Allow the customer to select seats.
5. Verify current inventory and booking eligibility.
6. Atomically reserve selected seats.
7. Create a durable temporary hold with an expiration time.
8. Return a hold ID and authoritative expiry timestamp.
9. Initiate a payment through a sandbox payment provider.
10. Handle payment success, failure, timeout, and webhooks.
11. Confirm the booking only when inventory and payment conditions are satisfied.
12. Generate a unique ticket with a verifiable QR/barcode token.
13. Release expired or failed holds safely.
14. Notify the customer.
15. Support cancellation and eligible refunds.
16. Recover from server crashes, retries, payment ambiguity, and asynchronous event duplication.

### Fundamental correctness invariant

**For each event seat, at most one active confirmed booking may own that seat.**

For general-admission ticket inventory, confirmed allocations and active holds must never exceed configured sellable
capacity under the documented inventory model.

For railway bookings, a berth may be allocated to different passengers only when their journey segments do not overlap
and operational allocation rules permit it.

---

# PART B — FUNCTIONAL REQUIREMENTS

## 4. IDENTITY AND USER MANAGEMENT

Implement:

- Customer registration or identity-provider integration
- Authentication
- Customer profile
- Passenger/attendee details
- Booking history
- Saved preferences
- Account deactivation
- Administrative accounts
- Seller/organizer accounts
- Role-based and resource-level authorization

Use Spring Security and OAuth2/OIDC integration where appropriate.

Do not trust client-supplied customer IDs for authorization.

---

## 5. EVENT AND VENUE MANAGEMENT

Implement:

- Event creation
- Event schedules
- Venue creation
- Venue sections
- Rows
- Seats
- Ticket categories
- Ticket prices
- Event publication
- Event cancellation
- Organizer management

### Core concepts

Distinguish:

- Event
- Event occurrence/performance
- Venue
- Seat
- Sellable inventory
- Ticket category
- Reservation hold
- Booking
- Issued ticket

A physical seat may be sold for multiple event occurrences, but it must not be sold twice for the same occurrence.

Provide immutable identifiers for inventory allocations.

---

## 6. SEAT AVAILABILITY

Implement:

- Venue seat map
- Available seats
- Held seats
- Sold seats
- Blocked seats
- Ticket category availability
- Section-level counts
- Price display

### Important consistency requirement

Availability displayed in the UI may be slightly stale.

However, the authoritative reservation operation must perform a current transactional check.

Do not guarantee that a displayed available seat will remain available until a hold succeeds.

Define API behavior for conflicts and partially unavailable seat selections.

---

## 7. TEMPORARY SEAT HOLDS

Implement seat holds with configurable TTL.

Example:

- Customer selects seats A10 and A11.
- System creates a 5-minute hold.
- Seats are unavailable to other customers during the valid hold.
- Customer completes payment.
- Hold transitions to confirmed.
- If the hold expires, inventory becomes available again.

### Required operations

- Create hold
- Retrieve hold
- Inspect expiry
- Confirm hold
- Release hold
- Expire hold
- Extend hold if permitted

### Required invariants

- A seat cannot belong to two simultaneously valid holds.
- A sold seat cannot be held again.
- Hold creation is atomic for a multi-seat selection.
- A partial hold must not remain after an all-or-nothing operation fails.
- Expired holds cannot be confirmed without an explicit safe recovery protocol.
- Duplicate hold requests with the same idempotency key return the original result.
- An old expiration worker cannot release a seat that has been reassigned to a newer hold.

Use database-enforced state transitions, constraints, and reservation ownership tokens.

---

# PART C — CONCURRENCY AND DOUBLE-BOOKING PREVENTION

## 8. LAST-SEAT CONCURRENCY PROBLEM

Consider:

- Exactly one seat remains.
- Customer A tries to book it.
- Customer B tries to book it simultaneously.
- Both requests reach different Spring Boot instances.

The system must ensure only one reservation succeeds.

Compare:

1. Pessimistic locking
2. Optimistic locking using `@Version`
3. Conditional SQL updates
4. Database uniqueness constraints
5. Redis distributed locks
6. Serialized partitioned reservation commands

Recommend a correct baseline approach using PostgreSQL transactional constraints and atomic updates.

Explain why a Redis lock is not sufficient as the authoritative safeguard.

### Mandatory tests

- Two simultaneous requests for the same seat
- One hundred concurrent requests
- Thousands of concurrent requests
- Multiple Spring Boot instances
- Transaction rollback
- Deadlock retry
- Duplicate hold creation

Ensure tests verify the invariant rather than merely asserting successful HTTP responses.

---

## 9. MULTI-SEAT ATOMIC RESERVATION

A customer selects four seats.

Only three are still available.

The system must either:

- Reject the entire hold; or
- Explicitly support partial fulfillment if the API contract allows it.

For the baseline, use all-or-nothing seat reservation.

Define lock acquisition order to reduce deadlocks.

Implement transaction rollback on partial failure.

---

## 10. SEAT HOLD EXPIRATION

Design expiration using:

- Database expiration timestamps
- Indexed expiry scans
- Bounded background processing
- Conditional release
- Redis TTL notifications only as optional hints

Do not rely solely on Redis key expiration for authoritative inventory release.

Explain:

- Worker crashes
- Delayed expiry jobs
- Concurrent confirmation
- Clock skew
- Reassigned seats
- Repeated expiry events

Use authoritative server/database time where necessary.

If an expired hold remains physically uncleared in storage, new booking attempts should be able to safely reclaim it
through the documented transactional protocol.

---

# PART D — RAILWAY-SPECIFIC INVENTORY DESIGN

## 11. TRAIN ROUTE AND SEGMENT ALLOCATION

Design a railway booking domain with:

- Train
- Route
- Ordered stations
- Scheduled journey date
- Coach
- Berth
- Berth category
- Passenger
- Journey segment
- Reservation
- Waitlist entry

### Critical scenario

A train travels:

`A → B → C → D → E`

Passenger 1 reserves berth 12 for `A → C`.

Passenger 2 requests berth 12 for `C → E`.

Both may be allocated the same berth because their occupied journey segments do not overlap.

Passenger 3 requests berth 12 for `B → D`.

The request must conflict with existing overlapping allocation.

Implement segment overlap using a defined interval model such as:

`[originStationIndex, destinationStationIndex)`

Use half-open intervals so adjacent journeys can share the same berth.

Consider PostgreSQL range types and exclusion constraints when appropriate.

Do not reuse the simpler event-seat uniqueness rule for railway segment allocation.

---

## 12. WAITLIST AND RAC

Design:

- Confirmed reservations
- RAC-style shared accommodation rules where applicable
- Waitlist positions
- Cancellation-driven promotion
- Priority and quota eligibility
- Passenger notification
- Final allocation

Define deterministic promotion rules.

Ensure duplicate cancellation events cannot promote the same available capacity twice.

Avoid claiming exact IRCTC policy equivalence without verifying official operational rules.

Treat Tatkal, quota allocation, RAC, and railway-specific regulatory policies as configurable, versioned domain
policies.

---

# PART E — PRICING AND QUOTAS

## 13. PRICING ENGINE

Support:

- Base fare
- Event category price
- Dynamic pricing as an optional module
- Service charges
- Taxes
- Discounts
- Promotional codes
- Booking fees
- Currency

Use monetary precision-safe types.

Define:

- Price quote validity
- Currency
- Rounding
- Tax configuration
- Promotion precedence
- Total calculation

The customer must see the authoritative final payable amount before payment authorization.

Do not accept client-calculated totals as authoritative.

---

## 14. RESERVATION QUOTAS

Support configurable inventory allocation policies such as:

- General sale
- Presale
- VIP
- Organizer quota
- Accessibility allocations
- Railway quota categories

Define release times and eligibility.

Ensure quota checks and inventory allocation are concurrency-safe.

Do not implement quota enforcement solely using eventually consistent counters.

---

# PART F — HIGH-LEVEL DESIGN

## 15. ARCHITECTURE ALTERNATIVES

Compare:

### Option A — Modular Monolith

One Spring Boot application with bounded modules:

- Identity
- Catalog
- Venue/Train Inventory
- Reservations
- Pricing
- Booking
- Payments
- Tickets
- Notifications

### Option B — Domain-Oriented Microservices

Separate deployments for independently scalable domains.

### Option C — Globally Distributed Ticketing Platform

Regional browsing, authoritative event/route inventory ownership, regional payment integration, and global admission
controls.

Evaluate:

- Correctness
- Latency
- Deployment complexity
- Operational cost
- Independent scalability
- Transaction boundaries
- Failure isolation

**Prefer a modular monolith for the initial working implementation while preserving clear domain boundaries and future
extraction options.**

---

## 16. REFERENCE ARCHITECTURE

Consider:

1. API Gateway
2. Identity and Authentication
3. Event/Train Catalog
4. Inventory Service
5. Reservation/Hold Manager
6. Booking Service
7. Pricing Service
8. Payment Orchestrator
9. Ticket Issuance Service
10. Waiting Room
11. PostgreSQL
12. Redis
13. Kafka
14. Notification Workers
15. Search
16. Observability

Use Mermaid diagrams for:

- Overall HLD
- Seat selection
- Seat hold creation
- Booking confirmation
- Payment Saga
- Hold expiration
- Waitlist promotion
- Virtual waiting room
- Ticket validation
- Railway segment booking

Explicitly separate the high-volume browsing path from the strongly coordinated inventory mutation path.

---

# PART G — CAPACITY PLANNING

## 17. SCALE ASSUMPTIONS

Treat these as architectural targets to validate.

| Dimension                 | Target                              |
|---------------------------|-------------------------------------|
| Registered users          | 100 million                         |
| Concurrent browsing users | 1 million                           |
| Peak sale attempts        | 1 million requests/sec              |
| Booking transactions      | 10,000/sec target                   |
| Event inventory           | 10,000–100,000 seats per occurrence |
| Active holds              | Millions across events              |
| Seat availability reads   | 100,000+ RPS                        |
| Booking correctness       | No double booking                   |
| Availability              | 99.99% target                       |
| Hold creation latency     | p99 below 200 ms under defined load |
| Deployment                | Multi-AZ, multi-region extension    |

Estimate:

1. Seat availability QPS
2. Reservation attempt rate
3. Successful hold rate
4. Payment volume
5. Database lock contention
6. Redis cache size
7. Kafka event volume
8. Hold expiration rate
9. Waiting-room admission rate
10. Ticket storage
11. Notification throughput
12. Database storage and indexes
13. Network bandwidth
14. Worker capacity

Calculate separately for:

- Normal ticket sales
- Popular concert opening
- Railway Tatkal-like release
- General-admission events
- Reserved-seat events

Do not assume that one million inbound attempts can all be admitted into the inventory database simultaneously.

---

# PART H — LOW-LEVEL DESIGN

## 18. DOMAIN MODEL

Design focused concepts such as:

- Customer
- Organizer
- Event
- EventOccurrence
- Venue
- VenueSection
- Seat
- SeatInventory
- Train
- TrainRun
- RouteSegment
- BerthInventory
- ReservationHold
- ReservationItem
- Booking
- BookingItem
- Passenger
- PriceQuote
- Payment
- PaymentAttempt
- IssuedTicket
- WaitlistEntry
- OutboxEvent

Define aggregate boundaries.

Identify which aggregate or transactional component owns the no-double-booking invariant.

Provide:

- UML class diagram
- Sequence diagrams
- State machines
- Transaction boundaries
- Repository interfaces
- API contracts
- Domain invariants

Use Java 21 records, enums, and sealed interfaces where beneficial.

---

## 19. POSTGRESQL SCHEMA

Design tables such as:

- users
- organizers
- venues
- venue_seats
- events
- event_occurrences
- occurrence_seat_inventory
- reservation_holds
- reservation_items
- bookings
- booking_items
- passengers
- payments
- payment_attempts
- payment_webhook_events
- issued_tickets
- ticket_validation_events
- waitlist_entries
- train_runs
- train_stations
- berth_allocations
- outbox_events
- processed_events

Create only tables required for implemented features.

Define:

- Primary keys
- Foreign keys
- Unique constraints
- Partial unique indexes
- Check constraints
- Transaction isolation
- Indexes
- Optimistic locking
- Retention
- Audit timestamps
- State transition rules

Use Flyway migrations.

### Mandatory integrity rules

- A confirmed event seat has no competing confirmed owner.
- Active seat holds have exclusive authoritative ownership.
- Expired holds are released through safe transitions.
- Booking confirmation is idempotent.
- Ticket issuance does not duplicate active tickets unintentionally.
- Payment state is recoverable.
- Railway berth allocations do not overlap incompatible journey segments.

Include executable SQL for critical constraints.

---

# PART I — RESERVATION STATE MACHINE

## 20. HOLD LIFECYCLE

Define states such as:

- ACTIVE
- CONFIRMING
- CONFIRMED
- EXPIRED
- RELEASED
- CANCELLED

Define the legal transitions and the transaction ownership for each.

Avoid ambiguous transitions such as confirming an already expired hold without a defined policy.

---

## 21. BOOKING LIFECYCLE

Define states such as:

- INITIATED
- HOLD_ACTIVE
- PAYMENT_PENDING
- CONFIRMATION_PENDING
- CONFIRMED
- PAYMENT_FAILED
- EXPIRED
- CANCELLED
- REFUND_PENDING
- REFUNDED

Keep payment, booking, and ticket validity states separate where necessary.

Use conditional state transitions and optimistic locking.

---

# PART J — PAYMENT INTEGRATION

## 22. PAYMENT ORCHESTRATION

Implement a realistic payment sandbox or deterministic local simulator.

Support:

- Payment initiation
- Payment authorization
- Payment capture where supported
- Payment confirmation
- Payment failure
- Payment timeout
- Refund initiation
- Refund status
- Webhooks
- Provider reconciliation

### Mandatory rules

- Idempotency keys are required for financial operations.
- Duplicate client requests must not produce duplicate charges.
- Payment webhook authenticity must be verified.
- Duplicate webhooks must be deduplicated.
- Out-of-order webhook events must be handled.
- Ambiguous provider responses must be reconciled.
- Payment amounts must match authoritative booking quotes.
- Failed payments must not leave inventory indefinitely reserved.

Do not store raw payment-card details.

---

## 23. PAYMENT AND HOLD EXPIRATION RACE

Analyze this scenario:

1. Customer holds seat A10.
2. Customer starts payment.
3. Payment provider responds slowly.
4. Seat hold reaches expiration.
5. Expiry worker attempts release.
6. Payment success arrives.

Design an explicit policy.

Possible approaches:

- Transition hold to a bounded PAYMENT_PENDING extension.
- Complete authorization before hold deadline and confirm conditionally.
- Release expired inventory and automatically void/refund late payment.

Define the authoritative commit point.

**Never confirm a booking for inventory already reassigned to another customer.**

Implement race-condition tests.

---

# PART K — BOOKING SAGA

## 24. DISTRIBUTED BOOKING WORKFLOW

Implement a recoverable Saga such as:

1. Create hold
2. Create pending booking
3. Initiate payment
4. Verify payment
5. Confirm inventory
6. Confirm booking
7. Issue tickets
8. Notify customer

Define compensations:

- Release hold
- Cancel pending booking
- Void payment authorization
- Refund captured payment
- Invalidate issued tickets where applicable

Use durable workflow state.

Support:

- Retry
- Timeout
- Duplicate events
- Worker crashes
- Manual recovery
- Reconciliation

Do not rely on in-memory workflow state alone.

---

# PART L — TICKET GENERATION AND VALIDATION

## 25. TICKET ISSUANCE

Generate tickets containing:

- Ticket ID
- Booking ID
- Event occurrence/train run
- Seat or berth assignment
- Attendee/passenger reference
- Validity period
- Ticket status
- Cryptographically verifiable QR/barcode token

Do not expose sensitive passenger data inside publicly readable QR payloads.

Use signed opaque tokens or a server-verifiable reference scheme.

Implement:

- Ticket issuance
- Ticket retrieval
- Ticket cancellation
- Ticket validation
- Check-in
- Duplicate scan detection

Define offline validation limitations.

---

# PART M — VIRTUAL WAITING ROOM

## 26. TRAFFIC ADMISSION CONTROL

Design a waiting room for high-demand sales.

Support:

- Queue admission
- Queue position or approximate progress
- Signed admission token
- Token expiration
- Per-user admission limits
- Bot mitigation
- Bounded checkout admission
- Fairness
- Reconnect handling

Evaluate:

- FIFO
- Randomized admission for presales
- Weighted priority
- Verified customer groups

Do not promise perfectly fair ordering without a mechanism that can enforce it.

### Important distinction

A waiting-room token grants permission to attempt booking, not ownership of inventory.

Implement a representative waiting-room flow with rate-limited admission to reservation endpoints.

---

# PART N — FLASH SALES AND TATKAL-LIKE LOAD

## 27. HIGH-CONTENTION INVENTORY

Analyze:

- Celebrity concerts
- Sporting finals
- High-demand railway booking openings
- Limited presale allocations

Consider:

- Hot seat rows
- Hot event IDs
- Inventory partitioning
- Admission throttling
- Bounded transactions
- Database connection protection
- Queueing
- Retry storms
- Backpressure

For general admission, evaluate atomic capacity reservation.

For numbered seats, enforce seat-level ownership.

Explain why treating both inventory types identically may create unnecessary contention or incorrect semantics.

---

# PART O — REDIS CACHING

## 28. CACHE DESIGN

Use Redis for suitable ephemeral or derived state:

- Event metadata
- Availability snapshots
- Waiting-room admission
- Session metadata
- Rate limiting
- Popular search results

Define:

- Cache keys
- TTL
- Invalidation
- Cache stampede prevention
- Hot-key protection
- Redis outage fallback
- Memory limits

Do not make Redis the sole authority for confirmed bookings.

Cached seat maps must be understood as advisory.

---

# PART P — KAFKA AND EVENT PROCESSING

## 29. EVENT-DRIVEN ARCHITECTURE

Define events:

- HoldCreated
- HoldExpired
- BookingInitiated
- PaymentAuthorized
- PaymentCaptured
- PaymentFailed
- BookingConfirmed
- BookingCancelled
- TicketIssued
- RefundCompleted
- WaitlistPromoted

Implement:

- Transactional outbox
- Event IDs
- Aggregate versions
- Partition keys
- Schema versions
- Idempotent consumers
- Bounded retries
- Dead-letter handling
- Replay
- Lag monitoring

Do not assume Kafka gives exactly-once booking or payment effects automatically.

---

# PART Q — API DESIGN

## 30. REQUIRED REST APIS

Implement versioned APIs.

### Event discovery

- `GET /api/v1/events`
- `GET /api/v1/events/{eventId}`
- `GET /api/v1/events/{eventId}/occurrences`
- `GET /api/v1/occurrences/{occurrenceId}/seats`

### Reservations

- `POST /api/v1/holds`
- `GET /api/v1/holds/{holdId}`
- `DELETE /api/v1/holds/{holdId}`

### Booking

- `POST /api/v1/bookings`
- `GET /api/v1/bookings`
- `GET /api/v1/bookings/{bookingId}`
- `POST /api/v1/bookings/{bookingId}/cancel`

### Payments

- `POST /api/v1/bookings/{bookingId}/payments`
- `POST /api/v1/payments/webhooks/{provider}`

### Tickets

- `GET /api/v1/bookings/{bookingId}/tickets`
- `POST /api/v1/tickets/{ticketId}/validate`

### Waiting room

- `POST /api/v1/waiting-room/join`
- `GET /api/v1/waiting-room/status`
- `POST /api/v1/waiting-room/admission`

### Railway extension

- `GET /api/v1/trains/search`
- `GET /api/v1/train-runs/{runId}/availability`
- `POST /api/v1/rail-reservations`
- `GET /api/v1/rail-reservations/{id}`

For every API define:

- Authentication
- Authorization
- Validation
- Idempotency
- Status codes
- JSON request/response
- Pagination
- Error handling
- OpenAPI specification

Use RFC 9457 Problem Details where applicable.

---

# PART R — TECHNOLOGY STACK

## 31. JAVA 21 + SPRING BOOT STACK

Use:

| Layer                  | Technology                                    |
|------------------------|-----------------------------------------------|
| Language               | Java 21                                       |
| Framework              | Spring Boot, verified compatible release      |
| Build                  | Maven                                         |
| REST APIs              | Spring Web MVC                                |
| Security               | Spring Security                               |
| Authoritative database | PostgreSQL                                    |
| Data access            | Spring Data JPA/JDBC or jOOQ, justified       |
| Migrations             | Flyway                                        |
| Cache/admission        | Redis / Lettuce                               |
| Event processing       | Apache Kafka                                  |
| Search                 | PostgreSQL initially; OpenSearch if justified |
| Ticket artifacts       | S3-compatible storage if needed               |
| Payment integration    | Sandbox provider / simulator                  |
| Observability          | Micrometer / OpenTelemetry                    |
| Unit tests             | JUnit 5 / Mockito                             |
| Integration tests      | Testcontainers                                |
| Performance tests      | k6 or Gatling                                 |
| Containers             | Docker                                        |
| Orchestration          | Kubernetes                                    |
| Monitoring             | Prometheus / Grafana                          |

Verify actual compatible dependency versions.

Evaluate Java 21 virtual threads for blocking I/O.

Do not assume virtual threads eliminate database contention or improve inventory transaction throughput.

---

# PART S — PROJECT STRUCTURE

## 32. SOURCE CODE ORGANIZATION

Use a focused structure:

```text
ticket-booking-platform/
├── pom.xml
├── README.md
├── docs/
│   ├── architecture.md
│   ├── inventory-consistency.md
│   ├── reservation-lifecycle.md
│   ├── payment-saga.md
│   ├── railway-allocation.md
│   ├── waiting-room.md
│   ├── capacity-planning.md
│   ├── security.md
│   ├── runbook.md
│   └── decisions/
├── src/
│   ├── main/
│   │   ├── java/com/example/ticketing/
│   │   │   ├── identity/
│   │   │   ├── catalog/
│   │   │   ├── venue/
│   │   │   ├── inventory/
│   │   │   ├── reservation/
│   │   │   ├── booking/
│   │   │   ├── pricing/
│   │   │   ├── payment/
│   │   │   ├── ticket/
│   │   │   ├── waitingroom/
│   │   │   ├── railway/
│   │   │   ├── notification/
│   │   │   ├── security/
│   │   │   └── shared/
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   └── test/
│       └── java/
├── customer-web/
├── organizer-portal/
├── admin-portal/
├── benchmarks/
├── docker/
├── helm/
└── .github/workflows/
```

Keep domain boundaries explicit.

Avoid speculative microservices, excessive abstractions, and empty packages.

---

# PART T — RELIABILITY AND FAILURE ANALYSIS

## 33. REQUIRED PRODUCTION SCENARIOS

Analyze and test these 35 scenarios:

1. Two customers attempt the last seat simultaneously.
2. Ten thousand customers attempt the same seat.
3. A multi-seat hold partially fails.
4. Hold creation commits but response is lost.
5. Customer retries hold creation.
6. Hold expires while payment is pending.
7. Payment succeeds after hold expiration.
8. Expiry worker races with booking confirmation.
9. Old expiry worker tries to release reassigned inventory.
10. Payment provider processes a charge but response times out.
11. Payment webhook is delivered twice.
12. Payment webhook arrives before the synchronous payment response.
13. Payment events arrive out of order.
14. Booking commits but API response is lost.
15. Ticket issuance fails after booking confirmation.
16. Kafka becomes unavailable.
17. Redis becomes unavailable.
18. PostgreSQL primary fails.
19. Reservation Saga crashes midway.
20. Customer cancels while payment is being captured.
21. A refund request is submitted twice.
22. A ticket is scanned twice.
23. Event is cancelled after ticket issuance.
24. Venue seating map changes after sales begin.
25. Organizer blocks seats after a hold is created.
26. A general-admission category reaches capacity.
27. Waiting room receives one million concurrent users.
28. Admission token is replayed.
29. Bot traffic overwhelms reservation APIs.
30. Railway journeys overlap on one berth.
31. Waitlist promotion event is processed twice.
32. Two regional instances attempt conflicting inventory allocations.
33. A pod crashes after locking inventory.
34. Rolling deployment introduces incompatible event schema versions.
35. A booking remains stuck between payment and confirmation.

For each scenario provide:

- Failure mechanism
- Double-booking or financial risk
- User-visible impact
- Detection signals
- Prevention
- Recovery
- Automated test approach
- Residual risk

---

# PART U — AUTOMATED TESTING

## 34. UNIT TESTS

Cover:

- Seat state transitions
- Hold expiration
- Price calculation
- Reservation validation
- Booking state transitions
- Payment state transitions
- Ticket validation
- Waiting-room token validation
- Railway segment overlap
- Waitlist eligibility
- Idempotency
- Authorization

## 35. INTEGRATION TESTS

Use Testcontainers for:

- PostgreSQL
- Redis
- Kafka if implemented

Verify:

- Flyway migrations
- Real database inventory constraints
- Seat holds
- Booking confirmation
- Payment webhooks
- Outbox events
- Expiration
- Ticket issuance
- Authorization
- Reconciliation

## 36. CONCURRENCY TESTS

Specifically verify:

- Last-seat reservation
- Thousands of concurrent seat requests
- Multi-seat atomicity
- Hold expiry versus confirmation
- Duplicate booking creation
- Payment confirmation races
- General-admission oversell prevention
- Railway overlap enforcement
- Waitlist promotion races

Use real PostgreSQL transactions.

Do not rely solely on mocks to demonstrate absence of double booking.

## 37. FAILURE TESTS

Simulate:

- Database timeout
- Redis outage
- Kafka outage
- Worker crash
- Payment ambiguity
- Duplicate webhook
- Lost response
- Hold expiry
- Pod termination
- Reconciliation after restart

Document actual test results and limitations.

---

# PART V — PERFORMANCE ENGINEERING

## 38. LOAD TESTING

Create representative workloads.

### Browsing

- 1,000 RPS
- 10,000 RPS
- 100,000 RPS

### Reservation contention

- 100 concurrent users per seat
- 1,000 concurrent users per seat
- 10,000 concurrent users per seat

### Ticket sale opening

- 10,000 users
- 100,000 users
- 1 million users as a scale scenario

### Successful booking throughput

- 100 bookings/sec
- 1,000 bookings/sec
- 10,000 bookings/sec as a capacity target

Measure:

- Offered and achieved RPS
- p50/p95/p99 latency
- Hold success rate
- Conflict rate
- Double-booking incidents
- Database lock waits
- Deadlocks
- Checkout completion rate
- Payment latency
- Saga completion time
- Reservation expiry lag
- Waiting-room admission rate
- Redis latency
- Kafka consumer lag
- Database QPS
- JVM CPU/heap/GC
- Error rates

Never invent performance results.

If target-scale tests cannot execute, provide reproducible scripts and label them unverified.

---

# PART W — MULTI-REGION ARCHITECTURE

## 39. GLOBAL TICKETING

Compare:

1. Single-region multi-AZ
2. Active-passive multi-region
3. Region-partitioned inventory ownership
4. Globally coordinated active-active booking

Evaluate:

- Inventory ownership
- Cross-region latency
- Failover
- Regional replication
- Consistency
- Payment routing
- Data residency
- RPO/RTO

**Do not use asynchronous multi-region inventory replication as proof of globally exclusive seat ownership.**

Prefer an authoritative home region or explicit coordination for each inventory partition.

Define behavior when the authoritative inventory region is unavailable.

---

# PART X — SECURITY AND FRAUD PREVENTION

## 40. SECURITY REQUIREMENTS

Implement:

1. OAuth2/OIDC authentication
2. Spring Security authorization
3. Organizer/admin RBAC
4. Resource ownership validation
5. Reservation rate limiting
6. Bot mitigation
7. Waiting-room token signatures
8. Admission token replay protection
9. Payment webhook signature verification
10. Ticket token cryptographic validation
11. Secure secrets management
12. HTTPS/TLS
13. Input validation
14. Audit logging
15. Sensitive passenger data protection
16. Refund authorization
17. Anti-scalping controls where applicable
18. Per-user purchase limits
19. Account/device abuse detection
20. Data retention policies

Do not claim compliance with payment or railway regulations without actual evidence and deployment-specific
verification.

---

# PART Y — FUNCTIONAL FRONTEND

## 41. CUSTOMER INTERFACE

Implement:

- Event search
- Event details
- Venue seat map
- Real-time availability refresh
- Seat selection
- Hold countdown
- Checkout
- Sandbox payment
- Booking confirmation
- Ticket display
- QR code
- Cancellation/refund request
- Booking history

The frontend must handle stale seat availability and HTTP conflict responses gracefully.

### Organizer portal

Support:

- Event creation
- Seat map configuration
- Pricing
- Inventory controls
- Booking monitoring

### Admin portal

Support:

- Event approval
- Booking inspection
- Refund monitoring
- Operational status
- Waiting-room configuration

Use a lightweight frontend stack appropriate for a functional demonstration.

Do not substitute static screenshots for working interfaces.

---

# PART Z — DEPLOYMENT AND OBSERVABILITY

## 42. DOCKER AND KUBERNETES

Provide:

- Multi-stage Dockerfile
- Docker Compose
- PostgreSQL
- Redis
- Kafka if implemented
- Backend
- Frontend
- Kubernetes manifests or Helm
- ConfigMaps
- Secret references
- Resource requests/limits
- HPA
- PodDisruptionBudget
- Readiness/liveness probes
- Graceful shutdown
- Rolling deployment
- CI/CD workflow

Ensure a complete event ticket reservation and sandbox checkout can run locally.

---

## 43. METRICS AND ALERTING

Expose:

- Seat availability lookup latency
- Hold creation throughput
- Hold conflict rate
- Active holds
- Hold expiration lag
- Reservation failures
- Confirmed bookings
- Payment success/failure
- Payment reconciliation backlog
- Saga failures
- Ticket issuance failures
- Waiting-room queue depth
- Admission throughput
- Database lock contention
- Deadlock count
- Redis latency
- Kafka consumer lag

Provide:

- Micrometer metrics
- Prometheus configuration
- Grafana dashboards
- OpenTelemetry tracing
- Structured logging
- SLO definitions
- Alert recommendations
- Operational runbooks

Avoid high-cardinality booking IDs, user IDs, and seat IDs as unrestricted metric labels.

---

# PART AA — END-TO-END IMPLEMENTATION PLAN

## 44. DEVELOPMENT PHASES

Execute incrementally.

**Phase 1 — Requirements and Architecture**

Define inventory correctness, reservation semantics, payment milestones, event/railway domain boundaries, and capacity
assumptions.

**Phase 2 — Event and Venue Management**

Implement organizers, events, occurrences, seat maps, pricing, persistence, migrations, and security.

**Phase 3 — Reservation Engine**

Implement atomic seat holds, inventory ownership, expiration, idempotency, and concurrency tests.

**Phase 4 — Booking and Payments**

Implement booking states, payment sandbox integration, webhooks, and durable Saga processing.

**Phase 5 — Ticket Issuance**

Implement ticket generation, signed QR tokens, retrieval, validation, and cancellation.

**Phase 6 — Waiting Room and High-Concurrency Controls**

Implement admission tokens, rate limits, bounded checkout access, and overload protection.

**Phase 7 — Railway Extension**

Implement route segments, berth overlap checks, and representative reservation/waitlist functionality where included in
scope.

**Phase 8 — Reliability and Event Processing**

Implement transactional outbox, Kafka integration where justified, retries, compensation, reconciliation, and failure
recovery.

**Phase 9 — Frontend**

Implement customer seat selection, checkout, ticket display, organizer tools, and admin functionality.

**Phase 10 — Deployment**

Implement Docker Compose, Kubernetes/Helm, CI/CD, observability, and operational documentation.

**Phase 11 — Verification and Performance**

Run tests, simulate high contention, benchmark available workloads, fix significant defects, and document limitations.

For every phase:

- Implement actual source files.
- Keep the project buildable.
- Run applicable tests.
- Fix discovered defects.
- Preserve unrelated working code.
- Document important architectural decisions.
- State verified results and residual risks.

If development tools are available, create files and execute checks directly.

If unavailable, provide complete source files and executable commands without claiming execution.

---

# PART AB — REQUIRED DELIVERABLES

## 45. COMPLETE PROJECT OUTPUTS

Deliver:

### Deliverable 1 — Requirements

- Functional requirements
- Non-functional requirements
- Assumptions
- Inventory invariants
- Acceptance criteria

### Deliverable 2 — High-Level Design

- Architecture diagrams
- Inventory ownership
- Reservation flows
- Payment Saga
- Capacity planning
- Multi-region strategy

### Deliverable 3 — Low-Level Design

- UML class diagrams
- Sequence diagrams
- Database schema
- State machines
- Domain model
- API contracts
- Concurrency strategy

### Deliverable 4 — Backend Source Code

- Java 21 Spring Boot project
- Event/venue management
- Seat inventory
- Temporary holds
- Booking
- Payment orchestration
- Ticket generation
- Waiting room
- Expiration and reconciliation
- Railway extension where implemented
- Security
- Event processing

### Deliverable 5 — Functional Frontend

- Customer booking UI
- Seat map
- Countdown timer
- Checkout
- Sandbox payment
- Ticket QR display
- Organizer/admin interfaces

### Deliverable 6 — Automated Tests

- Unit tests
- Integration tests
- Concurrency tests
- Payment failure tests
- Security tests
- Recovery tests

### Deliverable 7 — Infrastructure

- Docker
- Docker Compose
- Kubernetes/Helm
- CI/CD
- Monitoring

### Deliverable 8 — Performance

- Load-test scripts
- Measured results if executed
- Capacity estimates
- Bottleneck analysis

### Deliverable 9 — Documentation

- README
- Architecture Decision Records
- Inventory consistency guide
- Hold expiration protocol
- Payment Saga documentation
- Railway segment allocation
- Deployment guide
- Operational runbook
- Known limitations

---

# PART AC — MANDATORY SYSTEM DESIGN REVIEW

## 46. ARCHITECTURE QUESTIONS

Answer these 40 questions.

1. How would you design Ticketmaster for millions of simultaneous users?
2. How would you design IRCTC Tatkal booking?
3. How do you prevent double booking?
4. Why are database constraints critical?
5. How does optimistic locking prevent lost updates?
6. When should pessimistic locking be preferred?
7. How do you implement an atomic multi-seat hold?
8. Why are Redis distributed locks insufficient by themselves?
9. How do you implement temporary seat expiration?
10. How do you prevent an old expiry worker from releasing a newer hold?
11. What happens when payment completes after hold expiration?
12. How do you handle payment timeout ambiguity?
13. How do you prevent duplicate charges?
14. How do you design a booking Saga?
15. How do you recover partially completed bookings?
16. How does transactional outbox work?
17. How do you handle duplicate Kafka events?
18. How do you guarantee ticket issuance is idempotent?
19. How do you securely generate QR-based tickets?
20. How do you prevent duplicate ticket check-ins?
21. How does a virtual waiting room work?
22. How do you prevent admission token replay?
23. How do you design fair ticket allocation?
24. How do you handle flash-sale traffic?
25. How do you avoid database connection exhaustion?
26. How do you partition event inventory?
27. How do you handle hot events and hot seats?
28. How do general-admission tickets differ from numbered seats?
29. How does railway berth segment allocation work?
30. How can the same berth be reused for non-overlapping journeys?
31. How do waitlists and RAC-style allocation work?
32. How do you enforce quota reservations?
33. How do you handle event cancellation and mass refunds?
34. How do you support multiple regions?
35. How do you maintain globally exclusive seat ownership?
36. What happens during a regional outage?
37. How do you measure reservation correctness?
38. How do you benchmark booking throughput?
39. What metrics reveal reservation-system bottlenecks?
40. What guarantees must be proven before production deployment?

For each answer explain:

- Architectural decision
- Consistency guarantees
- Concurrency handling
- Failure recovery
- Scalability
- Operational trade-offs

---

# DEFINITION OF DONE

## 47. ACCEPTANCE CRITERIA

The implementation is complete only when:

- Java 21 project builds successfully.
- PostgreSQL migrations execute.
- Event/venue management works.
- Seat availability can be queried.
- Atomic seat holds prevent competing ownership.
- Multi-seat reservations are all-or-nothing.
- Hold expiration is safe under concurrency.
- Confirmed seats cannot be booked twice.
- Checkout is idempotent.
- Payment sandbox integration works.
- Duplicate/out-of-order payment webhooks are handled.
- Booking Saga recovers after partial failure.
- Ticket issuance is idempotent.
- QR/barcode validation works.
- Duplicate ticket check-in is prevented.
- Cancellation and refund behavior is correct.
- Waiting-room admission controls function.
- Railway segment allocation is verified if implemented.
- Automated tests cover critical concurrency and payment failure paths.
- Docker Compose supports a complete booking workflow.
- Kubernetes configuration is provided.
- Metrics and health checks function.
- Load tests are reproducible.
- Performance targets are measured or marked unverified.
- Known limitations and production risks are documented.

Do not declare production readiness based solely on successful local tests.

---

# FINAL EXECUTION INSTRUCTIONS

Build a **real, production-oriented Distributed Ticket Booking Platform using Java 21 and Spring Boot**.

Prioritize:

1. **Inventory correctness:** No double booking under the supported consistency model.
2. **Concurrency safety:** Correct seat ownership and expiry transitions.
3. **Financial correctness:** No duplicate charges or uncontrolled refunds.
4. **Transaction reliability:** Durable booking and recoverable Saga workflows.
5. **Scalability:** High-volume browsing with bounded inventory mutations.
6. **Security:** Authenticated booking, signed admission, and verifiable tickets.
7. **Fault tolerance:** Recoverable failures across payments, databases, messaging, and workers.
8. **Maintainability:** Pragmatic bounded contexts and focused Java 21 modules.
9. **Observability:** Diagnosable contention, booking failures, and payment inconsistencies.
10. **Reproducibility:** Runnable source, functional frontend, tests, benchmarks, and infrastructure.

Begin with a concise architectural assessment and select the authoritative inventory strategy.

Then implement the entire system phase by phase.

Use available development tools to create files, compile, run tests, and fix defects.

At completion report:

- Implemented features
- Architecture decisions and trade-offs
- Source structure and important files
- Exact build and run commands
- Tests actually executed
- Benchmark measurements, if available
- Known limitations
- Remaining production risks

**Do not stop after HLD or LLD. Deliver the complete end-to-end Ticket Booking System, including backend, functional
frontend, reservation engine, payment workflow, automated tests, deployment infrastructure, and operational
documentation.**