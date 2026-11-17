package com.example.ticketing;

import com.example.ticketing.booking.Bookings;
import com.example.ticketing.catalog.Catalog;
import com.example.ticketing.payment.Webhooks;
import com.example.ticketing.reservation.Reservations;
import com.example.ticketing.security.Tokens;
import com.example.ticketing.shared.Problem;
import com.example.ticketing.shared.Store;
import com.example.ticketing.ticket.Tickets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PlatformTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    @Autowired
    Store db;
    @Autowired
    Catalog catalog;
    @Autowired
    Reservations holds;
    @Autowired
    Bookings bookings;
    @Autowired
    Tickets tickets;
    @Autowired
    Webhooks hooks;
    @Autowired
    Tokens tokens;
    @Autowired
    MockMvc mvc;
    @Autowired
    TransactionTemplate tx;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("ticketing.workers.enabled", () -> "false");
    }

    Fixture fixture(int n) {
        var e =
                catalog.create(
                        "organizer",
                        "Test " + UUID.randomUUID(),
                        "Hall",
                        n,
                        10000,
                        Instant.now().plusSeconds(86400),
                        "RESERVED");
        UUID id = (UUID) e.get("id");
        catalog.publish(id);
        UUID occ = (UUID) db.one("SELECT id FROM occurrences WHERE event_id=?", id).get("id");
        return new Fixture(
                occ,
                db.list("SELECT id FROM inventory WHERE occurrence_id=? ORDER BY id", occ).stream()
                        .map(x -> (UUID) x.get("id"))
                        .toList());
    }

    UUID hold(Fixture f, String user) {
        return (UUID)
                holds.create(user, UUID.randomUUID().toString(), f.occurrence, f.seats, null).get("id");
    }

    UUID book(UUID h, String user) {
        return (UUID) bookings.create(user, "book-" + h, h).get("id");
    }

    UUID pay(UUID b, String user) {
        return (UUID) bookings.pay(user, "pay-" + b, b, "SUCCESS").get("id");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {2, 100, 1000, 10000})
    void lastSeatConcurrentTransactions(int attempts) throws Exception {
        var f = fixture(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger(), rejected = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < attempts; i++) {
                String user = "racer-" + i;
                jobs.add(
                        pool.submit(
                                () -> {
                                    try {
                                        start.await();
                                        holds.create(user, "race", f.occurrence, f.seats, null);
                                        success.incrementAndGet();
                                    } catch (Problem.Failure e) {
                                        assertEquals(409, e.status);
                                        conflicts.incrementAndGet();
                                    } catch (org.springframework.dao.TransientDataAccessException
                                             |
                                             org.springframework.transaction.CannotCreateTransactionException overload) {
                                        // Bounded pool may shed excess offered work; no inventory commit occurred.
                                        rejected.incrementAndGet();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new RuntimeException(e);
                                    }
                                }));
            }
            start.countDown();
            for (var j : jobs) j.get(90, TimeUnit.SECONDS);
        }
        assertEquals(1, success.get());
        assertEquals(attempts, success.get() + conflicts.get() + rejected.get());
        System.out.printf(
                "CONTENTION attempts=%d held=%d conflicts=%d overload=%d%n",
                attempts, success.get(), conflicts.get(), rejected.get());
        assertEquals(
                1,
                ((Number)
                        db.one(
                                        "SELECT count(*) n FROM inventory WHERE occurrence_id=? AND state='HELD'",
                                        f.occurrence)
                                .get("n"))
                        .intValue());
        assertEquals(
                1,
                ((Number)
                        db.one("SELECT count(*) n FROM holds WHERE occurrence_id=?", f.occurrence).get("n"))
                        .intValue());
    }

    @Test
    void allOrNothingRollback() {
        var f = fixture(2);
        holds.create("first", "one", f.occurrence, List.of(f.seats.getLast()), null);
        assertThrows(
                Problem.Failure.class, () -> holds.create("second", "two", f.occurrence, f.seats, null));
        assertEquals(
                "AVAILABLE",
                db.one("SELECT state FROM inventory WHERE id=?", f.seats.getFirst()).get("state"));
        assertEquals(
                1,
                ((Number)
                        db.one("SELECT count(*) n FROM holds WHERE occurrence_id=?", f.occurrence).get("n"))
                        .intValue());
    }

    @Test
    void lostResponseAndRequestFingerprint() {
        var f = fixture(1);
        var a = holds.create("retry", "same", f.occurrence, f.seats, null);
        var b = holds.create("retry", "same", f.occurrence, f.seats, null);
        assertEquals(a.get("id"), b.get("id"));
        assertThrows(
                Problem.Failure.class,
                () -> holds.create("retry", "same", UUID.randomUUID(), f.seats, null));
        UUID booking = book((UUID) a.get("id"), "retry");
        assertEquals(booking, bookings.create("retry", "another-key", (UUID) a.get("id")).get("id"));
    }

    @Test
    void latePaymentCannotStealReassignedSeat() {
        var f = fixture(1);
        UUID old = hold(f, "late"), b = book(old, "late"), p = pay(b, "late");
        db.update("UPDATE payments SET due_at=clock_timestamp()+interval '1 hour' WHERE id=?", p);
        tx.executeWithoutResult(
                s -> {
                    db.update(
                            "UPDATE holds SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?", old);
                    db.update(
                            "UPDATE inventory SET expires_at=clock_timestamp()-interval '1 second' WHERE"
                                    + " hold_id=?",
                            old);
                });
        UUID fresh = hold(f, "fresh");
        holds.expire();
        bookings.settle(p, "CAPTURED");
        assertEquals(
                fresh,
                db.one("SELECT hold_id FROM inventory WHERE id=?", f.seats.getFirst()).get("hold_id"));
        assertEquals("REFUNDED", db.one("SELECT state FROM payments WHERE id=?", p).get("state"));
    }

    @Test
    void checkoutTicketsCancellationAndDuplicateScan() {
        var f = fixture(2);
        UUID h = hold(f, "buyer"), b = book(h, "buyer"), p = pay(b, "buyer");
        bookings.settle(p, "CAPTURED");
        bookings.settle(p, "FAILED");
        tickets.issue(b);
        tickets.issue(b);
        var ts = tickets.list(b, "buyer");
        assertEquals(2, ts.size());
        UUID t = (UUID) ts.getFirst().get("id");
        try {
            var image =
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(tickets.qr(t, "buyer")));
            var source = new com.google.zxing.client.j2se.BufferedImageLuminanceSource(image);
            assertEquals(
                    tokens.token(t.toString()),
                    new com.google.zxing.MultiFormatReader()
                            .decode(
                                    new com.google.zxing.BinaryBitmap(
                                            new com.google.zxing.common.HybridBinarizer(source)))
                            .getText());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(
                true, tickets.scan(t, tokens.token(t.toString()), "organizer", false).get("accepted"));
        assertEquals(
                false, tickets.scan(t, tokens.token(t.toString()), "organizer", false).get("accepted"));
        bookings.cancel("buyer", "cancel", b);
        bookings.cancel("buyer", "cancel", b);
        assertEquals("REFUNDED", db.one("SELECT state FROM payments WHERE id=?", p).get("state"));
        assertEquals(
                0,
                ((Number)
                        db.one("SELECT count(*) n FROM booking_items WHERE booking_id=? AND active", b)
                                .get("n"))
                        .intValue());
        assertTrue(
                tickets.list(b, "buyer").stream().allMatch(ti -> ti.get("state").equals("CANCELLED")));
    }

    @Test
    void duplicateAndOutOfOrderSignedWebhooks() throws Exception {
        var f = fixture(1);
        UUID h = hold(f, "webhook"), b = book(h, "webhook"), p = pay(b, "webhook");
        String body =
                "{\"eventId\":\""
                        + UUID.randomUUID()
                        + "\",\"paymentId\":\""
                        + p
                        + "\",\"state\":\"CAPTURED\"}";
        String ts = "" + Instant.now().getEpochSecond();
        String sig =
                Tokens.sign(ts + "." + body, "local-webhook-secret-change-before-deploying-123456");
        hooks.accept(body, ts, sig);
        hooks.accept(body, ts, sig);
        bookings.settle(p, "FAILED");
        assertEquals("CAPTURED", db.one("SELECT state FROM payments WHERE id=?", p).get("state"));
        assertThrows(Problem.Failure.class, () -> hooks.accept(body, ts, "forged"));
    }

    @Test
    void confirmationExpiryRace() throws Exception {
        for (int i = 0; i < 20; i++) {
            var f = fixture(1);
            String u = "race-pay-" + i;
            UUID h = hold(f, u), b = book(h, u), p = pay(b, u);
            if (i % 2 == 0)
                db.update(
                        "UPDATE holds SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?", h);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var a = pool.submit(() -> bookings.settle(p, "CAPTURED"));
                var c = pool.submit(() -> holds.expire());
                a.get();
                c.get();
            }
            String state = db.one("SELECT state FROM bookings WHERE id=?", b).get("state").toString();
            assertEquals(i % 2 == 0 ? "REFUNDED" : "CONFIRMED", state);
            if (i % 2 != 0)
                assertEquals(
                        h,
                        db.one("SELECT hold_id FROM inventory WHERE id=?", f.seats.getFirst()).get("hold_id"));
        }
    }

    @Test
    void databaseConstraintRejectsCompetingConfirmedOwners() {
        var f = fixture(1);
        UUID h = hold(f, "constraint"), b = book(h, "constraint"), p = pay(b, "constraint");
        bookings.settle(p, "CAPTURED");
        var other = fixture(1);
        UUID second = book(hold(other, "other"), "other");
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> db.update("INSERT INTO booking_items VALUES(?,?,true)", second, f.seats.getFirst()));
    }

    @Test
    void authorizationAndConflictApi() throws Exception {
        mvc.perform(get("/api/v1/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/status").with(httpBasic("customer", "demo-customer")))
                .andExpect(status().isForbidden());
        var f = fixture(1);
        UUID h = hold(f, "customer");
        mvc.perform(get("/api/v1/holds/" + h).with(httpBasic("organizer", "demo-organizer")))
                .andExpect(status().isNotFound());
    }

    @Test
    void failureReleasesInventory() {
        var f = fixture(1);
        UUID h = hold(f, "failure"), b = book(h, "failure"), p = pay(b, "failure");
        bookings.settle(p, "FAILED");
        assertEquals(
                "AVAILABLE",
                db.one("SELECT state FROM inventory WHERE id=?", f.seats.getFirst()).get("state"));
        assertEquals("PAYMENT_FAILED", db.one("SELECT state FROM bookings WHERE id=?", b).get("state"));
    }

    @Test
    void twoIndependentApplicationInstances() throws Exception {
        var f = fixture(1);
        try (var second =
                     new org.springframework.boot.builder.SpringApplicationBuilder(TicketingApplication.class)
                             .run(
                                     "--server.port=0",
                                     "--spring.datasource.url=" + pg.getJdbcUrl(),
                                     "--spring.datasource.username=" + pg.getUsername(),
                                     "--spring.datasource.password=" + pg.getPassword(),
                                     "--ticketing.workers.enabled=false")) {
            var other = second.getBean(Reservations.class);
            AtomicInteger n = new AtomicInteger();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var jobs = new ArrayList<Future<?>>();
                for (int i = 0; i < 100; i++) {
                    int index = i;
                    jobs.add(
                            pool.submit(
                                    () -> {
                                        try {
                                            (index % 2 == 0 ? holds : other)
                                                    .create("instance-" + index, "key", f.occurrence, f.seats, null);
                                            n.incrementAndGet();
                                        } catch (Problem.Failure ex) {
                                            assertEquals(409, ex.status);
                                        }
                                    }));
                }
                for (var job : jobs) job.get();
            }
            assertEquals(1, n.get());
        }
    }

    @Test
    void generalAdmissionSlotsNeverExceedCapacity() throws Exception {
        var f = fixture(3);
        db.update("UPDATE inventory SET category='GA' WHERE occurrence_id=?", f.occurrence);
        for (int i = 0; i < 3; i++)
            holds.create("ga-" + i, "ga", f.occurrence, List.of(f.seats.get(i)), null);
        assertThrows(
                Problem.Failure.class, () -> holds.create("oversell", "ga", f.occurrence, f.seats, null));
        assertEquals(
                3,
                ((Number)
                        db.one(
                                        "SELECT count(*) n FROM inventory WHERE occurrence_id=? AND state='HELD'",
                                        f.occurrence)
                                .get("n"))
                        .intValue());
    }

    @Test
    void outboxRetriesTicketIssuanceAfterWorkerRestart() {
        var f = fixture(1);
        UUID b = book(hold(f, "recovery"), "recovery"), p = pay(b, "recovery");
        bookings.settle(p, "CAPTURED");
        assertTrue(tickets.list(b, "recovery").isEmpty());
        var worker = new com.example.ticketing.notification.Workers(db, bookings, tickets, holds, tx);
        worker.outbox();
        worker.outbox();
        assertEquals(1, tickets.list(b, "recovery").size());
        assertEquals(
                1,
                ((Number)
                        db.one(
                                        "SELECT count(*) n FROM notifications n JOIN outbox_events e ON"
                                                + " e.id=n.event_id WHERE e.aggregate_id=? AND"
                                                + " e.type='BookingConfirmed'",
                                        b)
                                .get("n"))
                        .intValue());
    }

    @Test
    void eventCancellationPreventsPaymentConfirmation() {
        var f = fixture(1);
        UUID b = book(hold(f, "event-cancel"), "event-cancel"), p = pay(b, "event-cancel");
        UUID event =
                (UUID) db.one("SELECT event_id FROM occurrences WHERE id=?", f.occurrence).get("event_id");
        catalog.cancel(event, "organizer", false);
        bookings.settle(p, "CAPTURED");
        assertEquals("REFUNDED", bookings.get(b, "event-cancel").get("state"));
    }

    @Test
    void admissionSignaturesAndReplay() {
        var gate = new com.example.ticketing.waitingroom.Admission(db, tokens, true, 100);
        String user = "queue-" + UUID.randomUUID();
        gate.join(user);
        var admitted = tx.execute(status -> gate.admit(user));
        String token = admitted.get("token").toString();
        assertThrows(
                Problem.Failure.class,
                () -> tx.executeWithoutResult(status -> gate.consume(user, token + "forged")));
        tx.executeWithoutResult(status -> gate.consume(user, token));
        assertThrows(
                Problem.Failure.class, () -> tx.executeWithoutResult(status -> gate.consume(user, token)));
    }

    @Test
    void deadlockVictimCanRetryInFreshTransaction() throws Exception {
        var f = fixture(2);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        AtomicInteger victims = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) {
                int first = i;
                jobs.add(
                        pool.submit(
                                () -> {
                                    try {
                                        tx.executeWithoutResult(
                                                st -> {
                                                    db.one(
                                                            "SELECT id FROM inventory WHERE id=? FOR UPDATE", f.seats.get(first));
                                                    try {
                                                        barrier.await(10, TimeUnit.SECONDS);
                                                    } catch (Exception e) {
                                                        throw new RuntimeException(e);
                                                    }
                                                    db.one(
                                                            "SELECT id FROM inventory WHERE id=? FOR UPDATE",
                                                            f.seats.get(1 - first));
                                                });
                                    } catch (org.springframework.dao.TransientDataAccessException expected) {
                                        victims.incrementAndGet();
                                        tx.executeWithoutResult(
                                                st -> {
                                                    for (UUID id : f.seats)
                                                        db.one("SELECT id FROM inventory WHERE id=? FOR UPDATE", id);
                                                });
                                    }
                                }));
            }
            for (var j : jobs) j.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1, victims.get());
        UUID h = hold(f, "after-deadlock");
        assertNotNull(h);
    }

    record Fixture(UUID occurrence, List<UUID> seats) {
    }
}
