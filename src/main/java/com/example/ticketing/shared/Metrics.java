package com.example.ticketing.shared;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class Metrics {
    public Metrics(Store db, MeterRegistry registry) {
        gauge(
                db,
                registry,
                "ticketing.holds.active",
                "SELECT count(*) n FROM holds WHERE state='ACTIVE' AND expires_at>clock_timestamp()");
        gauge(
                db,
                registry,
                "ticketing.expiry.lag.seconds",
                "SELECT coalesce(max(extract(epoch FROM clock_timestamp()-expires_at)),0) n FROM holds"
                        + " WHERE state='ACTIVE' AND expires_at<=clock_timestamp()");
        gauge(
                db,
                registry,
                "ticketing.payment.backlog",
                "SELECT count(*) n FROM payments WHERE state='PENDING'");
        gauge(
                db,
                registry,
                "ticketing.outbox.backlog",
                "SELECT count(*) n FROM outbox_events WHERE processed_at IS NULL");
        gauge(
                db,
                registry,
                "ticketing.waiting.depth",
                "SELECT count(*) n FROM waiting_entries WHERE expires_at IS NULL");
    }

    void gauge(Store db, MeterRegistry r, String name, String sql) {
        io.micrometer.core.instrument.Gauge.builder(
                        name, db, d -> ((Number) d.one(sql).get("n")).doubleValue())
                .register(r);
    }
}
