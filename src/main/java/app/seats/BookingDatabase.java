package app.seats;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Install the function after tables, and invoke it as one prepared statement. */
@Component
@DependsOnDatabaseInitialization
class BookingDatabase {
    private final JdbcTemplate db;

    BookingDatabase(JdbcTemplate db, @Value("classpath:booking.sql") Resource definition) throws IOException {
        this.db = db;
        // Send the complete function to PostgreSQL; do not split its PL/pgSQL semicolons.
        try (var input = definition.getInputStream()) {
            db.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    record Result(String outcome, ReservationController.Reservation reservation) {}

    Result book(UUID show, String user, String key, List<String> seats, long amount, int limit) {
        UUID id = UUID.randomUUID();
        return db.query(con -> {
            var ps = con.prepareStatement("SELECT * FROM public.reserve_booking(?, ?, ?, ?, ?, ?, ?)");
            ps.setObject(1, id); ps.setObject(2, show); ps.setString(3, user); ps.setString(4, key);
            ps.setArray(5, con.createArrayOf("text", seats.toArray()));
            ps.setLong(6, amount); ps.setInt(7, limit);
            return ps;
        }, (rs, row) -> {
            UUID resultId = (UUID)rs.getObject("result_id");
            var reservation = resultId == null ? null : new ReservationController.Reservation(resultId,
                    (UUID)rs.getObject("result_show"), rs.getString("result_user"),
                    List.of((String[])rs.getArray("result_seats").getArray()),
                    rs.getLong("result_amount"), rs.getString("result_status"));
            return new Result(rs.getString("result_outcome"), reservation);
        }).get(0);
    }
}
