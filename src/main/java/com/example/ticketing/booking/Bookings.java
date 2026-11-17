package com.example.ticketing.booking;

import com.example.ticketing.reservation.Reservations;
import com.example.ticketing.shared.Store;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.ticketing.shared.Problem.fail;

@Service
public class Bookings {
    private final Store db;
    private final Reservations holds;
    private final MeterRegistry metrics;

    public Bookings(Store db, Reservations holds, MeterRegistry metrics) {
        this.db = db;
        this.holds = holds;
        this.metrics = metrics;
    }

    public Map<String, Object> get(UUID id, String user) {
        var b = db.one("SELECT * FROM bookings WHERE id=?", id);
        db.own(b, user);
        var r = new LinkedHashMap<>(b);
        r.put("payments", db.list("SELECT * FROM payments WHERE booking_id=?", id));
        r.put(
                "items",
                db.list(
                        "SELECT i.label,i.section FROM hold_items hi JOIN inventory i ON i.id=hi.inventory_id"
                                + " WHERE hi.hold_id=?",
                        b.get("hold_id")));
        return r;
    }

    @Transactional
    public Map<String, Object> create(String user, String key, UUID hold) {
        UUID replay = db.replay(user, "booking", key, hold.toString());
        if (replay != null) return get(replay, user);
        var h =
                db.one(
                        "SELECT *,expires_at>clock_timestamp() AS valid FROM holds WHERE id=? FOR UPDATE",
                        hold);
        db.own(h, user);
        var existing = db.list("SELECT id FROM bookings WHERE hold_id=?", hold);
        UUID id;
        if (!existing.isEmpty()) id = db.id(existing.getFirst(), "id");
        else {
            if (!h.get("state").equals("ACTIVE") || !Boolean.TRUE.equals(h.get("valid")))
                throw fail(409, "Hold is no longer active");
            id = UUID.randomUUID();
            db.update(
                    "INSERT INTO bookings(id,hold_id,customer,state,total,currency)"
                            + " VALUES(?,?,?,'PAYMENT_PENDING',?,?)",
                    id,
                    hold,
                    user,
                    h.get("total"),
                    h.get("currency"));
            db.event(id, "BookingInitiated", user);
        }
        db.remember(user, "booking", key, hold.toString(), id);
        return get(id, user);
    }

    @Transactional
    public Map<String, Object> pay(String user, String key, UUID booking, String outcome) {
        if (outcome == null || !List.of("SUCCESS", "FAIL", "TIMEOUT").contains(outcome))
            throw fail(400, "Unknown simulator outcome");
        UUID replay = db.replay(user, "payment", key, booking + ":" + outcome);
        if (replay != null) return db.one("SELECT * FROM payments WHERE id=?", replay);
        // All booking mutations acquire hold, then booking, then inventory locks.
        var ref = db.one("SELECT hold_id FROM bookings WHERE id=?", booking);
        var h =
                db.one(
                        "SELECT *,expires_at>clock_timestamp() AS valid FROM holds WHERE id=? FOR UPDATE",
                        ref.get("hold_id"));
        var b = db.one("SELECT * FROM bookings WHERE id=? FOR UPDATE", booking);
        db.own(b, user);
        var previous = db.list("SELECT * FROM payments WHERE booking_id=?", booking);
        UUID id;
        if (!previous.isEmpty()) id = db.id(previous.getFirst(), "id");
        else {
            if (!b.get("state").equals("PAYMENT_PENDING")
                    || !h.get("state").equals("ACTIVE")
                    || !Boolean.TRUE.equals(h.get("valid"))) throw fail(409, "Checkout expired");
            id = UUID.randomUUID();
            db.update(
                    "INSERT INTO payments(id,booking_id,state,outcome,total,due_at)"
                            + " VALUES(?,?,'PENDING',?,?,clock_timestamp()+ (? * interval '1 second'))",
                    id,
                    booking,
                    outcome,
                    b.get("total"),
                    outcome.equals("TIMEOUT") ? 30 : 1);
            db.event(booking, "PaymentInitiated", user);
        }
        db.remember(user, "payment", key, booking + ":" + outcome, id);
        return db.one("SELECT * FROM payments WHERE id=?", id);
    }

