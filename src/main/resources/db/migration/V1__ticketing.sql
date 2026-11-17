CREATE TABLE users
(
    id          text PRIMARY KEY,
    password    text    NOT NULL,
    role        text    NOT NULL CHECK (role IN ('CUSTOMER', 'ORGANIZER', 'ADMIN')),
    active      boolean NOT NULL DEFAULT true,
    preferences text    NOT NULL DEFAULT '{}'
);
CREATE TABLE events
(
    id         uuid PRIMARY KEY,
    organizer  text        NOT NULL,
    title      text        NOT NULL,
    venue      text        NOT NULL,
    status     text        NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE occurrences
(
    id        uuid PRIMARY KEY,
    event_id  uuid        NOT NULL REFERENCES events,
    starts_at timestamptz NOT NULL,
    sale_at   timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE holds
(
    id            uuid PRIMARY KEY,
    customer      text        NOT NULL,
    occurrence_id uuid        NOT NULL REFERENCES occurrences,
    state         text        NOT NULL CHECK (state IN ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'RELEASED')),
    expires_at    timestamptz NOT NULL,
    total         bigint      NOT NULL CHECK (total > 0),
    currency      text        NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    created_at    timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX holds_expiry ON holds (expires_at) WHERE state='ACTIVE';
CREATE TABLE inventory
(
    id            uuid PRIMARY KEY,
    occurrence_id uuid   NOT NULL REFERENCES occurrences,
    section       text   NOT NULL,
    label         text   NOT NULL,
    category      text   NOT NULL CHECK (category IN ('RESERVED', 'GA')),
    price         bigint NOT NULL CHECK (price > 0),
    state         text   NOT NULL DEFAULT 'AVAILABLE' CHECK (state IN ('AVAILABLE', 'HELD', 'SOLD', 'BLOCKED')),
    hold_id       uuid REFERENCES holds,
    expires_at    timestamptz,
    UNIQUE (occurrence_id, label),
    CHECK ((state = 'HELD' AND hold_id IS NOT NULL AND expires_at IS NOT NULL) OR
           (state = 'SOLD' AND hold_id IS NOT NULL AND expires_at IS NULL) OR
           (state IN ('AVAILABLE', 'BLOCKED') AND hold_id IS NULL AND expires_at IS NULL))
);
CREATE INDEX inventory_occurrence ON inventory (occurrence_id, state);
CREATE TABLE hold_items
(
    hold_id      uuid   NOT NULL REFERENCES holds,
    inventory_id uuid   NOT NULL REFERENCES inventory,
    price        bigint NOT NULL,
    PRIMARY KEY (hold_id, inventory_id)
);
CREATE TABLE bookings
(
    id         uuid PRIMARY KEY,
    hold_id    uuid        NOT NULL UNIQUE REFERENCES holds,
    customer   text        NOT NULL,
    state      text        NOT NULL CHECK (state IN
                                           ('PAYMENT_PENDING', 'CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED', 'REFUNDED')),
    total      bigint      NOT NULL,
    currency   text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE booking_items
(
    booking_id   uuid    NOT NULL REFERENCES bookings,
    inventory_id uuid    NOT NULL REFERENCES inventory,
    active       boolean NOT NULL DEFAULT true,
    PRIMARY KEY (booking_id, inventory_id)
);
CREATE UNIQUE INDEX exclusive_confirmed_seat ON booking_items (inventory_id) WHERE active;
CREATE TABLE payments
(
    id         uuid PRIMARY KEY,
    booking_id uuid        NOT NULL UNIQUE REFERENCES bookings,
    state      text        NOT NULL CHECK (state IN ('PENDING', 'CAPTURED', 'FAILED', 'REFUNDED')),
    outcome    text        NOT NULL CHECK (outcome IN ('SUCCESS', 'FAIL', 'TIMEOUT')),
    total      bigint      NOT NULL,
    due_at     timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE payment_webhooks
(
    id          text PRIMARY KEY,
    payment_id  uuid        NOT NULL REFERENCES payments,
    state       text        NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE tickets
(
    id           uuid PRIMARY KEY,
    booking_id   uuid NOT NULL REFERENCES bookings,
    inventory_id uuid NOT NULL REFERENCES inventory,
    state        text NOT NULL DEFAULT 'VALID' CHECK (state IN ('VALID', 'USED', 'CANCELLED')),
    scanned_at   timestamptz,
    UNIQUE (booking_id, inventory_id)
);
CREATE TABLE ticket_scans
(
    id         uuid PRIMARY KEY,
    ticket_id  uuid        NOT NULL REFERENCES tickets,
    scanner    text        NOT NULL,
    accepted   boolean     NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE idempotency
(
    customer    text NOT NULL,
    operation   text NOT NULL,
    key         text NOT NULL,
    fingerprint text NOT NULL,
    result_id   uuid NOT NULL,
    PRIMARY KEY (customer, operation, key)
);
CREATE TABLE outbox_events
(
    id             uuid PRIMARY KEY,
    aggregate_id   uuid        NOT NULL,
    type           text        NOT NULL,
    schema_version int         NOT NULL DEFAULT 1,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    processed_at   timestamptz
);
CREATE INDEX outbox_pending ON outbox_events (created_at) WHERE processed_at IS NULL;
CREATE TABLE notifications
(
    event_id   uuid PRIMARY KEY REFERENCES outbox_events,
    customer   text        NOT NULL,
    message    text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE waiting_entries
(
    customer    text PRIMARY KEY,
    position    bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    admitted_at timestamptz,
    expires_at  timestamptz,
    token_id    uuid,
    consumed    boolean NOT NULL DEFAULT false
);
CREATE TABLE admission_clock
(
    id        int PRIMARY KEY CHECK (id = 1),
    window_at timestamptz NOT NULL,
    admitted  int         NOT NULL
);
INSERT INTO admission_clock
VALUES (1, clock_timestamp(), 0);
CREATE TABLE rate_limits
(
    customer  text PRIMARY KEY,
    window_at timestamptz NOT NULL,
    attempts  int         NOT NULL
);
