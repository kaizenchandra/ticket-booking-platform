package com.example.ticketing.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.ticketing.shared.Problem.fail;

@Component
public class Store {
    public final JdbcTemplate sql;

    public Store(JdbcTemplate sql) {
        this.sql = sql;
    }

    public Map<String, Object> one(String q, Object... args) {
        var rows = sql.queryForList(q, args);
        if (rows.isEmpty()) throw fail(404, "Resource not found");
        return rows.getFirst();
    }

    public List<Map<String, Object>> list(String q, Object... args) {
        return sql.queryForList(q, args);
    }

    public int update(String q, Object... args) {
        return sql.update(q, args);
    }

    public UUID id(Map<String, Object> row, String key) {
        return (UUID) row.get(key);
    }

    public void event(UUID id, String type, String customer) {
        update(
                "INSERT INTO outbox_events(id,aggregate_id,type,payload)"
                        + " VALUES(?,?,?,jsonb_build_object('customer',?::text))",
                UUID.randomUUID(),
                id,
                type,
                customer);
    }

    public UUID replay(String user, String op, String key, String fingerprint) {
        if (key == null || key.isBlank() || key.length() > 120)
            throw fail(400, "Idempotency-Key required (1–120 characters)");
        sql.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", user + "/" + op + "/" + key);
        var r =
                list("SELECT * FROM idempotency WHERE customer=? AND operation=? AND key=?", user, op, key);
        if (r.isEmpty()) return null;
        if (!r.getFirst().get("fingerprint").equals(fingerprint))
            throw fail(409, "Idempotency key reused for a different request");
        return id(r.getFirst(), "result_id");
    }

    public void remember(String user, String op, String key, String fp, UUID id) {
        update("INSERT INTO idempotency VALUES(?,?,?,?,?)", user, op, key, fp, id);
    }

    public void own(Map<String, Object> row, String user) {
        if (!row.get("customer").equals(user)) throw fail(404, "Resource not found");
    }
}
