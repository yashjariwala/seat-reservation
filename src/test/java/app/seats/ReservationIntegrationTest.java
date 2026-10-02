package app.seats;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

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
