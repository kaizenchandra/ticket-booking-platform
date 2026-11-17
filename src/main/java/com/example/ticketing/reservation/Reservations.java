package com.example.ticketing.reservation;

import com.example.ticketing.shared.Store;
import com.example.ticketing.waitingroom.Admission;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.example.ticketing.shared.Problem.fail;

@Service
public class Reservations {
    private final Store db;
    private final Admission admission;
    private final int ttl;
    private final MeterRegistry metrics;

    public Reservations(
            Store db,
            Admission admission,
            @Value("${ticketing.hold-seconds}") int ttl,
            MeterRegistry metrics) {
        this.db = db;
        this.admission = admission;
        this.ttl = ttl;
        this.metrics = metrics;
    }

    public Map<String, Object> get(UUID id, String user) {
        var h =
                db.one(
                        "SELECT *, greatest(0,extract(epoch FROM expires_at-clock_timestamp())) AS"
                                + " seconds_remaining FROM holds WHERE id=?",
                        id);
        db.own(h, user);
        var result = new LinkedHashMap<>(h);
        result.put(
                "items",
                db.list(
                        "SELECT i.id,i.label,i.section,hi.price FROM hold_items hi JOIN inventory i ON"
                                + " i.id=hi.inventory_id WHERE hi.hold_id=? ORDER BY i.id",
                        id));
        return result;
    }

    @Transactional
    public Map<String, Object> create(
            String user, String key, UUID occurrence, List<UUID> seats, String token) {
        if (seats == null
                || seats.isEmpty()
                || seats.size() > 6
                || new HashSet<>(seats).size() != seats.size())
            throw fail(400, "Choose 1–6 distinct inventory IDs");
        var ids = seats.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        String fp = occurrence + ":" + ids;
        UUID old = db.replay(user, "hold", key, fp);
        if (old != null) return get(old, user);
        admission.consume(user, token);
        // Event shared lock prevents publication/cancellation from racing a new sale.
        var event =
                db.one(
                        "SELECT e.*,o.sale_at<=clock_timestamp() AS open,o.starts_at>clock_timestamp() AS"
                                + " future FROM occurrences o JOIN events e ON e.id=o.event_id WHERE o.id=? FOR"
                                + " SHARE OF e",
                        occurrence);
        if (!event.get("status").equals("PUBLISHED")
                || !Boolean.TRUE.equals(event.get("open"))
                || !Boolean.TRUE.equals(event.get("future"))) throw fail(409, "Sale is not open");
        db.sql.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,1))", user + ":" + occurrence);
        long count =
                ((Number)
                        db.one(
                                        "SELECT count(*) AS n FROM hold_items hi JOIN holds h ON h.id=hi.hold_id"
                                                + " WHERE h.customer=? AND h.occurrence_id=? AND (h.state='CONFIRMED'"
                                                + " OR (h.state='ACTIVE' AND h.expires_at>clock_timestamp()))",
                                        user,
                                        occurrence)
                                .get("n"))
                        .longValue();
        if (count + ids.size() > 6) throw fail(409, "Purchase limit is six per occurrence");
        long total = 0;
        for (UUID seat : ids) {
            var s =
                    db.one(
                            "SELECT *,expires_at<=clock_timestamp() AS expired FROM inventory WHERE id=? AND"
                                    + " occurrence_id=? FOR UPDATE",
                            seat,
                            occurrence);
            if (!s.get("state").equals("AVAILABLE")
                    && !(s.get("state").equals("HELD") && Boolean.TRUE.equals(s.get("expired")))) {
                metrics.counter("ticketing.hold.conflicts").increment();
                throw fail(409, "Selection changed; refresh and choose again");
            }
            total = Math.addExact(total, ((Number) s.get("price")).longValue());
        }
        UUID hold = UUID.randomUUID();
        db.update(
                "INSERT INTO holds(id,customer,occurrence_id,state,expires_at,total)"
                        + " VALUES(?,?,?,'ACTIVE',clock_timestamp()+(? * interval '1 second'),?)",
                hold,
                user,
                occurrence,
                ttl,
                total);
        for (UUID seat : ids) {
            db.update("INSERT INTO hold_items SELECT ?,id,price FROM inventory WHERE id=?", hold, seat);
            db.update(
                    "UPDATE inventory SET state='HELD',hold_id=?,expires_at=(SELECT expires_at FROM holds"
                            + " WHERE id=?) WHERE id=?",
                    hold,
                    hold,
                    seat);
        }
        db.remember(user, "hold", key, fp, hold);
        db.event(hold, "HoldCreated", user);
        metrics.counter("ticketing.holds.created").increment();
        return get(hold, user);
    }

    @Transactional
    public void release(UUID id, String user) {
        var h = db.one("SELECT * FROM holds WHERE id=? FOR UPDATE", id);
        db.own(h, user);
        if (h.get("state").equals("CONFIRMED")) throw fail(409, "Cancel the confirmed booking instead");
        if (h.get("state").equals("ACTIVE")) {
            clear(id);
            db.update("UPDATE holds SET state='RELEASED' WHERE id=?", id);
            db.event(id, "HoldReleased", user);
        }
    }

    public void clear(UUID hold) {
        lockItems(hold);
        db.update(
                "UPDATE inventory SET state='AVAILABLE',hold_id=NULL,expires_at=NULL WHERE hold_id=? AND"
                        + " state='HELD'",
                hold);
    }

    public void lockItems(UUID hold) {
        db.list(
                "SELECT i.id FROM inventory i JOIN hold_items hi ON hi.inventory_id=i.id WHERE hi.hold_id=?"
                        + " ORDER BY i.id FOR UPDATE OF i",
                hold);
    }

    @Transactional
    public int expire() {
        var rows =
                db.list(
                        "SELECT * FROM holds WHERE state='ACTIVE' AND expires_at<=clock_timestamp() ORDER BY"
                                + " expires_at LIMIT 100 FOR UPDATE SKIP LOCKED");
        for (var h : rows) {
            UUID id = db.id(h, "id");
            clear(id);
            db.update("UPDATE holds SET state='EXPIRED' WHERE id=?", id);
            db.update(
                    "UPDATE bookings SET state='EXPIRED' WHERE hold_id=? AND state='PAYMENT_PENDING'", id);
            db.event(id, "HoldExpired", h.get("customer").toString());
        }
        return rows.size();
    }
}
