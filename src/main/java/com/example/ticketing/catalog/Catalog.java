package com.example.ticketing.catalog;

import com.example.ticketing.shared.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.ticketing.shared.Problem.fail;

@Service
public class Catalog {
    private final Store db;

    public Catalog(Store db) {
        this.db = db;
    }

    @Transactional
    public Map<String, Object> create(
            String organizer,
            String title,
            String venue,
            int seats,
            long price,
            Instant starts,
            String category) {
        if (title == null
                || title.isBlank()
                || title.length() > 200
                || venue == null
                || venue.isBlank()
                || venue.length() > 200
                || seats < 1
                || seats > 1000
                || price < 1
                || price > 100000000
                || starts == null
                || !starts.isAfter(Instant.now())
                || category == null
                || !List.of("RESERVED", "GA").contains(category))
            throw fail(400, "Invalid event (1–1000 seats, positive minor-unit price, future date)");
        UUID event = UUID.randomUUID(), occ = UUID.randomUUID();
        db.update(
                "INSERT INTO events(id,organizer,title,venue,status) VALUES(?,?,?,?,'DRAFT')",
                event,
                organizer,
                title,
                venue);
        db.update(
                "INSERT INTO occurrences(id,event_id,starts_at) VALUES(?,?,?)",
                occ,
                event,
                java.sql.Timestamp.from(starts));
        for (int n = 1; n <= seats; n++)
            db.update(
                    "INSERT INTO inventory(id,occurrence_id,section,label,category,price)"
                            + " VALUES(?,?,'Main',?,?,?)",
                    UUID.randomUUID(),
                    occ,
                    (category.equals("GA") ? "GA-" : "A") + n,
                    category,
                    price);
        return db.one("SELECT * FROM events WHERE id=?", event);
    }

    @Transactional
    public void publish(UUID id) {
        db.update("UPDATE events SET status='PUBLISHED' WHERE id=? AND status='DRAFT'", id);
    }

    @Transactional
    public void cancel(UUID id, String user, boolean admin) {
        var e = db.one("SELECT * FROM events WHERE id=? FOR UPDATE", id);
        if (!admin && !e.get("organizer").equals(user)) throw fail(403, "Not your event");
        db.update("UPDATE events SET status='CANCELLED' WHERE id=?", id);
        db.event(id, "EventCancelled", user);
    }

    @Transactional
    public void block(UUID id, String user, boolean blocked) {
        var i =
                db.one(
                        "SELECT e.organizer FROM inventory i JOIN occurrences o ON o.id=i.occurrence_id JOIN"
                                + " events e ON e.id=o.event_id WHERE i.id=?",
                        id);
        if (!i.get("organizer").equals(user)) throw fail(403, "Not your event");
        int n =
                db.update(
                        "UPDATE inventory SET state=? WHERE id=? AND state=?",
                        blocked ? "BLOCKED" : "AVAILABLE",
                        id,
                        blocked ? "AVAILABLE" : "BLOCKED");
        if (n != 1)
            throw fail(
                    409, "Only available seats may be blocked; held and sold allocations are immutable");
    }
}
