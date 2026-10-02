package app.seats;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Independent checks of durable relationships, all from one MVCC statement snapshot. */
final class DatabaseInvariantAudit {
    static void assertConsistent(JdbcTemplate db, UUID show) {
        var violations = db.queryForMap("""
                WITH scope AS (SELECT CAST(? AS uuid) AS id),
                booked AS (
                    SELECT r.* FROM reservations r, scope
                    WHERE r.show_id = scope.id AND r.status = 'confirmed'
                ), ownership AS (
                    SELECT id, show_id, user_id, unnest(seats) AS label FROM booked
                ), expected_counts AS (
                    SELECT user_id, sum(cardinality(seats)) AS n FROM booked GROUP BY user_id
                ), stored_counts AS (
                    SELECT u.* FROM user_counts u, scope WHERE u.show_id = scope.id
                )
                SELECT
                  (SELECT count(*) FROM (
                     SELECT label FROM ownership GROUP BY label HAVING count(*) > 1
                  ) duplicates) AS double_ownership,
                  (SELECT count(*) FROM seats s, scope WHERE s.show_id = scope.id AND (
                     (s.status = 'available' AND s.reservation_id IS NOT NULL) OR
                     (s.status = 'confirmed' AND NOT EXISTS (
                       SELECT 1 FROM ownership o WHERE o.label = s.label AND o.id = s.reservation_id
                     ))
                  )) AS invalid_seat_owner,
                  (SELECT count(*) FROM ownership o WHERE NOT EXISTS (
                     SELECT 1 FROM seats s WHERE s.show_id = o.show_id AND s.label = o.label
                       AND s.status = 'confirmed' AND s.reservation_id = o.id
                  )) AS missing_booked_seat,
                  (SELECT count(*) FROM stored_counts u FULL JOIN expected_counts e USING (user_id)
                     WHERE coalesce(u.seat_count, 0) <> coalesce(e.n, 0)
                        OR coalesce(e.n, 0) > (SELECT per_user_limit FROM shows, scope WHERE shows.id = scope.id)
                  ) AS wrong_user_count,
                  (SELECT count(*) FROM shows h, scope WHERE h.id = scope.id AND h.total_seats <>
                     (SELECT count(*) FROM seats s WHERE s.show_id = h.id)
                  ) AS wrong_inventory_total,
                  (SELECT count(*) FROM reservations r, scope
                     WHERE r.show_id = scope.id AND r.status = 'pending'
                  ) AS leaked_pending_reservation
                """, show);
        violations.forEach((name, value) -> assertThat(((Number)value).longValue())
                .as("show=%s invariant=%s", show, name).isZero());
    }
}
