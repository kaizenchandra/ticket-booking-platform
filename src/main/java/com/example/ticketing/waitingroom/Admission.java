package com.example.ticketing.waitingroom;

import com.example.ticketing.security.Tokens;
import com.example.ticketing.shared.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.example.ticketing.shared.Problem.fail;

@Service
public class Admission {
    private final Store db;
    private final Tokens tokens;
    private final boolean enabled;
    private final int limit;

    public Admission(
            Store db,
            Tokens tokens,
            @Value("${ticketing.waiting-room}") boolean enabled,
            @Value("${ticketing.admission-per-second}") int limit) {
        this.db = db;
        this.tokens = tokens;
        this.enabled = enabled;
        this.limit = limit;
    }

    @Transactional
    public Map<String, Object> join(String user) {
        db.update(
                "INSERT INTO waiting_entries(customer) VALUES(?) ON CONFLICT(customer) DO UPDATE SET"
                        + " position=DEFAULT,admitted_at=NULL,expires_at=NULL,token_id=NULL,consumed=false,queue_deadline=clock_timestamp()+interval"
                        + " '5 minutes' WHERE waiting_entries.expires_at<=clock_timestamp() OR"
                        + " waiting_entries.queue_deadline<=clock_timestamp() AND waiting_entries.expires_at IS"
                        + " NULL",
                user);
        return status(user);
    }

    public Map<String, Object> status(String user) {
        var row =
                db.one(
                        "SELECT w.*, (SELECT count(*) FROM waiting_entries x WHERE x.position<w.position AND"
                                + " x.expires_at IS NULL AND x.queue_deadline>clock_timestamp()) AS ahead FROM"
                                + " waiting_entries w WHERE customer=?",
                        user);
        var result = new LinkedHashMap<>(row);
        result.put("enabled", enabled);
        if (row.get("token_id") != null)
            result.put("token", tokens.token(user + ":" + row.get("token_id")));
        return result;
    }

    @Transactional
    public Map<String, Object> admit(String user) {
        db.one("SELECT * FROM admission_clock WHERE id=1 FOR UPDATE");
        db.update(
                "UPDATE admission_clock SET window_at=clock_timestamp(),admitted=0 WHERE id=1 AND"
                        + " window_at<=clock_timestamp()-interval '1 second'");
        var w =
                db.one(
                        "SELECT *,expires_at>clock_timestamp() AS valid FROM waiting_entries WHERE customer=?"
                                + " FOR UPDATE",
                        user);
        if (Boolean.TRUE.equals(w.get("valid"))) return status(user);
        if (((Number) db.one("SELECT admitted FROM admission_clock WHERE id=1").get("admitted"))
                .intValue()
                >= limit) throw fail(429, "Admission window full; retry shortly");
        var next =
                db.one(
                        "SELECT customer FROM waiting_entries WHERE expires_at IS NULL AND"
                                + " queue_deadline>clock_timestamp() ORDER BY position LIMIT 1");
        if (!next.get("customer").equals(user)) throw fail(429, "Waiting for earlier queue entries");
        db.update(
                "UPDATE waiting_entries SET"
                        + " admitted_at=clock_timestamp(),expires_at=clock_timestamp()+interval '2"
                        + " minutes',token_id=?,consumed=false WHERE customer=?",
                UUID.randomUUID(),
                user);
        db.update("UPDATE admission_clock SET admitted=admitted+1 WHERE id=1");
        return status(user);
    }

    public void consume(String user, String token) {
        if (!enabled) return;
        var w =
                db.one(
                        "SELECT *,expires_at>clock_timestamp() AS valid FROM waiting_entries WHERE customer=?"
                                + " FOR UPDATE",
                        user);
        if (!Boolean.TRUE.equals(w.get("valid"))
                || Boolean.TRUE.equals(w.get("consumed"))
                || !tokens.valid(user + ":" + w.get("token_id"), token))
            throw fail(403, "Fresh waiting-room admission required");
        db.update("UPDATE waiting_entries SET consumed=true WHERE customer=?", user);
    }

    @Transactional
    public void rate(String user) {
        db.update("INSERT INTO rate_limits VALUES(?,clock_timestamp(),0) ON CONFLICT DO NOTHING", user);
        var r =
                db.one(
                        "UPDATE rate_limits SET attempts=CASE WHEN window_at<clock_timestamp()-interval '1"
                                + " minute' THEN 1 ELSE attempts+1 END,window_at=CASE WHEN"
                                + " window_at<clock_timestamp()-interval '1 minute' THEN clock_timestamp() ELSE"
                                + " window_at END WHERE customer=? RETURNING attempts",
                        user);
        if (((Number) r.get("attempts")).intValue() > 30)
            throw fail(429, "Reservation rate limit reached");
    }
}
