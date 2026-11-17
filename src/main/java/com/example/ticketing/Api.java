package com.example.ticketing;

import com.example.ticketing.booking.Bookings;
import com.example.ticketing.catalog.Catalog;
import com.example.ticketing.payment.Webhooks;
import com.example.ticketing.reservation.Reservations;
import com.example.ticketing.shared.Store;
import com.example.ticketing.ticket.Tickets;
import com.example.ticketing.waitingroom.Admission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.ticketing.shared.Problem.fail;

@RestController
@RequestMapping("/api/v1")
public class Api {
    private final Store db;
    private final Reservations holds;
    private final Bookings bookings;
    private final Tickets tickets;
    private final Admission admission;
    private final Catalog catalog;
    private final Webhooks hooks;
    private final PasswordEncoder passwords;
    private final boolean demo;

    public Api(
            Store db,
            Reservations holds,
            Bookings bookings,
            Tickets tickets,
            Admission admission,
            Catalog catalog,
            Webhooks hooks,
            PasswordEncoder passwords,
            @Value("${ticketing.demo}") boolean demo) {
        this.db = db;
        this.holds = holds;
        this.bookings = bookings;
        this.tickets = tickets;
        this.admission = admission;
        this.catalog = catalog;
        this.hooks = hooks;
        this.passwords = passwords;
        this.demo = demo;
    }

    boolean admin(Authentication a) {
        return a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMIN"));
    }

    @PostMapping("/register")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Map<String, Object> register(@RequestBody Registration r) {
        if (!demo) throw fail(404, "Register through your identity provider");
        if (r.username() == null
                || !r.username().matches("[a-zA-Z0-9_-]{3,64}")
                || r.password() == null
                || r.password().length() < 10
                || r.password().length() > 72)
            throw fail(400, "Use a 3–64 character username and 10–72 character password");
        db.update(
                "INSERT INTO users(id,password,role) VALUES(?,?,'CUSTOMER')",
                r.username(),
                passwords.encode(r.password()));
        return Map.of("username", r.username());
    }

    @GetMapping("/me")
    Map<String, Object> me(Authentication a) {
        return Map.of("username", a.getName(), "roles", a.getAuthorities());
    }

    @GetMapping("/profile")
    Map<String, Object> profile(Authentication a) {
        return db.one("SELECT id,preferences,active FROM users WHERE id=?", a.getName());
    }

    @PutMapping("/profile")
    void prefs(Authentication a, @RequestBody Map<String, String> body) {
        String pref = body.getOrDefault("preferences", "{}");
        if (pref.length() > 2000) throw fail(400, "Preferences too long");
        db.update("UPDATE users SET preferences=? WHERE id=?", pref, a.getName());
    }

    @DeleteMapping("/profile")
    void deactivate(Authentication a) {
        db.update("UPDATE users SET active=false WHERE id=?", a.getName());
    }

    @GetMapping("/events")
    Object events(
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page) {
        if (page < 0 || page > 10000 || q.length() > 100) throw fail(400, "Invalid search");
        return db.list(
                "SELECT * FROM events WHERE status='PUBLISHED' AND title ILIKE ? ORDER BY created_at,id"
                        + " LIMIT 50 OFFSET ?",
                "%" + q + "%",
                page * 50);
    }

    @GetMapping("/events/{id}")
    Object event(@PathVariable UUID id) {
        return db.one("SELECT * FROM events WHERE id=? AND status='PUBLISHED'", id);
    }

    @GetMapping("/events/{id}/occurrences")
    Object occurrences(@PathVariable UUID id) {
        return db.list("SELECT * FROM occurrences WHERE event_id=? ORDER BY starts_at LIMIT 100", id);
    }

    @GetMapping("/occurrences/{id}/seats")
    Object seats(@PathVariable UUID id) {
        return db.list(
                "SELECT id,section,label,category,price,CASE WHEN state='HELD' AND"
                        + " expires_at<=clock_timestamp() THEN 'AVAILABLE' ELSE state END AS state FROM"
                        + " inventory WHERE occurrence_id=? ORDER BY section,label LIMIT 10000",
                id);
    }

    @PostMapping("/holds")
    Object hold(
            Authentication a,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Admission-Token", required = false) String token,
            @RequestBody HoldRequest r) {
        admission.rate(a.getName());
        if (r.occurrenceId() == null) throw fail(400, "occurrenceId required");
        return holds.create(a.getName(), key, r.occurrenceId(), r.seatIds(), token);
    }

    @GetMapping("/holds/{id}")
    Object hold(Authentication a, @PathVariable UUID id) {
        return holds.get(id, a.getName());
    }

    @DeleteMapping("/holds/{id}")
    void release(Authentication a, @PathVariable UUID id) {
        holds.release(id, a.getName());
    }

    @PostMapping("/bookings")
    Object book(
            Authentication a,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody BookingRequest r) {
        if (r.holdId() == null) throw fail(400, "holdId required");
        return bookings.create(a.getName(), key, r.holdId());
    }

    @GetMapping("/bookings")
    Object history(Authentication a, @RequestParam(defaultValue = "0") int page) {
        if (page < 0 || page > 10000) throw fail(400, "Invalid page");
        return db.list(
                "SELECT * FROM bookings WHERE customer=? ORDER BY created_at DESC,id LIMIT 50 OFFSET ?",
                a.getName(),
                page * 50);
    }

