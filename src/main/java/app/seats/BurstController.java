package app.seats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, public demonstration runner. Fixed loopback target; credentials never leave the server. */
@RestController
@RequestMapping("/api/burst")
public class BurstController {
    private final ShowController shows;
    private final Auth auth;
    private final ObjectMapper json;
    private final String adminKey;
    private final long cooldownMs;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
    private final ExecutorService clients = Executors.newFixedThreadPool(4);
    private final HttpClient http = HttpClient.newBuilder().executor(clients)
            .connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build();
    private volatile int port;
    private volatile Run current;
    private long nextStart;

    BurstController(ShowController shows, Auth auth, ObjectMapper json,
                    @Value("${ADMIN_KEY:dev-admin-key}") String adminKey,
                    @Value("${BURST_COOLDOWN_SECONDS:300}") long cooldownSeconds) {
        this.shows = shows;
        this.auth = auth;
        this.json = json;
        this.adminKey = adminKey;
        this.cooldownMs = Math.max(0, cooldownSeconds) * 1000;
    }

    @EventListener
    void serverReady(WebServerInitializedEvent event) { port = event.getWebServer().getPort(); }

    record Start(String preset) {}
    record Spec(int requests, int concurrency, int seats) {}
    record Check(String name, boolean passed, String detail) {}
    record Event(long at, String message) {}
    record Work(String user, String key, List<String> seats) {}
    record Result(Work work, int status, JsonNode body) {}

    static Spec spec(String preset) {
        return switch (preset == null ? "demo" : preset) {
            case "demo" -> new Spec(2000, 16, 500);
            case "full" -> new Spec(20000, 32, 5000);
            default -> throw ApiError.badRequest("preset must be demo or full");
        };
    }

