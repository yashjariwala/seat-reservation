package app.seats;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class ShowController {
    private final JdbcTemplate db;
    private final Auth auth;

    ShowController(JdbcTemplate db, Auth auth) {
        this.db = db;
        this.auth = auth;
    }

    record TokenRequest(String userId) {}
    record CreateShow(String name, List<String> seats, Long pricePaise, Integer perUserLimit) {}

    /**
     * Issues a signed token for a user id. Admin-only: this stands in for an identity provider, so a user
     * cannot mint a token for someone else and act as them.
     */
    @PostMapping("/auth/token")
    Map<String, String> token(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
                              @RequestBody TokenRequest req) {
        auth.requireAdmin(adminKey);
        return Map.of("user_id", req.userId(), "token", auth.issue(req.userId()));
    }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Map<String, Object> create(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
                               @RequestBody CreateShow req) {
        auth.requireAdmin(adminKey);
        if (req.name() == null || req.name().isBlank()) throw ApiError.badRequest("name is required");
        if (req.seats() == null || req.seats().isEmpty()) throw ApiError.badRequest("seats must be non-empty");
        if (req.seats().stream().anyMatch(s -> s == null || s.isBlank())) throw ApiError.badRequest("seat labels must be non-blank");
        if (new HashSet<>(req.seats()).size() != req.seats().size()) throw ApiError.badRequest("seat labels must be unique");
        if (req.pricePaise() == null || req.pricePaise() < 0) throw ApiError.badRequest("price_paise must be a non-negative integer");
        int limit = req.perUserLimit() == null ? 4 : req.perUserLimit();
        if (limit < 1) throw ApiError.badRequest("per_user_limit must be >= 1");

        UUID id = UUID.randomUUID();
        db.update("INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats) VALUES (?, ?, ?, ?, ?)",
                id, req.name(), req.pricePaise(), limit, req.seats().size());
        db.update(con -> {
            var ps = con.prepareStatement("INSERT INTO seats (show_id, label) SELECT ?, unnest(?::text[])");
            ps.setObject(1, id);
            ps.setArray(2, con.createArrayOf("text", req.seats().toArray()));
            return ps;
        });
        return show(id);
    }

    @GetMapping("/shows/{id}")
    Map<String, Object> get(@PathVariable UUID id) {
        return show(id);
    }

    /**
     * Seats and counts come from ONE query = one snapshot, so available+held+confirmed==total
     * holds by construction (every seat row has exactly one status).
     */
    private Map<String, Object> show(UUID id) {
        var shows = db.queryForList("SELECT id, name, price_paise, per_user_limit FROM shows WHERE id = ?", id);
        if (shows.isEmpty()) throw ApiError.notFound("show not found");

        Map<String, Integer> counts = new LinkedHashMap<>(Map.of("available", 0, "held", 0, "confirmed", 0));
        List<Map<String, String>> seats = new ArrayList<>();
        db.query("SELECT label, status FROM seats WHERE show_id = ? ORDER BY label", rs -> {
            String status = rs.getString("status");
            seats.add(Map.of("label", rs.getString("label"), "status", status));
            counts.merge(status, 1, Integer::sum);
        }, id);

        Map<String, Object> out = new LinkedHashMap<>(shows.get(0));
        out.put("total_seats", seats.size());
        out.put("counts", counts);
        out.put("seats", seats);
        return out;
    }
}
