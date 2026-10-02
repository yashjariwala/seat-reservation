package app.seats;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.awaitility.Awaitility.await;

import static org.assertj.core.api.Assertions.*;

/** Run against a dedicated PostgreSQL database; no in-memory substitute for row-lock semantics. */
@EnabledIfEnvironmentVariable(named = "SEAT_TEST_DATABASE_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=${SEAT_TEST_DATABASE_URL}")
class ReservationIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired ShowController shows;
    @Autowired Auth auth;
    @Autowired JdbcTemplate db;
    @Autowired MeterRegistry meters;
    @Autowired BookingDatabase bookings;
    @LocalServerPort int port;
    @Value("${ADMIN_KEY:dev-admin-key}") String admin;

    private UUID show(List<String> seats) {
        return (UUID)shows.create(admin, new ShowController.CreateShow("Concurrency regression", seats, 25000L, 4)).get("id");
    }
    private ResponseEntity<JsonNode> reserve(UUID show, String token, String key, List<String> seats) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/shows/" + show + "/reserve",
                new HttpEntity<>(Map.of("seats", seats, "idempotency_key", key), headers), JsonNode.class);
    }
    private ResponseEntity<JsonNode> cancel(String id, String token) {
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(token);
        return http.postForEntity("/reservations/" + id + "/cancel",
                new HttpEntity<>(Map.of(), headers), JsonNode.class);
    }

    @Test void functionDeclinesRollbackPartialChangesEvenWithoutAnOuterRollback() {
        UUID id = show(List.of("A", "B"));
        String owner = "function-owner-" + UUID.randomUUID();
        assertThat(reserve(id, auth.issue(owner), "winner", List.of("A")).getStatusCode().value()).isEqualTo(201);
        // Direct DAO calls use autocommit here: only the function can undo its partial writes.
        var taken = bookings.book(id, "function-loser", "partial", List.of("A", "B"), 50000, 4);
        assertThat(taken.outcome()).isEqualTo("seat_taken");
        assertThat(taken.reservation()).isNull();
        var unknown = bookings.book(id, owner, "unknown", List.of("B", "missing"), 50000, 4);
        assertThat(unknown.outcome()).isEqualTo("unknown_seats");
        assertThat(db.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM user_counts WHERE show_id=?", Integer.class, id)).isEqualTo(1);
        DatabaseInvariantAudit.assertConsistent(db, id);

        UUID limited = show(List.of("A", "B", "C", "D", "E"));
        assertThat(reserve(limited, auth.issue(owner), "full", List.of("A", "B", "C", "D")).getStatusCode().value()).isEqualTo(201);
        assertThat(bookings.book(limited, owner, "fifth", List.of("E"), 25000, 4).outcome()).isEqualTo("per_user_limit");
        assertThat(db.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?", Integer.class, limited)).isEqualTo(1);
        DatabaseInvariantAudit.assertConsistent(db, limited);
    }

    @Test void invariantAuditDetectsDirectDatabaseCorruption() {
        UUID id = show(List.of("A", "B"));
        String user = "audit-" + UUID.randomUUID(), token = auth.issue(user);
        assertThat(reserve(id, token, "original", List.of("A")).getStatusCode().value()).isEqualTo(201);
        DatabaseInvariantAudit.assertConsistent(db, id);

        db.update("UPDATE user_counts SET seat_count=2 WHERE show_id=?", id);
        assertThatThrownBy(() -> DatabaseInvariantAudit.assertConsistent(db, id)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("wrong_user_count");
        db.update("UPDATE user_counts SET seat_count=1 WHERE show_id=?", id);

        db.update("UPDATE seats SET status='available' WHERE show_id=? AND label='A'", id);
        assertThatThrownBy(() -> DatabaseInvariantAudit.assertConsistent(db, id)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("invalid_seat_owner");
        db.update("UPDATE seats SET status='confirmed' WHERE show_id=? AND label='A'", id);

        UUID duplicate = UUID.randomUUID();
        db.update("""
                INSERT INTO reservations(id,show_id,user_id,idem_key,seats,amount_paise,status)
                VALUES (?,?,'bypass','bypass',ARRAY['A'],25000,'confirmed')
                """, duplicate, id);
        assertThatThrownBy(() -> DatabaseInvariantAudit.assertConsistent(db, id)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("double_ownership");
        db.update("DELETE FROM reservations WHERE id=?", duplicate);

        db.update("UPDATE shows SET total_seats=3 WHERE id=?", id);
        assertThatThrownBy(() -> DatabaseInvariantAudit.assertConsistent(db, id)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("wrong_inventory_total");
        db.update("UPDATE shows SET total_seats=2 WHERE id=?", id);
        DatabaseInvariantAudit.assertConsistent(db, id);
    }

    @Test void existingKeyRejectsChangedSeatsBeforeInventoryOrPriceValidation() {
        UUID id = (UUID) shows.create(admin, new ShowController.CreateShow("Price boundary",
                List.of("A", "B"), Long.MAX_VALUE, 4)).get("id");
        String token = auth.issue("key-boundary-" + UUID.randomUUID());
        assertThat(reserve(id, token, "key", List.of("A")).getStatusCode().value()).isEqualTo(201);
        for (var changed : List.of(List.of("missing"), List.of("A", "B"))) {
            var response = reserve(id, token, "key", changed);
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody().path("error").asText()).isEqualTo("idempotency_key_reused");
        }
        assertThat(reserve(id, token, "key", List.of("A")).getStatusCode().value()).isEqualTo(200);
        // New keys still receive ordinary input validation and leave no state behind.
        assertThat(reserve(id, token, "new", List.of("A", "B")).getStatusCode().value()).isEqualTo(400);
        DatabaseInvariantAudit.assertConsistent(db, id);
        assertThat(db.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test void repeatedCancelRebookRacesPreserveOwnershipAndCounters() throws Exception {
        for (int round = 0; round < 10; round++) {
            UUID id = show(List.of("A", "B", "C"));
            String owner = auth.issue("owner-" + UUID.randomUUID());
            String attacker = auth.issue("attacker-" + UUID.randomUUID());
            var original = reserve(id, owner, "original", List.of("A", "B"));
            assertThat(original.getStatusCode().value()).isEqualTo(201);
            String rid = original.getBody().path("reservation_id").asText();
            List<Callable<ResponseEntity<JsonNode>>> work = new ArrayList<>();
            for (int i = 0; i < 5; i++) work.add(() -> {
                var response = cancel(rid, owner);
                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getBody().path("status").asText()).isEqualTo("cancelled");
                return response;
            });
            for (int i = 0; i < 5; i++) work.add(() -> {
                var response = cancel(rid, attacker);
                assertThat(response.getStatusCode().value()).isEqualTo(404);
                return response;
            });
            for (int i = 0; i < 20; i++) {
                String buyer = auth.issue("race-" + UUID.randomUUID());
                List<String> seats = i % 2 == 0 ? List.of("B", "A") : List.of("C", "B");
                work.add(() -> reserve(id, buyer, "race", seats));
            }
            var observing = new AtomicBoolean(true);
            var observer = Executors.newSingleThreadExecutor();
            var snapshots = observer.submit(() -> {
                int observed = 0;
                while (observing.get()) {
                    DatabaseInvariantAudit.assertConsistent(db, id);
                    observed++;
                    Thread.sleep(2);
                }
                return observed;
            });
            List<ResponseEntity<JsonNode>> results;
            try { results = concurrent(work); }
            finally {
                observing.set(false);
                try { assertThat(snapshots.get(10, TimeUnit.SECONDS)).isPositive(); }
                finally { observer.shutdownNow(); }
            }
            assertThat(results.stream().filter(r -> r.getStatusCode().value() == 201).count()).isLessThanOrEqualTo(1);
            assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(200, 201, 404, 409));
            if (results.stream().noneMatch(r -> r.getStatusCode().value() == 201))
                assertThat(reserve(id, attacker, "after", List.of("A", "B")).getStatusCode().value()).isEqualTo(201);
            var replay = reserve(id, owner, "original", List.of("A", "B"));
            assertThat(replay.getStatusCode().value()).isEqualTo(200);
            assertThat(replay.getBody().path("status").asText()).isEqualTo("cancelled");
            DatabaseInvariantAudit.assertConsistent(db, id);
            assertThat(db.queryForObject("SELECT count(*) FROM seats WHERE show_id=? AND status='confirmed'", Integer.class, id)).isEqualTo(2);
        }
    }

    private static class ModelBooking {
        final String id, user;
        final List<String> seats;
        boolean cancelled;
        ModelBooking(String id, String user, List<String> seats) { this.id = id; this.user = user; this.seats = seats; }
    }

    @Test void seededOperationSequencesAgreeWithIndependentBookingModel() {
        int seeds = Integer.getInteger("seat.safety.seeds", 10);
        int steps = Integer.getInteger("seat.safety.steps", 100);
        long firstSeed = Long.getLong("seat.safety.seed", 20261002L);
        assertThat(seeds).isPositive(); assertThat(steps).isPositive();
        for (int iteration = 0; iteration < seeds; iteration++) {
            long seed = firstSeed + iteration;
            Random random = new Random(seed);
            List<String> labels = IntStream.range(0, 12).mapToObj(i -> "S" + i).toList();
            UUID id = show(labels);
            String prefix = "model-" + UUID.randomUUID();
            List<String> users = IntStream.range(0, 4).mapToObj(i -> prefix + "-" + i).toList();
            Map<String, String> tokens = new HashMap<>();
            users.forEach(u -> tokens.put(u, auth.issue(u)));
            Map<String, ModelBooking> keys = new HashMap<>();
            Map<String, String> seatOwners = new HashMap<>();
            for (int step = 0; step < steps; step++) {
                try {
                    if (!keys.isEmpty() && random.nextInt(10) < 3) {
                        // Sorting by the stable model key below makes workload choice seed-reproducible.
                        var orderedKeys = keys.keySet().stream().sorted().toList();
                        var booking = keys.get(orderedKeys.get(random.nextInt(orderedKeys.size())));
                        boolean attack = random.nextInt(4) == 0;
                        String caller = attack ? users.stream().filter(u -> !u.equals(booking.user)).findFirst().orElseThrow() : booking.user;
                        var response = cancel(booking.id, tokens.get(caller));
                        assertThat(response.getStatusCode().value()).isEqualTo(attack ? 404 : 200);
                        if (!attack) {
                            if (!booking.cancelled) booking.seats.forEach(seatOwners::remove);
                            booking.cancelled = true;
                            assertThat(response.getBody().path("status").asText()).isEqualTo("cancelled");
                        }
                    } else {
                        String user = users.get(random.nextInt(users.size())), key = "k" + random.nextInt(20);
                        String scopedKey = user + "/" + key;
                        ModelBooking prior = keys.get(scopedKey);
                        List<String> requested = random.nextBoolean() ? List.of(labels.get(random.nextInt(labels.size())))
                                : List.of(labels.get(random.nextInt(6)), labels.get(6 + random.nextInt(6))).stream().sorted().toList();
                        if (prior != null && random.nextInt(3) == 0) requested = prior.seats;
                        requested = requested.stream().sorted().toList();
                        int held = keys.values().stream().filter(b -> b.user.equals(user) && !b.cancelled).mapToInt(b -> b.seats.size()).sum();
                        boolean free = requested.stream().noneMatch(seatOwners::containsKey);
                        int expected = prior != null ? (prior.seats.equals(requested) ? 200 : 409)
                                : (free && held + requested.size() <= 4 ? 201 : 409);
                        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(tokens.get(user));
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        var response = http.postForEntity("/shows/" + id + "/reserve", new HttpEntity<>(Map.of(
                                "seats", requested, "idempotency_key", key, "user_id", "spoofed-other-user"), headers), JsonNode.class);
                        assertThat(response.getStatusCode().value()).isEqualTo(expected);
                        if (expected < 300) {
                            JsonNode body = response.getBody();
                            assertThat(body.path("user_id").asText()).isEqualTo(user);
                            assertThat(body.path("amount_paise").asLong()).isEqualTo(25000L * requested.size());
                            if (expected == 201) {
                                var booking = new ModelBooking(body.path("reservation_id").asText(), user, requested);
                                keys.put(scopedKey, booking);
                                requested.forEach(label -> seatOwners.put(label, booking.id));
                                assertThat(body.path("status").asText()).isEqualTo("confirmed");
                            } else {
                                assertThat(body.path("reservation_id").asText()).isEqualTo(prior.id);
                                assertThat(body.path("status").asText()).isEqualTo(prior.cancelled ? "cancelled" : "confirmed");
                            }
                        } else if (prior != null) {
                            assertThat(response.getBody().path("error").asText()).isEqualTo("idempotency_key_reused");
                        }
                    }
                    DatabaseInvariantAudit.assertConsistent(db, id);
                    assertThat(db.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?", Integer.class, id)).isEqualTo(keys.size());
                    var actualOwners = new HashMap<String, String>();
                    db.query("SELECT label,reservation_id FROM seats WHERE show_id=? AND status='confirmed'",
                            rs -> { actualOwners.put(rs.getString(1), rs.getString(2)); }, id);
                    assertThat(actualOwners).isEqualTo(seatOwners);
                } catch (AssertionError e) {
                    throw new AssertionError("Reproduce with seed=" + seed + " step=" + step, e);
                }
            }
            System.out.printf("Safety model seed=%d steps=%d passed%n", seed, steps);
        }
    }

    /** Faults are opt-in and confined to an explicitly named local test database. */
    private String fault(UUID id, String operation, double sleep) {
        var url = java.net.URI.create(System.getenv("SEAT_TEST_DATABASE_URL").substring(5));
        assertThat(url.getHost()).isIn("localhost", "127.0.0.1", "::1", "[::1]");
        String database = db.queryForObject("SELECT current_database()", String.class);
        assertThat(database).endsWith("_tests");
        String name = "seatlab_fault_" + id.toString().replace("-", "");
        String body = operation.equals("raise") ? "RAISE EXCEPTION 'injected seat-write failure';"
                : "PERFORM pg_sleep(" + sleep + ");";
        db.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " + body + " RETURN NEW; END $$");
        db.execute("CREATE TRIGGER " + name + " AFTER UPDATE OF status ON seats FOR EACH ROW "
                + "WHEN (NEW.show_id = '" + id + "'::uuid AND NEW.status = 'confirmed') EXECUTE FUNCTION " + name + "()");
        return name;
    }
    private void removeFault(String name) {
        db.execute("DROP TRIGGER IF EXISTS " + name + " ON seats");
        db.execute("DROP FUNCTION IF EXISTS " + name + "()");
    }
    private int pausedBackend() {
        var ids = db.queryForList("""
                SELECT pid FROM pg_stat_activity WHERE datname=current_database()
                AND wait_event='PgSleep' AND query LIKE '%reserve_booking%'
                """, Integer.class);
        return ids.isEmpty() ? -1 : ids.get(0);
    }

    @Test @EnabledIfEnvironmentVariable(named="SEAT_TEST_FAULTS", matches="true")
    void midWriteFailureRollsBackEveryRowAndCounterThenSameKeyCanSucceed() {
        UUID id = show(List.of("A", "B"));
        String user = "rollback-" + UUID.randomUUID(), token = auth.issue(user);
        double before = meters.get("reservations_confirmed_total").counter().count();
        String trigger = fault(id, "raise", 0);
        try {
            assertThat(reserve(id, token, "retry", List.of("A", "B")).getStatusCode().value()).isEqualTo(500);
            assertThat(db.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?", Integer.class, id)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM user_counts WHERE show_id=?", Integer.class, id)).isZero();
            DatabaseInvariantAudit.assertConsistent(db, id);
            assertThat(meters.get("reservations_confirmed_total").counter().count()).isEqualTo(before);
        } finally { removeFault(trigger); }
        assertThat(reserve(id, token, "retry", List.of("A", "B")).getStatusCode().value()).isEqualTo(201);
        assertThat(meters.get("reservations_confirmed_total").counter().count()).isEqualTo(before + 1);
        DatabaseInvariantAudit.assertConsistent(db, id);
    }

    @Test @EnabledIfEnvironmentVariable(named="SEAT_TEST_FAULTS", matches="true")
    void terminatedDatabaseConnectionRollsBackAndPoolRecoversForSameKey() throws Exception {
        UUID id = show(List.of("A"));
        String token = auth.issue("disconnect-" + UUID.randomUUID());
        String trigger = fault(id, "sleep", 15);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var response = worker.submit(() -> reserve(id, token, "retry", List.of("A")));
            await().atMost(Duration.ofSeconds(10)).until(() -> pausedBackend() > 0);
            int pid = pausedBackend();
            assertThat(db.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pid)).isTrue();
            assertThat(response.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(500);
            assertThat(db.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?", Integer.class, id)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM user_counts WHERE show_id=?", Integer.class, id)).isZero();
            DatabaseInvariantAudit.assertConsistent(db, id);
        } finally { worker.shutdownNow(); removeFault(trigger); }
        assertThat(reserve(id, token, "retry", List.of("A")).getStatusCode().value()).isEqualTo(201);
        DatabaseInvariantAudit.assertConsistent(db, id);
    }

    @Test @EnabledIfEnvironmentVariable(named="SEAT_TEST_FAULTS", matches="true")
    void droppedClientResponseIsRecoveredBySameKeyWithoutAnotherBooking() throws Exception {
        UUID id = show(List.of("A"));
        String user = "lost-response-" + UUID.randomUUID(), token = auth.issue(user);
        String trigger = fault(id, "sleep", 2);
        try {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                byte[] body = "{\"seats\":[\"A\"],\"idempotency_key\":\"lost\"}".getBytes(StandardCharsets.UTF_8);
                String headers = "POST /shows/" + id + "/reserve HTTP/1.1\r\nHost: localhost\r\n"
                        + "Authorization: Bearer " + token + "\r\nContent-Type: application/json\r\n"
                        + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().write(body); socket.getOutputStream().flush();
                await().atMost(Duration.ofSeconds(10)).until(() -> pausedBackend() > 0);
                // Close while the transaction is in progress; intentionally never read its response.
            }
            await().atMost(Duration.ofSeconds(10)).until(() -> db.queryForObject(
                    "SELECT count(*) FROM reservations WHERE show_id=? AND status='confirmed'", Integer.class, id) == 1);
            var replay = reserve(id, token, "lost", List.of("A"));
            assertThat(replay.getStatusCode().value()).isEqualTo(200);
            assertThat(replay.getBody().path("user_id").asText()).isEqualTo(user);
            assertThat(db.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?", Integer.class, id)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT seat_count FROM user_counts WHERE show_id=? AND user_id=?", Integer.class, id, user)).isEqualTo(1);
            DatabaseInvariantAudit.assertConsistent(db, id);
        } finally { removeFault(trigger); }
    }
    private List<ResponseEntity<JsonNode>> concurrent(List<Callable<ResponseEntity<JsonNode>>> work) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(work.size());
        CountDownLatch ready = new CountDownLatch(work.size()), start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<JsonNode>>> jobs = new ArrayList<>();
            for (var task : work) jobs.add(workers.submit(() -> {
                ready.countDown(); start.await(); return task.call();
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
            for (var job : jobs) responses.add(job.get(30, TimeUnit.SECONDS));
            return responses;
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    @Test void batchedReadsIsolateBuyersShowsAndInvalidRequests() throws Exception {
        var labels = IntStream.range(0, 10).mapToObj(i -> "S\"" + i).toList();
        List<UUID> ids = List.of(show(labels), show(labels), show(labels));
        List<Callable<ResponseEntity<JsonNode>>> work = new ArrayList<>();
        for (UUID id : ids) for (String label : labels) {
            String token = auth.issue("batch-" + UUID.randomUUID());
            work.add(() -> reserve(id, token, "key", List.of(label)));
        }
        for (int i = 0; i < 10; i++) {
            String token = auth.issue("invalid-" + UUID.randomUUID());
            work.add(() -> reserve(ids.get(0), token, "key", List.of("missing")));
            work.add(() -> reserve(UUID.randomUUID(), token, "key", List.of("missing")));
        }
        var results = concurrent(work);
        assertThat(results.stream().filter(r -> r.getStatusCode().value() == 201).count()).isEqualTo(30);
        assertThat(results.stream().filter(r -> r.getStatusCode().value() == 400).count()).isEqualTo(10);
        assertThat(results.stream().filter(r -> r.getStatusCode().value() == 404).count()).isEqualTo(10);
        for (UUID id : ids)
            assertThat(db.queryForObject("SELECT count(*) FROM seats WHERE show_id=? AND status='confirmed'", Integer.class, id)).isEqualTo(10);
    }

    @Test void guardedUpsertLimitsNewUsersAndRollsBackDeclinedReservationRows() throws Exception {
        var labels = IntStream.range(0, 20).mapToObj(i -> "S" + i).toList();
        UUID id = show(labels);
        String user = "limit-" + UUID.randomUUID(), token = auth.issue(user);
        List<Callable<ResponseEntity<JsonNode>>> work = new ArrayList<>();
        for (String seat : labels) work.add(() -> reserve(id, token, seat, List.of(seat)));
        var results = concurrent(work);
        assertThat(results.stream().filter(r -> r.getStatusCode().value() == 201).count()).isEqualTo(4);
        assertThat(results.stream().filter(r -> r.getStatusCode().value() == 409
                && r.getBody().path("error").asText().equals("per_user_limit")).count()).isEqualTo(16);
        assertThat(db.queryForObject("SELECT seat_count FROM user_counts WHERE show_id=? AND user_id=?", Integer.class, id, user)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?", Integer.class, id)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT count(*) FROM seats WHERE show_id=? AND status='confirmed'", Integer.class, id)).isEqualTo(4);
    }

    @Test void multiSeatRacesRollBackLosingCountersAndSameKeyReplaysConfirmedRows() throws Exception {
        UUID id = show(List.of("A", "B", "C"));
        List<Callable<ResponseEntity<JsonNode>>> work = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String token = auth.issue("hot-" + UUID.randomUUID());
            work.add(() -> reserve(id, token, "hot", List.of("B", "A")));
        }
        var results = concurrent(work);
        assertThat(results.stream().filter(r -> r.getStatusCode().value() == 201).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.getStatusCode().value() == 409).count()).isEqualTo(39);
        assertThat(db.queryForObject("SELECT coalesce(sum(seat_count),0) FROM user_counts WHERE show_id=?", Integer.class, id)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM seats WHERE show_id=? AND status='available'", Integer.class, id)).isEqualTo(1);

        var winner = results.stream().filter(r -> r.getStatusCode().value() == 201).findFirst().orElseThrow().getBody();
        String token = auth.issue(winner.path("user_id").asText());
        work.clear();
        for (int i = 0; i < 20; i++) work.add(() -> reserve(id, token, "hot", List.of("A", "B")));
        for (var replay : concurrent(work)) {
            assertThat(replay.getStatusCode().value()).isEqualTo(200);
            assertThat(replay.getBody().path("reservation_id")).isEqualTo(winner.path("reservation_id"));
            assertThat(replay.getBody().path("status").asText()).isEqualTo("confirmed");
        }
        assertThat(reserve(id, token, "hot", List.of("C")).getStatusCode().value()).isEqualTo(409);
    }
    @Test void concurrentSameKeyHasOneCreationAndCancelledReplayDoesNotRebook() throws Exception {
        UUID id = show(List.of("A", "B"));
        String token = auth.issue("same-key-" + UUID.randomUUID());
        List<Callable<ResponseEntity<JsonNode>>> work = new ArrayList<>();
        for (int i = 0; i < 40; i++) work.add(() -> reserve(id, token, "shared", List.of("A")));
        var results = concurrent(work);
        assertThat(results.stream().filter(r -> r.getStatusCode().value() == 201).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.getStatusCode().value() == 200).count()).isEqualTo(39);
        String reservation = results.get(0).getBody().path("reservation_id").asText();
        assertThat(results).allSatisfy(r -> assertThat(r.getBody().path("reservation_id").asText()).isEqualTo(reservation));
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(token);
        var cancelled = http.postForEntity("/reservations/" + reservation + "/cancel",
                new HttpEntity<>(Map.of(), headers), JsonNode.class);
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        var replay = reserve(id, token, "shared", List.of("A"));
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().path("status").asText()).isEqualTo("cancelled");
        assertThat(reserve(id, token, "shared", List.of("B")).getStatusCode().value()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT count(*) FROM seats WHERE show_id=? AND status='available'", Integer.class, id)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?", Integer.class, id)).isEqualTo(1);
    }

}
