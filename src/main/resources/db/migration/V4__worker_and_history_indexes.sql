CREATE INDEX payments_due ON payments (due_at) WHERE state='PENDING';
CREATE INDEX inventory_owner ON inventory (hold_id) WHERE hold_id IS NOT NULL;
CREATE INDEX bookings_history ON bookings (customer, created_at DESC);
CREATE INDEX holds_customer_occurrence ON holds (customer, occurrence_id);
CREATE INDEX outbox_aggregate ON outbox_events (aggregate_id, type);
CREATE INDEX notifications_customer ON notifications (customer, created_at DESC);
