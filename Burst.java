import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * On-sale stampede against a running service. JDK 17+, no deps:
 *   java Burst.java <BASE_URL> [requests=20000] [concurrency=1000] [seats=5000]
 * Env ADMIN_KEY (default dev-admin-key). Exit code 0 only if every correctness check passes.
 */
public class Burst {
    static final int HOT_SEATS = 5, LIMIT = 4, LIMIT_USERS = 20, LIMIT_FIRES = 10;
    static HttpClient http;
    static String base;

    record Req(String user, String key, List<String> seats, String kind) {}
    record Res(Req req, int status, String code, String reservationId) {}

    public static void main(String[] a) throws Exception {
        base = a.length > 0 ? a[0].replaceAll("/+$", "") : "http://localhost:8080";
        int total = a.length > 1 ? Integer.parseInt(a[1]) : 20000;
        int conc = a.length > 2 ? Integer.parseInt(a[2]) : 1000;
        int nSeats = a.length > 3 ? Integer.parseInt(a[3]) : 5000;
        String adminKey = Optional.ofNullable(System.getenv("ADMIN_KEY")).orElse("dev-admin-key");
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .executor(Executors.newFixedThreadPool(32)).build();

        System.out.printf("target=%s requests=%d concurrency=%d seats=%d%n", base, total, conc, nSeats);
        waitReady();

        // ---- show ----
        List<String> labels = IntStream.range(0, nSeats).mapToObj(i -> (char) ('A' + i / 200) + "" + (i % 200 + 1)).toList();
        String showJson = "{\"name\":\"burst-" + System.currentTimeMillis() + "\",\"price_paise\":25000,\"per_user_limit\":" + LIMIT
                + ",\"seats\":[" + labels.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]}";
        String show = field(send("POST", "/shows", showJson, Map.of("X-Admin-Key", adminKey)).body(), "id");
        System.out.println("show=" + show);
        List<String> hot = labels.subList(0, HOT_SEATS);
        List<String> limitSeats = labels.subList(HOT_SEATS, HOT_SEATS + LIMIT_USERS * LIMIT_FIRES);
        List<String> cold = labels.subList(HOT_SEATS + LIMIT_USERS * LIMIT_FIRES, nSeats);

        // ---- workload ----
        Random rnd = new Random(42);
        List<Req> reqs = new ArrayList<>();
        int hotN = (int) (total * 0.40), replayN = (int) (total * 0.10), reuseN = Math.max(10, total / 100);
        int hotUsers = Math.max(500, Math.min(hotN / 4, 10000)), coldUsers = Math.max(500, total / 8);
        for (int i = 0; i < hotN; i++)
            reqs.add(new Req("hot-" + rnd.nextInt(hotUsers), "h" + i, List.of(hot.get(i % HOT_SEATS)), "hot"));
        for (int u = 0; u < LIMIT_USERS; u++)
            for (int f = 0; f < LIMIT_FIRES; f++)
                reqs.add(new Req("lim-" + u, "l" + u + "-" + f, List.of(limitSeats.get(u * LIMIT_FIRES + f)), "limit"));
        int coldN = total - reqs.size() - replayN - reuseN;
        for (int i = 0; i < coldN; i++) {
            String s1 = cold.get(rnd.nextInt(cold.size())), s2 = cold.get(rnd.nextInt(cold.size()));
            List<String> seats = rnd.nextInt(4) == 0 && !s1.equals(s2) ? List.of(s1, s2) : List.of(s1);
            reqs.add(new Req("u-" + rnd.nextInt(coldUsers), "c" + i, seats, "cold"));
        }
        List<Req> originals = List.copyOf(reqs);
        for (int i = 0; i < replayN; i++) {   // exact duplicate fired concurrently with its original
            Req o = originals.get(rnd.nextInt(originals.size()));
            reqs.add(new Req(o.user(), o.key(), o.seats(), "replay"));
        }
        for (int i = 0; i < reuseN; i++) {    // same key, different seats -> must be 409
            Req o = originals.get(rnd.nextInt(originals.size()));
            reqs.add(new Req(o.user(), o.key(), List.of(cold.get(rnd.nextInt(cold.size()))), "reuse"));
        }
        Collections.shuffle(reqs, rnd);

        // ---- tokens (not part of the burst) ----
        Map<String, String> tokens = new ConcurrentHashMap<>();
        Set<String> users = reqs.stream().map(Req::user).collect(Collectors.toSet());
        System.out.printf("minting %d tokens...%n", users.size());
        runAll(users.stream().map(u -> (Callable<Object>) () -> {
            tokens.put(u, field(send("POST", "/auth/token", "{\"user_id\":\"" + u + "\"}", Map.of()).body(), "token"));
            return null;
        }).toList(), 200);

        // ---- burst, with an invariant watcher polling show state ----
        Map<String, Double> before = scrape();
        List<String> invariantViolations = new CopyOnWriteArrayList<>();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger polls = new AtomicInteger();
        Thread watcher = new Thread(() -> {
            while (running.get()) {
                try {
                    int[] c = counts(get("/shows/" + show).body());
                    polls.incrementAndGet();
                    if (c[0] + c[1] + c[2] != c[3]) invariantViolations.add(Arrays.toString(c));
                    Thread.sleep(500);
                } catch (Exception ignored) {}
            }
        });
        watcher.start();

        System.out.printf("firing %d reserve requests...%n", reqs.size());
        long t0 = System.nanoTime();
        List<Res> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger netErrors = new AtomicInteger();
        runAll(reqs.stream().map(r -> (Callable<Object>) () -> {
            String body = "{\"idempotency_key\":\"" + show.substring(0, 8) + "-" + r.key() + "\",\"seats\":["
                    + r.seats().stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]}";
            try {
                HttpResponse<String> resp = send("POST", "/shows/" + show + "/reserve", body,
                        Map.of("Authorization", "Bearer " + tokens.get(r.user())));
                String rid = resp.statusCode() < 300 ? field(resp.body(), "reservation_id") : null;
                results.add(new Res(r, resp.statusCode(), resp.statusCode() < 300 ? "ok" : field(resp.body(), "error"), rid));
            } catch (Exception e) {
                netErrors.incrementAndGet();
            }
            return null;
        }).toList(), conc);
        double secs = (System.nanoTime() - t0) / 1e9;
        running.set(false);
        watcher.join();

        // ---- report ----
        System.out.printf("%ndone in %.1fs (%.0f req/s)%n%nOutcome distribution:%n", secs, reqs.size() / secs);
        results.stream().collect(Collectors.groupingBy(r -> r.status() + " " + r.code(), TreeMap::new, Collectors.counting()))
                .forEach((k, v) -> System.out.printf("  %-32s %d%n", k, v));
        System.out.printf("  %-32s %d%n", "network errors/timeouts", netErrors.get());

        List<String> fails = new ArrayList<>();
        long fiveXX = results.stream().filter(r -> r.status() >= 500).count();
        check(fails, fiveXX == 0, "zero 5xx (got " + fiveXX + ")");
        check(fails, netErrors.get() == 0, "zero network errors (got " + netErrors.get() + ")");

        List<Res> created = results.stream().filter(r -> r.status() == 201).toList();
        for (String s : hot) {
            long winners = created.stream().filter(r -> r.req().seats().contains(s)).count();
            long losers = results.stream().filter(r -> r.req().seats().contains(s) && r.status() == 409).count();
            check(fails, winners == 1, "hot seat " + s + ": exactly one 201 (got " + winners + ", 409s=" + losers + ")");
        }
        Map<String, List<String>> seatOwners = new HashMap<>();
        created.forEach(r -> r.req().seats().forEach(s -> seatOwners.computeIfAbsent(s, k -> new ArrayList<>()).add(r.req().user())));
        long doubleSold = seatOwners.values().stream().filter(o -> o.size() > 1).count();
        check(fails, doubleSold == 0, "no seat confirmed twice (double-sold=" + doubleSold + ")");

        Map<String, Integer> perUser = new HashMap<>();
        created.forEach(r -> perUser.merge(r.req().user(), r.req().seats().size(), Integer::sum));
        int maxHeld = perUser.values().stream().max(Integer::compare).orElse(0);
        int limHeld = IntStream.range(0, LIMIT_USERS).map(u -> perUser.getOrDefault("lim-" + u, 0)).max().orElse(0);
        check(fails, maxHeld <= LIMIT, "per-user limit across all users (max held=" + maxHeld + ")");
        check(fails, limHeld <= LIMIT, "limit attack: 10 parallel on limit=4 (max held=" + limHeld + ")");

        Map<String, Set<String>> byKey = new HashMap<>();
        results.stream().filter(r -> r.reservationId() != null)
                .forEach(r -> byKey.computeIfAbsent(r.req().user() + "|" + r.req().key(), k -> new HashSet<>()).add(r.reservationId()));
        long splitKeys = byKey.values().stream().filter(s -> s.size() > 1).count();
        Map<String, Long> created201PerKey = created.stream().collect(Collectors.groupingBy(r -> r.req().user() + "|" + r.req().key(), Collectors.counting()));
        long dup201 = created201PerKey.values().stream().filter(n -> n > 1).count();
        check(fails, splitKeys == 0 && dup201 == 0, "idempotency: one reservation per key (keys with >1 reservation=" + splitKeys + ", >1 201s=" + dup201 + ")");
        // Whichever body reached a key first wins; every success on that key must then be for that same body.
        Map<String, Set<List<String>>> bodiesPerKey = new HashMap<>();
        results.stream().filter(r -> r.status() < 300)
                .forEach(r -> bodiesPerKey.computeIfAbsent(r.req().user() + "|" + r.req().key(), k -> new HashSet<>()).add(r.req().seats()));
        long mixed = bodiesPerKey.values().stream().filter(s -> s.size() > 1).count();
        long reused409 = results.stream().filter(r -> "idempotency_key_reused".equals(r.code())).count();
        check(fails, mixed == 0, "same key + different seats never both succeed (keys=" + mixed + ", 409 reused=" + reused409 + ")");

        int[] c = counts(get("/shows/" + show).body());
        int soldSeats = created.stream().mapToInt(r -> r.req().seats().size()).sum();
        System.out.printf("%nFinal show state: available=%d held=%d confirmed=%d total=%d%n", c[0], c[1], c[2], c[3]);
        check(fails, c[0] + c[1] + c[2] == c[3], "reconciliation: available+held+confirmed == total");
        check(fails, c[2] == soldSeats, "confirmed seats (" + c[2] + ") == seats in 201 responses (" + soldSeats + ")");
        check(fails, invariantViolations.isEmpty(), "invariant held during burst (" + polls.get() + " polls, violations=" + invariantViolations.size() + ")");

        Thread.sleep(2500);   // seat gauges refresh every 1s
        Map<String, Double> after = scrape();
        double dConfirmed = after.getOrDefault("reservations_confirmed_total", 0.0) - before.getOrDefault("reservations_confirmed_total", 0.0);
        check(fails, (long) dConfirmed == created.size(), "metric reservations_confirmed_total delta (" + (long) dConfirmed + ") == 201s (" + created.size() + ")");
        for (String reason : List.of("seat_taken", "per_user_limit", "idempotent_replay", "idempotency_key_reused")) {
            String m = "reservations_declined_total{reason=\"" + reason + "\"}";
            long d = (long) (after.getOrDefault(m, 0.0) - before.getOrDefault(m, 0.0));
            long api = results.stream().filter(r -> reason.equals("idempotent_replay") ? r.status() == 200 : reason.equals(r.code())).count();
            check(fails, d == api, "metric declined{" + reason + "} delta (" + d + ") == API (" + api + ")");
        }
        String[] st = {"available", "held", "confirmed"};
        for (int i = 0; i < 3; i++) {
            double g = after.getOrDefault("seats{show_id=\"" + show + "\",status=\"" + st[i] + "\"}", 0.0);
            check(fails, (int) g == c[i], "gauge seats{" + st[i] + "} (" + (int) g + ") == API (" + c[i] + ")");
        }

        System.out.println(fails.isEmpty() ? "\nALL CHECKS PASSED" : "\nFAILED: " + fails.size() + " check(s)");
        System.exit(fails.isEmpty() ? 0 : 1);
    }


    static void check(List<String> fails, boolean ok, String what) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (!ok) fails.add(what);
    }

    static void runAll(List<Callable<Object>> tasks, int conc) throws Exception {
        Semaphore sem = new Semaphore(conc);
        ExecutorService ex = Executors.newFixedThreadPool(conc);
        List<Future<Object>> fs = new ArrayList<>();
        for (Callable<Object> t : tasks) {
            sem.acquire();
            fs.add(ex.submit(() -> { try { return t.call(); } finally { sem.release(); } }));
        }
        for (Future<Object> f : fs) f.get();
        ex.shutdown();
    }

    static void waitReady() throws Exception {
        for (int i = 0; i < 90; i++) {   // free tiers cold-start slowly
            try { if (get("/actuator/health/readiness").statusCode() == 200) return; } catch (Exception ignored) {}
            System.out.println("waiting for readiness...");
            Thread.sleep(2000);
        }
        throw new IllegalStateException("service not ready");
    }

    static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> send(String method, String path, String json, Map<String, String> headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!m.find()) throw new IllegalStateException("no " + name + " in " + json);
        return m.group(1);
    }

    /** [available, held, confirmed, total_seats] from GET /shows/{id}. */
    static int[] counts(String json) {
        int[] c = new int[4];
        String[] keys = {"available", "held", "confirmed", "total_seats"};
        for (int i = 0; i < 4; i++) {
            Matcher m = Pattern.compile("\"" + keys[i] + "\"\\s*:\\s*(\\d+)").matcher(json);
            if (m.find()) c[i] = Integer.parseInt(m.group(1));
        }
        return c;
    }

    static Map<String, Double> scrape() throws Exception {
        Map<String, Double> out = new HashMap<>();
        for (String line : get("/actuator/prometheus").body().split("\n")) {
            if (line.startsWith("#") || line.isBlank()) continue;
            int sp = line.lastIndexOf(' ');
            out.put(line.substring(0, sp), Double.parseDouble(line.substring(sp + 1)));
        }
        return out;
    }
}
