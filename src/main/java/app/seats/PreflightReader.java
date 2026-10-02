package app.seats;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/** Coalesce concurrent reads, without caching inventory or acknowledging unfinished bookings. */
@Component
class PreflightReader {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final DistributionSummary sizes;
    private final BlockingQueue<Request> pending = new ArrayBlockingQueue<>(128);
    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "reservation-preflight");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    private record Request(UUID show, String user, String key, List<String> labels,
                           CompletableFuture<ReservationController.Preflight> result) {}

    PreflightReader(JdbcTemplate db, ObjectMapper json, MeterRegistry registry) {
        this.db = db;
        this.json = json;
        sizes = registry.summary("reservation_preflight_batch_size");
        workers.submit(this::consume);
        workers.submit(this::consume);
    }

    ReservationController.Preflight read(UUID show, String user, String key, List<String> labels) {
        Request request = new Request(show, user, key, labels, new CompletableFuture<>());
        try {
            if (closed) throw new IllegalStateException("Reservation reader stopped");
            pending.put(request);
            // Cover a shutdown racing with enqueue; no request may wait on a stopped worker.
            if (closed && pending.remove(request))
                request.result.completeExceptionally(new IllegalStateException("Reservation reader stopped"));
            return request.result.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while queuing reservation read", e);
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("Reservation read failed", e.getCause());
        }
    }

    private void consume() {
        while (!closed) {
            List<Request> batch = new ArrayList<>(64);
            try {
                Request first = pending.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                // At most 2 ms gathering delay; one snapshot supplies each buyer's own result.
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2);
                while (batch.size() < 64) {
                    pending.drainTo(batch, 64 - batch.size());
                    if (batch.size() == 64) break;
                    long left = deadline - System.nanoTime();
                    if (left <= 0) break;
                    Request next = pending.poll(left, TimeUnit.NANOSECONDS);
                    if (next == null) break;
                    batch.add(next);
                }
                query(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                batch.forEach(r -> r.result.completeExceptionally(e));
                return;
            } catch (Exception e) {
                batch.forEach(r -> r.result.completeExceptionally(e));
            }
        }
    }

    private void query(List<Request> batch) throws Exception {
        List<Map<String, Object>> input = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            Request r = batch.get(i);
            input.add(Map.of("ordinal", i, "show_id", r.show, "user_id", r.user,
                    "idem_key", r.key, "labels", r.labels));
        }
        String payload = json.writeValueAsString(input);
        // Keep inventory and the existing key in the same READ COMMITTED statement snapshot.
        // PostgreSQL converts each JSON labels array into text[]; ordinals isolate all buyers.
        Map<Integer, ReservationController.Preflight> results = new HashMap<>();
        db.query("""
                SELECT i.ordinal, s.id AS show_exists, s.price_paise, s.per_user_limit,
                       count(t.label) AS matched,
                       count(t.label) FILTER (WHERE t.status = 'available') AS available,
                       r.id AS prior_id, r.show_id AS prior_show, r.user_id AS prior_user,
                       r.seats AS prior_seats, r.amount_paise AS prior_amount, r.status AS prior_status
                FROM jsonb_to_recordset(CAST(? AS jsonb))
                     AS i(ordinal int, show_id uuid, user_id text, idem_key text, labels text[])
                LEFT JOIN shows s ON s.id = i.show_id
                LEFT JOIN reservations r ON r.user_id = i.user_id AND r.idem_key = i.idem_key
                LEFT JOIN seats t ON t.show_id = s.id AND t.label = ANY(i.labels)
                GROUP BY i.ordinal, s.id, r.id
                """, rs -> {
            int ordinal = rs.getInt("ordinal");
            if (rs.getObject("show_exists") == null) return;
            UUID priorId = (UUID) rs.getObject("prior_id");
            var prior = priorId == null ? null : new ReservationController.Reservation(priorId,
                    (UUID) rs.getObject("prior_show"), rs.getString("prior_user"),
                    List.of((String[]) rs.getArray("prior_seats").getArray()),
                    rs.getLong("prior_amount"), rs.getString("prior_status"));
            results.put(ordinal, new ReservationController.Preflight(rs.getLong("price_paise"),
                    rs.getInt("per_user_limit"), rs.getInt("matched"), rs.getInt("available"), prior));
        }, payload);
        sizes.record(batch.size());
        // Do not release any caller until the complete batch has been read successfully.
        for (int i = 0; i < batch.size(); i++) batch.get(i).result.complete(results.get(i));
    }

    @PreDestroy
    void stop() {
        closed = true;
        workers.shutdownNow();
        Request request;
        while ((request = pending.poll()) != null)
            request.result.completeExceptionally(new IllegalStateException("Reservation reader stopped"));
    }
}
