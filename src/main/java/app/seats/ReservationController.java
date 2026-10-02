package app.seats;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Array;
import java.util.*;

/**
 * Reserve = one transaction through three atomic gates, always locked in this order
 * (reservation key -> user counter -> seats sorted by label), so no two transactions
 * can wait on each other in a cycle:
 *   0. fast decline: unlocked read; if a seat is already gone, 409 without locking
 *   1. idempotency: INSERT ... ON CONFLICT (user_id, idem_key) DO NOTHING
 *   2. per-user limit: guarded INSERT ... ON CONFLICT DO UPDATE on user_counts
 *   3. seats: lock rows ORDER BY label, then UPDATE ... WHERE status = 'available'
 * Any decline throws ApiError -> the whole transaction rolls back (all-or-nothing).
 */
@RestController
public class ReservationController {
    private final JdbcTemplate db;
    private final Auth auth;
    private final Obs obs;
    private final TransactionTemplate transactions;
    private final PreflightReader preflights;

    ReservationController(JdbcTemplate db, Auth auth, Obs obs, PlatformTransactionManager manager, PreflightReader preflights) {
        this.db = db;
        this.auth = auth;
        this.obs = obs;
        this.transactions = new TransactionTemplate(manager);
        this.preflights = preflights;
    }

    private ApiError decline(String reason, String msg) {
        obs.declined(reason);
        return ApiError.conflict(reason, msg);
    }

    record ReserveRequest(List<String> seats, String idempotencyKey) {}
    record Reservation(UUID reservationId, UUID showId, String userId, List<String> seats, long amountPaise, String status) {}
    record Preflight(long price, int limit, int matched, int available, Reservation prior) {}

    @PostMapping("/shows/{showId}/reserve")
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

        // One autocommit snapshot for metadata, seat state and the existing key.
        // If this snapshot sees unavailable seats from a committed twin, it also sees
        // that twin's reservation. Hot losers need no transaction or rollback round trip.
        Object[] labels = seats.toArray();
        Preflight state = preflights.read(showId, userId, key, seats);
        if (state == null) throw ApiError.notFound("show not found");
        // A persisted key binds the original request, even if the changed seats
        // would independently be invalid or cause an amount overflow.
        if (state.prior() != null) return replay(state.prior(), showId, seats);
        int limit = state.limit();
        if (seats.size() > limit) throw decline("per_user_limit", "would exceed per-user limit of " + limit);
        long amount;
        try { amount = Math.multiplyExact(state.price(), seats.size()); }
        catch (ArithmeticException e) { throw ApiError.badRequest("amount overflows"); }
        if (state.matched() != seats.size()) throw ApiError.badRequest("unknown seats for this show");
        if (state.available() < seats.size())
            throw decline("seat_taken", "one or more seats are no longer available");

        return transactions.execute(status -> book(showId, userId, key, seats, labels, amount, limit));
    }

    private ResponseEntity<Reservation> book(UUID showId, String userId, String key,
                                             List<String> seats, Object[] labels, long amount, int limit) {
        // The confirmed row is invisible until commit; any later decline rolls it back with the seats.
        // Gate 1: idempotency. A concurrent request with the same key blocks on the unique
        // index until this transaction commits (then sees our row) or rolls back (then wins).
        UUID id = UUID.randomUUID();
        int inserted = db.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO reservations (id, show_id, user_id, idem_key, seats, amount_paise, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'confirmed')
                    ON CONFLICT (user_id, idem_key) DO NOTHING""");
            ps.setObject(1, id);
            ps.setObject(2, showId);
            ps.setString(3, userId);
            ps.setString(4, key);
            ps.setArray(5, con.createArrayOf("text", seats.toArray()));
            ps.setLong(6, amount);
            return ps;
        });
        if (inserted == 0)
            return replay(db.queryForMap("SELECT * FROM reservations WHERE user_id = ? AND idem_key = ?", userId, key), showId, seats);

        // Gate 2: atomically initialize OR increment the user counter, with the same limit guard.
        // New users are safe because the request size was checked against the limit above.
        int counted = db.update("""
                INSERT INTO user_counts (show_id, user_id, seat_count) VALUES (?, ?, ?)
                ON CONFLICT (show_id, user_id) DO UPDATE
                SET seat_count = user_counts.seat_count + EXCLUDED.seat_count
                WHERE user_counts.seat_count + EXCLUDED.seat_count <= ?""",
                showId, userId, seats.size(), limit);
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

    /**
     * Same key seen before: same body -> the original reservation (200); different body -> 409.
     * "Same body" compares the stored show_id and sorted seat array directly, not a derived string, so it
     * can't be ambiguous and works for every reservation ever stored.
     */
    private ResponseEntity<Reservation> replay(Map<String, Object> prior, UUID showId, List<String> seats) {
        return replay(toReservation(prior), showId, seats);
    }

    private ResponseEntity<Reservation> replay(Reservation original, UUID showId, List<String> seats) {
        if (!original.showId().equals(showId) || !original.seats().equals(seats))
            throw decline("idempotency_key_reused", "idempotency key was used with a different request");
        obs.declined("idempotent_replay");
        return ResponseEntity.ok(original);  // nothing moves
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
