package app.seats;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Array;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reserve = one transaction through three atomic gates, always locked in this order
 * (reservation key -> user counter -> seats sorted by label), so no two transactions
 * can wait on each other in a cycle:
 *   0. fast decline: unlocked read; if a seat is already gone, 409 without locking
 *   1. idempotency: INSERT ... ON CONFLICT (user_id, idem_key) DO NOTHING
 *   2. per-user limit: UPDATE user_counts ... WHERE seat_count + n <= limit
 *   3. seats: lock rows ORDER BY label, then UPDATE ... WHERE status = 'available'
 * Any decline throws ApiError -> the whole transaction rolls back (all-or-nothing).
 */
@RestController
public class ReservationController {
    private final JdbcTemplate db;
    private final Auth auth;
    private final Obs obs;

    ReservationController(JdbcTemplate db, Auth auth, Obs obs) {
        this.db = db;
        this.auth = auth;
        this.obs = obs;
    }

    private ApiError decline(String reason, String msg) {
        obs.declined(reason);
        return ApiError.conflict(reason, msg);
    }

    record ReserveRequest(List<String> seats, String idempotencyKey) {}
    record Reservation(UUID reservationId, UUID showId, String userId, List<String> seats, long amountPaise, String status) {}

    @PostMapping("/shows/{showId}/reserve")
    @Transactional
    ResponseEntity<Reservation> reserve(@PathVariable UUID showId,
                                        @RequestHeader(value = "Authorization", required = false) String authz,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String keyHeader,
                                        @RequestBody ReserveRequest req) {
        String userId = auth.userId(authz);
        String key = keyHeader != null ? keyHeader : req.idempotencyKey();
        if (key == null || key.isBlank() || key.length() > 128) throw ApiError.badRequest("idempotency_key is required (max 128 chars)");
        if (req.seats() == null || req.seats().isEmpty()) throw ApiError.badRequest("seats must be non-empty");
        if (req.seats().stream().anyMatch(s -> s == null || s.isBlank())) throw ApiError.badRequest("seat labels must be non-blank");
        List<String> seats = req.seats().stream().distinct().sorted().toList();
        if (seats.size() != req.seats().size()) throw ApiError.badRequest("seats must be unique");
        // Length-prefixed so it is unambiguous: ["A","B"] -> "1:A1:B", ["A,B"] -> "3:A,B".
        String requestHash = showId + ":" + seats.stream().map(l -> l.length() + ":" + l).collect(Collectors.joining());

        var show = db.queryForList("SELECT price_paise, per_user_limit FROM shows WHERE id = ?", showId);
        if (show.isEmpty()) throw ApiError.notFound("show not found");
        int limit = ((Number) show.get(0).get("per_user_limit")).intValue();
        if (seats.size() > limit) throw decline("per_user_limit", "would exceed per-user limit of " + limit);
        long amount;
        try {
            amount = Math.multiplyExact(((Number) show.get(0).get("price_paise")).longValue(), seats.size());
        } catch (ArithmeticException e) {
            throw ApiError.badRequest("amount overflows");
        }

        // Fast path: decline without taking any lock if a seat is already gone. Safe because a stale
        // read can only cause a decline, never a sale — the locked gates below stay the sole authority.
        // Seats are read BEFORE the key: if a same-key twin just took these seats, its committed row is
        // then guaranteed visible below, so a retry still gets its original reservation, not a 409.
        Object[] labels = seats.toArray();
        int[] avail = db.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT count(*), count(*) FILTER (WHERE status = 'available')
                    FROM seats WHERE show_id = ? AND label = ANY(?)""");
            ps.setObject(1, showId);
            ps.setArray(2, con.createArrayOf("text", labels));
            return ps;
        }, (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}).get(0);
        if (avail[0] != seats.size()) throw ApiError.badRequest("unknown seats for this show");
        if (avail[1] < seats.size()) {
            var prior = db.queryForList("SELECT * FROM reservations WHERE user_id = ? AND idem_key = ?", userId, key);
            if (!prior.isEmpty()) return replay(prior.get(0), requestHash);
            throw decline("seat_taken", "one or more seats are no longer available");
        }

        // Gate 1: idempotency. A concurrent request with the same key blocks on the unique
        // index until this transaction commits (then sees our row) or rolls back (then wins).
        UUID id = UUID.randomUUID();
        int inserted = db.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO reservations (id, show_id, user_id, idem_key, request_hash, seats, amount_paise, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'pending')
                    ON CONFLICT (user_id, idem_key) DO NOTHING""");
            ps.setObject(1, id);
            ps.setObject(2, showId);
            ps.setString(3, userId);
            ps.setString(4, key);
            ps.setString(5, requestHash);
            ps.setArray(6, con.createArrayOf("text", seats.toArray()));
            ps.setLong(7, amount);
            return ps;
        });
        if (inserted == 0)
            return replay(db.queryForMap("SELECT * FROM reservations WHERE user_id = ? AND idem_key = ?", userId, key), requestHash);

        // Gate 2: per-user limit, checked and incremented in one statement.
        db.update("INSERT INTO user_counts (show_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", showId, userId);
        int counted = db.update("""
                UPDATE user_counts SET seat_count = seat_count + ?
                WHERE show_id = ? AND user_id = ? AND seat_count + ? <= ?""",
                seats.size(), showId, userId, seats.size(), limit);
        if (counted == 0) throw decline("per_user_limit", "would exceed per-user limit of " + limit);

        // Gate 3: lock the requested seats in label order (deadlock-free), then flip only available ones.
        List<String> found = db.query(con -> {
            var ps = con.prepareStatement("SELECT label FROM seats WHERE show_id = ? AND label = ANY(?) ORDER BY label FOR UPDATE");
            ps.setObject(1, showId);
            ps.setArray(2, con.createArrayOf("text", labels));
            return ps;
        }, (rs, i) -> rs.getString(1));
        if (found.size() != seats.size()) throw ApiError.badRequest("unknown seats for this show");

        int taken = db.update(con -> {
            var ps = con.prepareStatement("""
                    UPDATE seats SET status = 'confirmed', reservation_id = ?
                    WHERE show_id = ? AND label = ANY(?) AND status = 'available'""");
            ps.setObject(1, id);
            ps.setObject(2, showId);
            ps.setArray(3, con.createArrayOf("text", labels));
            return ps;
        });
        if (taken != seats.size()) throw decline("seat_taken", "one or more seats are no longer available");

        db.update("UPDATE reservations SET status = 'confirmed' WHERE id = ?", id);
        obs.confirmed();
        return ResponseEntity.status(201).body(new Reservation(id, showId, userId, seats, amount, "confirmed"));
    }

    /**
     * Only the owner can cancel (user_id comes from the token). Seats are freed WHERE reservation_id = this one,
     * so a cancel can never release a seat that belongs to someone else. Lock order matches reserve:
     * reservation row -> user counter -> seats sorted by label.
     */
    @PostMapping("/reservations/{id}/cancel")
    @Transactional
    Reservation cancel(@PathVariable UUID id,
                       @RequestHeader(value = "Authorization", required = false) String authz) {
        String userId = auth.userId(authz);
        var rows = db.queryForList("SELECT * FROM reservations WHERE id = ? AND user_id = ? FOR UPDATE", id, userId);
        if (rows.isEmpty()) throw ApiError.notFound("reservation not found");  // also for other users' ids: don't leak
        Reservation r = toReservation(rows.get(0));
        if (!"confirmed".equals(r.status())) return r;  // already cancelled: cancel is idempotent

        db.update("UPDATE user_counts SET seat_count = seat_count - ? WHERE show_id = ? AND user_id = ?",
                r.seats().size(), r.showId(), userId);
        db.query("SELECT label FROM seats WHERE reservation_id = ? ORDER BY label FOR UPDATE", rs -> {}, id);
        db.update("UPDATE seats SET status = 'available', reservation_id = NULL WHERE reservation_id = ?", id);
        db.update("UPDATE reservations SET status = 'cancelled' WHERE id = ?", id);
        return new Reservation(r.reservationId(), r.showId(), userId, r.seats(), r.amountPaise(), "cancelled");
    }

    /** Same key seen before: same body -> the original reservation (200); different body -> 409. */
    private ResponseEntity<Reservation> replay(Map<String, Object> prior, String requestHash) {
        if (!requestHash.equals(prior.get("request_hash")))
            throw decline("idempotency_key_reused", "idempotency key was used with a different request");
        obs.declined("idempotent_replay");
        return ResponseEntity.ok(toReservation(prior));  // nothing moves
    }

    private static Reservation toReservation(Map<String, Object> r) {
        try {
            String[] seats = (String[]) ((Array) r.get("seats")).getArray();
            return new Reservation((UUID) r.get("id"), (UUID) r.get("show_id"), (String) r.get("user_id"),
                    List.of(seats), ((Number) r.get("amount_paise")).longValue(), (String) r.get("status"));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