    @GetMapping
    synchronized Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("run", current == null ? null : current.snapshot());
        state.put("cooldown_seconds", current != null && current.running() ? 0 : Math.max(0, (nextStart - System.currentTimeMillis() + 999) / 1000));
        return state;
    }

    @PostMapping
    synchronized ResponseEntity<Map<String, Object>> start(@RequestBody Start request) {
        Spec spec = spec(request.preset());
        if (current != null && current.running()) throw ApiError.conflict("burst_running", "A burst is already running. Follow its live progress.");
        if (System.currentTimeMillis() < nextStart) throw new ApiError(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                "burst_cooldown", "The shared runner is cooling down. Please wait for the countdown.");
        if (port == 0) throw ApiError.conflict("not_ready", "Server is still starting.");
        current = new Run(spec);
        Run run = current;
        nextStart = Long.MAX_VALUE;
        coordinator.submit(() -> execute(run));
        return ResponseEntity.accepted().body(state());
    }

    private static final class Run {
        final String id = UUID.randomUUID().toString();
        final Spec spec;
        final long started = System.currentTimeMillis();
        volatile long finished;
        volatile String status = "preparing", phase = "Creating a fresh show", showId;
        volatile Map<String, Object> inventory = Map.of();
        final AtomicInteger completed = new AtomicInteger();
        final ConcurrentMap<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        final List<Check> checks = new CopyOnWriteArrayList<>();
        final List<Event> events = new CopyOnWriteArrayList<>();
        Run(Spec spec) { this.spec = spec; }
        boolean running() { return finished == 0; }
        void event(String message) { events.add(new Event(System.currentTimeMillis(), message)); }
        void outcome(String name) { outcomes.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet(); }
        void check(String name, boolean passed, String detail) { checks.add(new Check(name, passed, detail)); }
        Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id); out.put("spec", spec); out.put("started_at", started); out.put("finished_at", finished);
            out.put("status", status); out.put("phase", phase); out.put("show_id", showId);
            out.put("completed", completed.get()); out.put("elapsed_seconds", ((finished == 0 ? System.currentTimeMillis() : finished) - started) / 1000.0);
            Map<String, Integer> counts = new TreeMap<>(); outcomes.forEach((k, v) -> counts.put(k, v.get()));
            out.put("outcomes", counts); out.put("checks", List.copyOf(checks)); out.put("events", List.copyOf(events));
            out.put("inventory", inventory); return out;
        }
    }

    private void execute(Run run) {
        ExecutorService workers = Executors.newFixedThreadPool(run.spec.concurrency());
        try {
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < run.spec.seats(); i++) labels.add("S" + String.format("%05d", i + 1));
            var show = shows.create(adminKey, new ShowController.CreateShow("Dashboard burst " + run.id.substring(0, 8), labels, 25000L, 4));
            run.showId = show.get("id").toString();
            run.event("Fresh show created · " + labels.size() + " seats · ₹250 per seat · limit 4");
            List<Work> workload = workload(run.spec.requests(), labels);
            Map<String, String> tokens = new HashMap<>();
            for (Work w : workload) tokens.computeIfAbsent(w.user(), u -> auth.issue("b" + run.id.substring(0, 8) + "-" + u));
            Queue<Result> results = new ConcurrentLinkedQueue<>();
            AtomicInteger cursor = new AtomicInteger();
            run.status = "running"; run.phase = "Firing concurrent reservations";
            run.event("Burst started · " + run.spec.requests() + " requests · " + run.spec.concurrency() + " concurrent clients");
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < run.spec.concurrency(); i++) jobs.add(workers.submit(() -> {
                for (int index; !Thread.currentThread().isInterrupted() && System.currentTimeMillis() - run.started < 600000 && (index = cursor.getAndIncrement()) < workload.size();) {
                    Work w = workload.get(index);
                    try {
                        Result result = reserve(run, w, tokens.get(w.user()), null);
                        results.add(result);
                        run.outcome(result.status() == 201 ? "confirmed" : result.status() == 200 ? "idempotent_replay"
                                : result.status() >= 500 ? "server_error" : result.body().path("error").asText("unexpected_response"));
                    } catch (Exception e) { run.outcome("network_error"); }
                    finally { run.completed.incrementAndGet(); }
                }
            }));
            boolean reconciles = true;
            int polls = 0;
            while (jobs.stream().anyMatch(j -> !j.isDone())) {
                if (System.currentTimeMillis() - run.started > 600000) throw new IllegalStateException("Run exceeded ten minutes");
                run.inventory = inventory(run.showId);
                reconciles &= invariant(run.inventory); polls++;
                Thread.sleep(750);
            }
            for (Future<?> job : jobs) job.get();
            run.inventory = inventory(run.showId);
            run.phase = "Checking correctness";
            run.check("Zero server / network errors", run.completed.get() == run.spec.requests() && count(run, "server_error") + count(run, "network_error") == 0,
                    count(run, "server_error") + " server errors · " + count(run, "network_error") + " network errors");
            List<Result> created = results.stream().filter(r -> r.status() == 201).toList();
            Map<String, Integer> winners = new HashMap<>(), perUser = new HashMap<>();
            Map<String, Set<String>> ids = new HashMap<>(), bodies = new HashMap<>();
            for (Result r : results) {
                if (r.status() == 201) {
                    r.work().seats().forEach(s -> winners.merge(s, 1, Integer::sum));
                    perUser.merge(r.work().user(), r.work().seats().size(), Integer::sum);
                }
                if (r.status() == 201 || r.status() == 200) {
                    String key = r.work().user() + ":" + r.work().key();
                    ids.computeIfAbsent(key, k -> new HashSet<>()).add(r.body().path("reservation_id").asText());
                    bodies.computeIfAbsent(key, k -> new HashSet<>()).add(r.work().seats().toString());
                }
            }
            boolean hot = labels.subList(0, 5).stream().allMatch(s -> winners.getOrDefault(s, 0) == 1);
            run.check("No double selling", hot && winners.values().stream().allMatch(n -> n == 1), "Exactly one winner for each of the 5 hot seats; no duplicate seat sales");
            int max = perUser.values().stream().mapToInt(n -> n).max().orElse(0);
            run.check("Per-user limit", max <= 4, "Maximum held by a user: " + max + " / 4");
            run.check("Idempotency", ids.values().stream().allMatch(s -> s.size() == 1) && bodies.values().stream().allMatch(s -> s.size() == 1)
                    && count(run, "idempotent_replay") > 0, "One reservation and one request body per successful key; " + count(run, "idempotent_replay") + " replays");
            int sold = created.stream().mapToInt(r -> r.work().seats().size()).sum();
            run.check("Inventory reconciliation", reconciles && invariant(run.inventory) && ((Number)run.inventory.get("confirmed")).intValue() == sold,
                    polls + " live snapshots checked; confirmed inventory matches successful reservations");
            // Deterministic checks complement the random burst, including a guaranteed post-cancel rebook.
            deterministicChecks(run, workers, created, tokens);
            run.inventory = inventory(run.showId);
            run.check("Final reconciliation", invariant(run.inventory), "Available + held + confirmed = declared seats");
            run.status = run.checks.stream().allMatch(Check::passed) ? "passed" : "failed";
            run.phase = run.status.equals("passed") ? "All checks passed" : "Some checks failed";
            run.event(run.phase);
        } catch (Exception e) {
            run.status = "failed"; run.phase = "Run interrupted";
            run.check("Runner completed", false, "Could not complete the test. Check service health and server logs.");
            run.event("Run interrupted; partial results remain visible");
            org.slf4j.LoggerFactory.getLogger(BurstController.class).error("Dashboard burst {} failed", run.id, e);
        } finally {
            workers.shutdownNow(); run.finished = System.currentTimeMillis();
            synchronized (this) { nextStart = run.finished + cooldownMs; }
        }
    }

    private void deterministicChecks(Run run, ExecutorService workers, List<Result> created, Map<String, String> tokens) throws Exception {
        if (created.isEmpty()) { run.check("Cancel and rebook", false, "No reservation was created"); return; }
        Result victim = created.get(0);
        String ownerToken = tokens.get(victim.work().user());
        String path = "/reservations/" + victim.body().path("reservation_id").asText() + "/cancel";
        var c1 = workers.submit(() -> call("POST", path, Map.of(), ownerToken));
        var c2 = workers.submit(() -> call("POST", path, Map.of(), ownerToken));
        var attacker = call("POST", path, Map.of(), auth.issue("b" + run.id.substring(0, 8) + "-attacker"));
        Result a = c1.get(), b = c2.get();
        run.check("Owner-only, idempotent cancellation", a.status() == 200 && b.status() == 200 && attacker.status() == 404
                && a.body().path("status").asText().equals("cancelled") && b.body().path("status").asText().equals("cancelled"),
                "Owner cancels twice; another user is rejected");
        String newUser = "b" + run.id.substring(0, 8) + "-rebook";
        String token = auth.issue(newUser);
        Work rebook = new Work(newUser, "post-cancel", victim.work().seats());
        Result booked = reserve(run, rebook, token, "spoofed-owner");
        Result replay = reserve(run, rebook, token, null);
        var partialShow = shows.create(adminKey, new ShowController.CreateShow("Atomicity check " + run.id.substring(0, 8), List.of("A", "B"), 25000L, 4));
        String partialPath = "/shows/" + partialShow.get("id") + "/reserve";
        Result first = call("POST", partialPath, Map.of("seats", List.of("A"), "idempotency_key", "partial-first"), ownerToken);
        Result partial = call("POST", partialPath, Map.of("seats", List.of("A", "B"), "idempotency_key", "partial-second"), token);
        Result partialState = call("GET", "/shows/" + partialShow.get("id"), null, null);
        // Both seat labels are valid, and one is already taken: the existing key must still reject a changed body.
        String validOther = "S00001".equals(victim.work().seats().get(0)) ? "S00002" : "S00001";
        Result reused = reserve(run, new Work(newUser, "post-cancel", List.of(validOther)), token, null);
        run.check("Rebooking and token identity", booked.status() == 201 && booked.body().path("user_id").asText().equals(newUser)
                && replay.status() == 200 && replay.body().path("reservation_id").equals(booked.body().path("reservation_id")),
                "Released seats book successfully; spoofed body identity is ignored; retry returns the same reservation");
        Result lateCancel = call("POST", path, Map.of(), ownerToken);
        Result afterRebook = call("GET", "/shows/" + run.showId, null, null);
        Set<String> reservedLabels = new HashSet<>();
        afterRebook.body().path("seats").forEach(seat -> {
            if (seat.path("status").asText().equals("confirmed")) reservedLabels.add(seat.path("label").asText());
        });
        run.check("Late cancellation preserves new booking", lateCancel.status() == 200 && reservedLabels.containsAll(rebook.seats()),
                "Cancelling the old reservation again cannot free the new owner's seats");
        run.check("Different body rejected", reused.status() == 409 && reused.body().path("error").asText().equals("idempotency_key_reused"), "Same key with different valid seats returns 409");
        run.check("Multi-seat all-or-nothing", first.status() == 201 && partial.status() == 409
                && partialState.body().path("counts").path("available").asInt() == 1
                && partialState.body().path("counts").path("confirmed").asInt() == 1,
                "A taken + free seat request is declined; the free seat stays available");
        run.event("Cancellation, rebooking, identity and key-reuse checks completed");
    }

    private static int count(Run run, String outcome) { return run.outcomes.getOrDefault(outcome, new AtomicInteger()).get(); }
    private Result reserve(Run run, Work w, String token, String spoof) throws Exception {
        Map<String, Object> body = new HashMap<>(); body.put("seats", w.seats()); body.put("idempotency_key", w.key());
        if (spoof != null) body.put("user_id", spoof);
        Result response = call("POST", "/shows/" + run.showId + "/reserve", body, token);
        return new Result(w, response.status(), response.body());
    }
    private Result call(String method, String path, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(150));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (method.equals("GET")) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(null, response.statusCode(), json.readTree(response.body()));
    }
    private Map<String, Object> inventory(String showId) throws Exception {
        Result response = call("GET", "/shows/" + showId, null, null);
        if (response.status() != 200) throw new IllegalStateException("Inventory read failed");
        JsonNode body = response.body(), counts = body.path("counts");
        return Map.of("available", counts.path("available").asInt(), "held", counts.path("held").asInt(),
                "confirmed", counts.path("confirmed").asInt(), "total", body.path("total_seats").asInt());
    }
    private static boolean invariant(Map<String, Object> inventory) {
        return ((Number)inventory.get("available")).intValue() + ((Number)inventory.get("held")).intValue()
                + ((Number)inventory.get("confirmed")).intValue() == ((Number)inventory.get("total")).intValue();
    }
    private static List<Work> workload(int total, List<String> labels) {
        Random random = new Random(42);
        List<Work> work = new ArrayList<>();
        for (int i = 0; i < total * 4 / 10; i++) work.add(new Work("hot-" + i, "h" + i, List.of(labels.get(i % 5))));
        for (int u = 0; u < 20; u++) for (int i = 0; i < 10; i++)
            work.add(new Work("limit-" + u, "l" + u + "-" + i, List.of(labels.get(5 + u * 10 + i))));
        int retries = total / 10, reuse = total / 100;
        while (work.size() < total - retries - reuse) {
            int i = work.size(); String first = labels.get(205 + random.nextInt(labels.size() - 205));
            String second = labels.get(205 + random.nextInt(labels.size() - 205));
            List<String> seats = i % 4 == 0 && !first.equals(second) ? List.of(first, second).stream().sorted().toList() : List.of(first);
            work.add(new Work("cold-" + random.nextInt(total / 8), "c" + i, seats));
        }
        List<Work> originals = List.copyOf(work);
        for (int i = 0; i < retries; i++) work.add(originals.get(random.nextInt(originals.size())));
        for (int i = 0; i < reuse; i++) {
            Work w = originals.get(random.nextInt(originals.size()));
            String other = labels.get(w.seats().contains(labels.get(0)) ? 1 : 0);
            work.add(new Work(w.user(), w.key(), List.of(other)));
        }
        Collections.shuffle(work, random); return work;
    }
    @PreDestroy void close() { coordinator.shutdownNow(); clients.shutdownNow(); }
}