    @Transactional
    public void settle(UUID payment, String state) {
        if (state == null || !List.of("CAPTURED", "FAILED").contains(state))
            throw fail(400, "Unsupported provider state");
        var p0 = db.one("SELECT * FROM payments WHERE id=?", payment);
        var b0 = db.one("SELECT * FROM bookings WHERE id=?", p0.get("booking_id"));
        var event =
                db.one(
                        "SELECT e.status FROM events e JOIN occurrences o ON o.event_id=e.id JOIN holds h ON"
                                + " h.occurrence_id=o.id WHERE h.id=? FOR SHARE OF e",
                        b0.get("hold_id"));
        var h =
                db.one(
                        "SELECT *,expires_at>clock_timestamp() AS valid FROM holds WHERE id=? FOR UPDATE",
                        b0.get("hold_id"));
        var b = db.one("SELECT * FROM bookings WHERE id=? FOR UPDATE", b0.get("id"));
        var p = db.one("SELECT * FROM payments WHERE id=? FOR UPDATE", payment);
        if (p.get("state").equals("FAILED") && state.equals("CAPTURED")) {
            db.update("UPDATE payments SET state='REFUNDED' WHERE id=?", payment);
            db.update("UPDATE bookings SET state='REFUNDED' WHERE id=?", b.get("id"));
            db.event(db.id(b, "id"), "LatePaymentRefunded", b.get("customer").toString());
            return;
        }
        if (!p.get("state").equals("PENDING")) return; // Terminal provider states never regress.
        UUID bid = db.id(b, "id"), hid = db.id(h, "id");
        String user = b.get("customer").toString();
        if (state.equals("FAILED")) {
            db.update("UPDATE payments SET state='FAILED' WHERE id=?", payment);
            if (b.get("state").equals("PAYMENT_PENDING")) {
                holds.clear(hid);
                db.update("UPDATE holds SET state='RELEASED' WHERE id=? AND state='ACTIVE'", hid);
                db.update("UPDATE bookings SET state='PAYMENT_FAILED' WHERE id=?", bid);
            }
            db.event(bid, "PaymentFailed", user);
            return;
        }
        holds.lockItems(hid);
        long missing =
                ((Number)
                        db.one(
                                        "SELECT count(*) AS n FROM hold_items hi JOIN inventory i ON"
                                                + " i.id=hi.inventory_id WHERE hi.hold_id=? AND (i.state<>'HELD' OR"
                                                + " i.hold_id IS DISTINCT FROM ?)",
                                        hid,
                                        hid)
                                .get("n"))
                        .longValue();
        // The conditional UPDATE below is the authoritative commit point, using database wall time
        // after locks.
        int committed = 0;
        if (event.get("status").equals("PUBLISHED")
                && b.get("state").equals("PAYMENT_PENDING")
                && missing == 0)
            committed =
                    db.update(
                            "UPDATE holds SET state='CONFIRMED' WHERE id=? AND state='ACTIVE' AND"
                                    + " expires_at>clock_timestamp()",
                            hid);
        if (committed == 0) {
            db.update("UPDATE payments SET state='REFUNDED' WHERE id=?", payment);
            holds.clear(hid);
            db.update("UPDATE holds SET state='EXPIRED' WHERE id=? AND state='ACTIVE'", hid);
            db.update("UPDATE bookings SET state='REFUNDED' WHERE id=?", bid);
            db.event(bid, "LatePaymentRefunded", user);
            return;
        }
        db.update(
                "UPDATE inventory SET state='SOLD',expires_at=NULL WHERE hold_id=? AND state='HELD'", hid);
        db.update(
                "INSERT INTO booking_items SELECT ?,inventory_id,true FROM hold_items WHERE hold_id=?",
                bid,
                hid);
        db.update("UPDATE bookings SET state='CONFIRMED' WHERE id=?", bid);
        db.update("UPDATE payments SET state='CAPTURED' WHERE id=?", payment);
        db.event(bid, "BookingConfirmed", user);
        metrics.counter("ticketing.bookings.confirmed").increment();
    }

    @Transactional
    public Map<String, Object> cancel(String user, String key, UUID bid) {
        UUID old = db.replay(user, "cancel", key, bid.toString());
        if (old != null) return get(old, user);
        var b0 = db.one("SELECT hold_id FROM bookings WHERE id=?", bid);
        UUID hid = db.id(b0, "hold_id");
        db.one("SELECT * FROM holds WHERE id=? FOR UPDATE", hid);
        var b = db.one("SELECT * FROM bookings WHERE id=? FOR UPDATE", bid);
        db.own(b, user);
        holds.lockItems(hid);
        db.update("UPDATE booking_items SET active=false WHERE booking_id=?", bid);
        db.update(
                "UPDATE inventory SET state='AVAILABLE',hold_id=NULL,expires_at=NULL WHERE hold_id=?", hid);
        db.update("UPDATE holds SET state='RELEASED' WHERE id=?", hid);
        db.update("UPDATE payments SET state='REFUNDED' WHERE booking_id=? AND state='CAPTURED'", bid);
        db.update("UPDATE bookings SET state='REFUNDED' WHERE id=?", bid);
        db.update("UPDATE tickets SET state='CANCELLED' WHERE booking_id=?", bid);
        db.event(bid, "BookingCancelled", user);
        db.remember(user, "cancel", key, bid.toString(), bid);
        return get(bid, user);
    }
}
