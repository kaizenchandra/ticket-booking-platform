package com.example.ticketing.ticket;

import com.example.ticketing.security.Tokens;
import com.example.ticketing.shared.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.ticketing.shared.Problem.fail;

@Service
public class Tickets {
    private final Store db;
    private final Tokens tokens;

    public Tickets(Store db, Tokens tokens) {
        this.db = db;
        this.tokens = tokens;
    }

    @Transactional
    public void issue(UUID booking) {
        var b = db.one("SELECT * FROM bookings WHERE id=? FOR UPDATE", booking);
        if (!b.get("state").equals("CONFIRMED")) return;
        for (var item :
                db.list("SELECT inventory_id FROM booking_items WHERE booking_id=? AND active", booking))
            db.update(
                    "INSERT INTO tickets(id,booking_id,inventory_id) VALUES(?,?,?) ON"
                            + " CONFLICT(booking_id,inventory_id) DO NOTHING",
                    UUID.randomUUID(),
                    booking,
                    item.get("inventory_id"));
    }

    public List<Map<String, Object>> list(UUID bid, String user) {
        db.own(db.one("SELECT * FROM bookings WHERE id=?", bid), user);
        return db
                .list(
                        "SELECT t.*,i.label,i.section FROM tickets t JOIN inventory i ON i.id=t.inventory_id"
                                + " WHERE t.booking_id=? ORDER BY i.label",
                        bid)
                .stream()
                .map(
                        t -> {
                            var r = new LinkedHashMap<>(t);
                            r.put("token", tokens.token(t.get("id").toString()));
                            return (Map<String, Object>) r;
                        })
                .toList();
    }

    @Transactional
    public Map<String, Object> scan(UUID id, String token, String user, boolean admin) {
        if (!tokens.valid(id.toString(), token)) throw fail(403, "Invalid ticket signature");
        var ref =
                db.one(
                        "SELECT e.organizer,e.status,t.booking_id,o.starts_at+interval '24"
                                + " hours'>clock_timestamp() AS valid_time FROM tickets t JOIN inventory i ON"
                                + " i.id=t.inventory_id JOIN occurrences o ON o.id=i.occurrence_id JOIN events e ON"
                                + " e.id=o.event_id WHERE t.id=? FOR SHARE OF e",
                        id);
        if (!admin && !ref.get("organizer").equals(user))
            throw fail(403, "Ticket belongs to a different organizer");
        db.one("SELECT * FROM bookings WHERE id=? FOR UPDATE", ref.get("booking_id"));
        int n =
                ref.get("status").equals("PUBLISHED") && Boolean.TRUE.equals(ref.get("valid_time"))
                        ? db.update(
                        "UPDATE tickets SET state='USED',scanned_at=clock_timestamp() WHERE id=? AND"
                                + " state='VALID'",
                        id)
                        : 0;
        db.update(
                "INSERT INTO ticket_scans VALUES(?,?,?,?,clock_timestamp())",
                UUID.randomUUID(),
                id,
                user,
                n == 1);
        return Map.of(
                "accepted", n == 1, "reason", n == 1 ? "Checked in" : "Already used or cancelled");
    }

    public byte[] qr(UUID id, String user) throws Exception {
        var t =
                db.one(
                        "SELECT t.*,b.customer FROM tickets t JOIN bookings b ON b.id=t.booking_id WHERE"
                                + " t.id=?",
                        id);
        db.own(t, user);
        var matrix =
                new com.google.zxing.qrcode.QRCodeWriter()
                        .encode(tokens.token(id.toString()), com.google.zxing.BarcodeFormat.QR_CODE, 256, 256);
        var out = new java.io.ByteArrayOutputStream();
        com.google.zxing.client.j2se.MatrixToImageWriter.writeToStream(matrix, "PNG", out);
        return out.toByteArray();
    }
}
