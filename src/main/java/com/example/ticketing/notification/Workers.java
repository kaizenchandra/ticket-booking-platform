package com.example.ticketing.notification;

import com.example.ticketing.booking.Bookings;
import com.example.ticketing.reservation.Reservations;
import com.example.ticketing.shared.Store;
import com.example.ticketing.ticket.Tickets;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "ticketing.workers.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class Workers {
    private final Store db;
    private final Bookings bookings;
    private final Tickets tickets;
    private final Reservations holds;
    private final TransactionTemplate tx;

    public Workers(
            Store db, Bookings bookings, Tickets tickets, Reservations holds, TransactionTemplate tx) {
        this.db = db;
        this.bookings = bookings;
        this.tickets = tickets;
        this.holds = holds;
        this.tx = tx;
    }

    @Scheduled(fixedDelay = 1000)
    public void expire() {
        holds.expire();
    }

    @Scheduled(fixedDelay = 2000)
    public void cancelledEvents() {
        for (var b :
                db.list(
                        "SELECT b.id,b.customer FROM bookings b JOIN holds h ON h.id=b.hold_id JOIN occurrences"
                                + " o ON o.id=h.occurrence_id JOIN events e ON e.id=o.event_id WHERE"
                                + " e.status='CANCELLED' AND b.state IN ('PAYMENT_PENDING','CONFIRMED') LIMIT 100"))
            bookings.cancel(
                    b.get("customer").toString(), "event-cancel-" + b.get("id"), (UUID) b.get("id"));
    }

    @Scheduled(fixedDelay = 1000)
    public void reconcile() {
        for (var p :
                db.list(
                        "SELECT id,outcome FROM payments WHERE state='PENDING' AND due_at<=clock_timestamp()"
                                + " ORDER BY due_at LIMIT 100"))
            bookings.settle((UUID) p.get("id"), p.get("outcome").equals("FAIL") ? "FAILED" : "CAPTURED");
    }

    @Scheduled(fixedDelay = 500)
    public void outbox() {
        tx.executeWithoutResult(
                s -> {
                    for (var e :
                            db.list(
                                    "SELECT * FROM outbox_events WHERE processed_at IS NULL ORDER BY created_at LIMIT"
                                            + " 100 FOR UPDATE SKIP LOCKED")) {
                        UUID id = (UUID) e.get("id");
                        if (e.get("type").equals("BookingConfirmed"))
                            tickets.issue((UUID) e.get("aggregate_id"));
                        db.update(
                                "INSERT INTO notifications(event_id,customer,message) SELECT"
                                        + " id,payload->>'customer',type FROM outbox_events WHERE id=? ON CONFLICT DO"
                                        + " NOTHING",
                                id);
                        db.update("UPDATE outbox_events SET processed_at=clock_timestamp() WHERE id=?", id);
                    }
                });
    }
}
