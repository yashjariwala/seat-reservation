package app.seats;

import io.micrometer.core.instrument.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Metrics + request-scoped structured logging. */
@Component
@EnableScheduling
public class Obs extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("access");

    private final MeterRegistry registry;
    private final JdbcTemplate db;
    private final Counter confirmations;
    private final Map<String, Counter> declines = new ConcurrentHashMap<>();
    private final Set<String> registeredShows = new HashSet<>(); // guarded by metricSnapshotLock
    private volatile Map<String, Map<String, Long>> seatSnapshot = Map.of();
    // Publish all seat counts together and keep a scrape on one consistent snapshot.
    private final ReentrantReadWriteLock metricSnapshotLock = new ReentrantReadWriteLock(true);

    Obs(MeterRegistry registry, JdbcTemplate db) {
        this.registry = registry;
        this.db = db;
        this.confirmations = registry.counter("reservations_confirmed_total");
        for (String reason : List.of("seat_taken", "per_user_limit", "idempotent_replay", "idempotency_key_reused"))
            declines.put(reason, registry.counter("reservations_declined_total", "reason", reason));
    }

    void confirmed() {
        afterCommit(confirmations::increment);
        MDC.put("outcome", "confirmed");
    }

    /** reason: seat_taken | per_user_limit | idempotent_replay | idempotency_key_reused | ... */
    void declined(String reason) {
        declines.computeIfAbsent(reason, r -> registry.counter("reservations_declined_total", "reason", r)).increment();
        MDC.put("outcome", reason);
    }

    /** Count only what actually committed, so metrics reconcile with the DB. */
    private static void afterCommit(Runnable r) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { r.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { r.run(); }
        });
    }

    /** seats{show_id, status} + seats_declared{show_id}: their sum must match (reconciliation). ponytail: 1s lag; fine for watching a burst. */
    @Scheduled(fixedDelay = 1000)
    void refreshSeatGauges() {
        Map<String, Map<String, Long>> next = new LinkedHashMap<>();
        // One SQL snapshot avoids comparing old seat counts with a newly-created show's total.
        db.query("""
                SELECT s.id AS show_id, s.total_seats, t.status, count(t.label) AS n
                FROM shows s LEFT JOIN seats t ON t.show_id = s.id
                GROUP BY s.id, s.total_seats, t.status""", rs -> {
            Map<String, Long> counts = next.computeIfAbsent(rs.getString("show_id"), id ->
                    new HashMap<>(Map.of("available", 0L, "held", 0L, "confirmed", 0L)));
            counts.put("total", rs.getLong("total_seats"));
            String status = rs.getString("status");
            if (status != null) counts.put(status, rs.getLong("n"));
        });
        next.replaceAll((id, counts) -> Map.copyOf(counts));
        metricSnapshotLock.writeLock().lock();
        try {
            seatSnapshot = Map.copyOf(next);
            // Register each series once; subsequent refreshes only replace the snapshot it reads.
            for (String id : next.keySet()) if (registeredShows.add(id)) {
                for (String status : List.of("available", "held", "confirmed"))
                    Gauge.builder("seats", this, obs -> obs.seatValue(id, status))
                            .tags("show_id", id, "status", status).register(registry);
                Gauge.builder("seats_declared", this, obs -> obs.seatValue(id, "total"))
                        .tag("show_id", id).register(registry);
            }
        } finally {
            metricSnapshotLock.writeLock().unlock();
        }
    }

    private long seatValue(String showId, String status) {
        return seatSnapshot.getOrDefault(showId, Map.of()).getOrDefault(status, 0L);
    }

    /** Correlation id: honour inbound X-Request-Id or mint one; echo it back; one access log line per request. */
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String rid = req.getHeader("X-Request-Id");
        if (rid == null || rid.isBlank() || rid.length() > 64) rid = UUID.randomUUID().toString();
        MDC.put("request_id", rid);
        res.setHeader("X-Request-Id", rid);
        long start = System.nanoTime();
        boolean scraping = req.getRequestURI().equals("/actuator/prometheus");
        if (scraping) metricSnapshotLock.readLock().lock();
        try {
            chain.doFilter(req, res);
        } finally {
            if (scraping) metricSnapshotLock.readLock().unlock();
            if (!req.getRequestURI().startsWith("/actuator")) {
                MDC.put("method", req.getMethod());
                MDC.put("path", req.getRequestURI());
                MDC.put("status", String.valueOf(res.getStatus()));
                MDC.put("duration_ms", String.valueOf((System.nanoTime() - start) / 1_000_000));
                log.info("request");
            }
            MDC.clear();
        }
    }
}
