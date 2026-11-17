package com.example.ticketing.payment;

import com.example.ticketing.booking.Bookings;
import com.example.ticketing.security.Tokens;
import com.example.ticketing.shared.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.example.ticketing.shared.Problem.fail;

@Service
public class Webhooks {
    private final Store db;
    private final Bookings bookings;
    private final ObjectMapper json;
    private final String secret;

    public Webhooks(
            Store db,
            Bookings bookings,
            ObjectMapper json,
            @Value("${ticketing.webhook-secret}") String secret) {
        this.db = db;
        this.bookings = bookings;
        this.json = json;
        this.secret = secret;
    }

    @Transactional
    public void accept(String body, String timestamp, String signature) throws Exception {
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (Exception e) {
            throw fail(401, "Invalid webhook timestamp");
        }
        if (Math.abs(java.time.Instant.now().getEpochSecond() - ts) > 300
                || !Tokens.equal(Tokens.sign(timestamp + "." + body, secret), signature))
            throw fail(401, "Invalid webhook signature");
        var node = json.readTree(body);
        String event = node.path("eventId").asText();
        if (event.isBlank() || event.length() > 120) throw fail(400, "eventId required");
        UUID id = UUID.fromString(node.path("paymentId").asText());
        String state = node.path("state").asText();
        db.sql.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,2))", event);
        var old = db.list("SELECT * FROM payment_webhooks WHERE id=?", event);
        if (!old.isEmpty()) {
            if (!old.getFirst().get("payment_id").equals(id)
                    || !old.getFirst().get("state").equals(state))
                throw fail(409, "Event ID content changed");
            return;
        }
        bookings.settle(id, state);
        db.update("INSERT INTO payment_webhooks(id,payment_id,state) VALUES(?,?,?)", event, id, state);
    }
}