    @GetMapping("/bookings/{id}")
    Object booking(Authentication a, @PathVariable UUID id) {
        return bookings.get(id, a.getName());
    }

    @PostMapping("/bookings/{id}/cancel")
    Object cancel(
            Authentication a, @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
        return bookings.cancel(a.getName(), key, id);
    }

    @PostMapping("/bookings/{id}/payments")
    Object pay(
            Authentication a,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody PayRequest r) {
        return bookings.pay(a.getName(), key, id, r.outcome());
    }

    @PostMapping("/payments/webhooks/simulator")
    void webhook(
            @RequestBody String body,
            @RequestHeader("X-Timestamp") String ts,
            @RequestHeader("X-Signature") String sig)
            throws Exception {
        hooks.accept(body, ts, sig);
    }

    @GetMapping("/bookings/{id}/tickets")
    Object tickets(Authentication a, @PathVariable UUID id) {
        return tickets.list(id, a.getName());
    }

    @GetMapping(value = "/tickets/{id}/qr", produces = "image/png")
    byte[] qr(Authentication a, @PathVariable UUID id) throws Exception {
        return tickets.qr(id, a.getName());
    }

    @PostMapping("/tickets/{id}/validate")
    Object scan(Authentication a, @PathVariable UUID id, @RequestBody Scan r) {
        return tickets.scan(id, r.token(), a.getName(), admin(a));
    }

    @PostMapping("/waiting-room/join")
    Object join(Authentication a) {
        return admission.join(a.getName());
    }

    @GetMapping("/waiting-room/status")
    Object queue(Authentication a) {
        return admission.status(a.getName());
    }

    @PostMapping("/waiting-room/admission")
    Object admit(Authentication a) {
        return admission.admit(a.getName());
    }

    @PostMapping("/organizer/events")
    Object create(Authentication a, @RequestBody EventRequest r) {
        return catalog.create(
                a.getName(), r.title(), r.venue(), r.seats(), r.price(), r.startsAt(), r.category());
    }

    @GetMapping("/organizer/events")
    Object ownEvents(Authentication a) {
        return db.list(
                "SELECT * FROM events WHERE organizer=? ORDER BY created_at DESC LIMIT 100", a.getName());
    }

    @PostMapping("/organizer/events/{id}/cancel")
    void cancelEvent(Authentication a, @PathVariable UUID id) {
        catalog.cancel(id, a.getName(), admin(a));
    }

    @PostMapping("/organizer/seats/{id}/block")
    void block(Authentication a, @PathVariable UUID id, @RequestBody Block b) {
        catalog.block(id, a.getName(), b.blocked());
    }

    @GetMapping("/organizer/bookings")
    Object monitor(Authentication a) {
        return db.list(
                "SELECT b.* FROM bookings b JOIN holds h ON h.id=b.hold_id JOIN occurrences o ON"
                        + " o.id=h.occurrence_id JOIN events e ON e.id=o.event_id WHERE e.organizer=? ORDER BY"
                        + " b.created_at DESC LIMIT 100",
                a.getName());
    }

    @GetMapping("/admin/events")
    Object allEvents() {
        return db.list("SELECT * FROM events ORDER BY created_at DESC LIMIT 100");
    }

    @PostMapping("/admin/events/{id}/approve")
    void approve(@PathVariable UUID id) {
        catalog.publish(id);
    }

    @PostMapping("/admin/events/{id}/cancel")
    void adminCancel(Authentication a, @PathVariable UUID id) {
        catalog.cancel(id, a.getName(), true);
    }

    @GetMapping("/admin/bookings")
    Object allBookings() {
        return db.list("SELECT * FROM bookings ORDER BY created_at DESC LIMIT 100");
    }

    @GetMapping("/admin/refunds")
    Object refunds() {
        return db.list(
                "SELECT * FROM payments WHERE state='REFUNDED' ORDER BY created_at DESC LIMIT 100");
    }

    @GetMapping("/admin/status")
    Object status() {
        return Map.of(
                "pendingPayments",
                db.one("SELECT count(*) AS n FROM payments WHERE state='PENDING'"),
                "pendingEvents",
                db.one("SELECT count(*) AS n FROM outbox_events WHERE processed_at IS NULL"),
                "activeHolds",
                db.one(
                        "SELECT count(*) AS n FROM holds WHERE state='ACTIVE' AND"
                                + " expires_at>clock_timestamp()"),
                "waitingRoom",
                db.one("SELECT count(*) AS n FROM waiting_entries WHERE expires_at IS NULL"));
    }

    @GetMapping("/notifications")
    Object notifications(Authentication a) {
        return db.list(
                "SELECT * FROM notifications WHERE customer=? ORDER BY created_at DESC LIMIT 50",
                a.getName());
    }

    record Registration(String username, String password) {
    }

    record HoldRequest(UUID occurrenceId, List<UUID> seatIds) {
    }

    record BookingRequest(UUID holdId) {
    }

    record PayRequest(String outcome) {
    }

    record Scan(String token) {
    }

    record EventRequest(
            String title, String venue, int seats, long price, Instant startsAt, String category) {
    }

    record Block(boolean blocked) {
    }
}
