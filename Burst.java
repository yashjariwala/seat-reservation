import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * ADMIN_KEY from environment or .env (default dev-admin-key for localhost only). Exit code 0 only if every correctness check passes.
 */
public class Burst {
    static final int HOT_SEATS = 5, LIMIT = 4, LIMIT_USERS = 20, LIMIT_FIRES = 10;
    static HttpClient[] clients;
    static final AtomicInteger nextClient = new AtomicInteger();
    // Each worker thread sticks to one client; ~80 threads per client keeps every HTTP/2 connection under the
    // edge's ~100 concurrent-stream cap (the JDK client errors instead of opening a second connection).
    static final ThreadLocal<HttpClient> http = ThreadLocal.withInitial(() -> clients[nextClient.getAndIncrement() % clients.length]);
    static String base;

    record Req(String user, String key, List<String> seats, String kind) {}
    record Res(Req req, int status, String code, String reservationId) {}

    static String adminKey() throws Exception {
        String key = System.getenv("ADMIN_KEY");
        if (key == null && Files.exists(Path.of(".env"))) {
            for (String line : Files.readAllLines(Path.of(".env"))) {
                if (line.startsWith("ADMIN_KEY=")) {
                    key = line.substring(10).trim();
                    if (key.length() >= 2 && ((key.startsWith("\"") && key.endsWith("\""))
                            || (key.startsWith("'") && key.endsWith("'")))) key = key.substring(1, key.length()-1);
                }
            }
        }
        if ((key == null || key.isBlank()) && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(URI.create(base).getHost()))
            key = "dev-admin-key";
        if (key == null || key.isBlank()) throw new IllegalStateException("Set ADMIN_KEY in the environment or git-ignored .env");
        return key;
    }

    public static void main(String[] a) {
        try { run(a); } catch (Throwable e) { e.printStackTrace(); System.exit(2); }
    }

    static void run(String[] a) throws Exception {
        base = a.length > 0 ? a[0].replaceAll("/+$", "") : "http://localhost:8080";
        int total = a.length > 1 ? Integer.parseInt(a[1]) : 20000;
        int conc = a.length > 2 ? Integer.parseInt(a[2]) : 1000;
        int nSeats = a.length > 3 ? Integer.parseInt(a[3]) : 5000;
        String adminKey = adminKey();
        // HTTPS: HTTP/2 multiplexes the burst over a few TLS connections instead of a handshake per in-flight
        // request (the edge resets handshake storms from one IP). Plain http (local) stays HTTP/1.1.
        var exec = Executors.newFixedThreadPool(32);
        clients = new HttpClient[conc / 80 + 1];
        for (int i = 0; i < clients.length; i++)
            clients[i] = HttpClient.newBuilder()
                    .version(base.startsWith("https") ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(30)).executor(exec).build();

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
            for (int attempt = 1; ; attempt++) {   // setup, not the test: retry transient edge/network errors
                try {
                    HttpResponse<String> r = send("POST", "/auth/token", "{\"user_id\":\"" + u + "\"}", Map.of("X-Admin-Key", adminKey));
                    if (r.statusCode() == 200) { tokens.put(u, field(r.body(), "token")); return null; }
                    if (attempt == 5) throw new IllegalStateException("token mint failed: " + r.statusCode() + " " + r.body());
                } catch (java.io.IOException e) {
                    if (attempt == 5) throw e;
                }
                Thread.sleep(500L * attempt);
            }
        }).toList(), 200);

        if (conc >= reqs.size()) {
            var warm = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            for (HttpClient client : clients) warm.add(client.sendAsync(
                    HttpRequest.newBuilder(URI.create(base + "/actuator/health/liveness"))
                            .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString()));
            CompletableFuture.allOf(warm.toArray(CompletableFuture[]::new)).join();
            if (warm.stream().anyMatch(f -> f.join().statusCode() != 200))
                throw new IllegalStateException("Connection warm-up failed before measured burst");
        }

        // ---- burst, with an invariant watcher polling show state ----
        Map<String, Double> before = scrape();
        List<String> invariantViolations = new CopyOnWriteArrayList<>();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger polls = new AtomicInteger(), failedPolls = new AtomicInteger();
        Thread watcher = new Thread(() -> {
            while (running.get()) {
                try {
                    int[] c = counts(get("/shows/" + show).body());
                    polls.incrementAndGet();
                    if (c[0] + c[1] + c[2] != c[3]) invariantViolations.add(Arrays.toString(c));
                } catch (Exception ignored) { failedPolls.incrementAndGet(); }
                try { Thread.sleep(500); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        });
        watcher.start();

        System.out.printf("firing %d reserve requests...%n", reqs.size());
        long t0 = System.nanoTime();
        double submissionSpan = Double.NaN;
        List<Res> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger netErrors = new AtomicInteger();
        if (conc >= reqs.size()) {
            // 20,000 outstanding requests use asynchronous I/O, not 20,000 platform threads.
            var requests = new ArrayList<HttpRequest>();
            for (Req r : reqs) {
                String body = "{\"idempotency_key\":\"" + show.substring(0, 8) + "-" + r.key() + "\",\"seats\":["
                        + r.seats().stream().map(label -> "\"" + label + "\"").collect(Collectors.joining(",")) + "]}";
                requests.add(HttpRequest.newBuilder(URI.create(base + "/shows/" + show + "/reserve"))
                        .timeout(Duration.ofSeconds(180)).header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + tokens.get(r.user()))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build());
            }
            t0 = System.nanoTime();
            var done = new ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < requests.size(); i++) {
                Req work = reqs.get(i);
                done.add(clients[i / 80].sendAsync(requests.get(i), HttpResponse.BodyHandlers.ofString())
                        .handle((response, error) -> {
                            if (error != null) netErrors.incrementAndGet();
                            else {
                                try { results.add(result(work, response)); }
                                catch (Exception e) { netErrors.incrementAndGet(); }
                            }
                            return null;
                        }));
            }
            submissionSpan = (System.nanoTime() - t0) / 1e9;
            System.out.printf("submission_span_seconds=%.3f (client dispatch; arrival timing not verified)%n", submissionSpan);
            CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).join();
        } else {
            runAll(reqs.stream().map(r -> (Callable<Object>) () -> {
                String body = "{\"idempotency_key\":\"" + show.substring(0, 8) + "-" + r.key() + "\",\"seats\":["
                        + r.seats().stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]}";
                try {
                    HttpResponse<String> resp = send("POST", "/shows/" + show + "/reserve", body,
                            Map.of("Authorization", "Bearer " + tokens.get(r.user())));
                    results.add(result(r, resp));
                } catch (Exception e) {
                    netErrors.incrementAndGet();
                }
                return null;
            }).toList(), conc);
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        running.set(false);
        watcher.join();

        // ---- report ----
        System.out.printf("%ndone in %.1fs (%.0f req/s)%n%nOutcome distribution:%n", secs, reqs.size() / secs);
        results.stream().collect(Collectors.groupingBy(r -> r.status() + " " + r.code(), TreeMap::new, Collectors.counting()))
                .forEach((k, v) -> System.out.printf("  %-32s %d%n", k, v));
        System.out.printf("  %-32s %d%n", "network errors/timeouts", netErrors.get());

        List<String> fails = new ArrayList<>();
        if (!Double.isNaN(submissionSpan))
            check(fails, submissionSpan <= 1, "all requests dispatched within one second (" + submissionSpan + " s)");
        long fiveXX = results.stream().filter(r -> r.status() >= 500).count();
        check(fails, fiveXX == 0, "zero 5xx (got " + fiveXX + ")");
        check(fails, netErrors.get() == 0, "zero network errors (got " + netErrors.get() + ")");
        long unexpected = results.stream().filter(r -> r.status() != 200 && r.status() != 201
                && !(r.status() == 409 && Set.of("seat_taken", "per_user_limit", "idempotency_key_reused").contains(r.code()))).count();
        check(fails, unexpected == 0, "only successful/replayed reservations or domain declines (unexpected=" + unexpected + ")");

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
        check(fails, polls.get() > 0 && failedPolls.get() == 0 && invariantViolations.isEmpty(), "invariant observed during burst (" + polls.get() + " polls, failed reads=" + failedPolls.get() + ", violations=" + invariantViolations.size() + ")");

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

        // ---- phase 2: cancel races, rebooking, spoofed identity ----
        // For each victim reservation, concurrently: owner cancels twice, an attacker tries to cancel, and three
        // other users try to rebook the same seats while spoofing "user_id": <owner> in the body.
        List<Res> victims = created.stream().filter(r -> r.req().kind().equals("cold")).limit(200).toList();
        Map<String, String> p2tokens = new ConcurrentHashMap<>();
        List<String> p2users = new ArrayList<>(List.of("attacker"));
        for (int i = 0; i < victims.size(); i++) for (int j = 0; j < 4; j++) p2users.add("rb-" + i + "-" + j);  // j=3: guaranteed rebook
        runAll(p2users.stream().map(u -> (Callable<Object>) () -> {
            p2tokens.put(u, field(send("POST", "/auth/token", "{\"user_id\":\"" + u + "\"}", Map.of("X-Admin-Key", adminKey)).body(), "token"));
            return null;
        }).toList(), 100);
        record P2(String kind, int victim, String user, int status, String body) {}
        List<P2> p2 = Collections.synchronizedList(new ArrayList<>());
        List<Callable<Object>> p2tasks = new ArrayList<>();
        for (int i = 0; i < victims.size(); i++) {
            int vi = i;
            Res v = victims.get(i);
            String cancelPath = "/reservations/" + v.reservationId() + "/cancel";
            for (int k = 0; k < 2; k++) p2tasks.add(() -> {
                var resp = send("POST", cancelPath, "{}", Map.of("Authorization", "Bearer " + tokens.get(v.req().user())));
                p2.add(new P2("owner_cancel", vi, v.req().user(), resp.statusCode(), resp.body()));
                return null;
            });
            p2tasks.add(() -> {
                var resp = send("POST", cancelPath, "{}", Map.of("Authorization", "Bearer " + p2tokens.get("attacker")));
                p2.add(new P2("attacker_cancel", vi, "attacker", resp.statusCode(), resp.body()));
                return null;
            });
            for (int j = 0; j < 3; j++) {
                String u = "rb-" + i + "-" + j;
                String body = "{\"user_id\":\"" + v.req().user() + "\",\"idempotency_key\":\"" + show.substring(0, 8) + "-rb-" + i + "-" + j
                        + "\",\"seats\":[" + v.req().seats().stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]}";
                p2tasks.add(() -> {
                    var resp = send("POST", "/shows/" + show + "/reserve", body, Map.of("Authorization", "Bearer " + p2tokens.get(u)));
                    p2.add(new P2("rebook", vi, u, resp.statusCode(), resp.body()));
                    return null;
                });
            }
        }
        Collections.shuffle(p2tasks, rnd);
        System.out.printf("%nPhase 2: %d victims -> %d concurrent cancel/attack/rebook requests%n", victims.size(), p2tasks.size());
        runAll(p2tasks, conc);
        p2.stream().collect(Collectors.groupingBy(x -> x.kind() + " " + x.status(), TreeMap::new, Collectors.counting()))
                .forEach((k, n) -> System.out.printf("  %-32s %d%n", k, n));

        long p2fiveXX = p2.stream().filter(x -> x.status() >= 500).count();
        check(fails, p2fiveXX == 0, "phase 2: zero 5xx (got " + p2fiveXX + ")");
        long attackerOk = p2.stream().filter(x -> x.kind().equals("attacker_cancel") && x.status() != 404).count();
        check(fails, attackerOk == 0, "cancel someone else's reservation -> 404 (non-404s=" + attackerOk + ")");
        long ownerBad = p2.stream().filter(x -> x.kind().equals("owner_cancel") && (x.status() != 200 || !x.body().contains("\"cancelled\""))).count();
        check(fails, ownerBad == 0, "owner double-cancel: both 200 cancelled (bad=" + ownerBad + ")");
        long spoofed = p2.stream().filter(x -> x.kind().equals("rebook") && x.status() == 201 && !field(x.body(), "user_id").equals(x.user())).count();
        check(fails, spoofed == 0, "spoofed body user_id ignored: every 201 is the token's user (violations=" + spoofed + ")");
        Map<Integer, Long> rebookWins = p2.stream().filter(x -> x.kind().equals("rebook") && x.status() == 201)
                .collect(Collectors.groupingBy(P2::victim, Collectors.counting()));
        long multiWin = rebookWins.values().stream().filter(n -> n > 1).count();
        check(fails, multiWin == 0, "freed seats rebooked by at most one user each (victims with >1 winner=" + multiWin + ")");
        // Guaranteed rebook: every victim is cancelled by now, so a fresh user (still spoofing the owner) must win
        // any victim nobody won during the race. Proves freed seats are cleanly re-bookable, not just "not broken".
        List<Callable<Object>> late = new ArrayList<>();
        for (int i = 0; i < victims.size(); i++) {
            if (rebookWins.containsKey(i)) continue;
            int vi = i;
            String u = "rb-" + i + "-3";
            Res v = victims.get(i);
            String body = "{\"user_id\":\"" + v.req().user() + "\",\"idempotency_key\":\"" + show.substring(0, 8) + "-late-" + i
                    + "\",\"seats\":[" + v.req().seats().stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]}";
            late.add(() -> {
                var resp = send("POST", "/shows/" + show + "/reserve", body, Map.of("Authorization", "Bearer " + p2tokens.get(u)));
                p2.add(new P2("late_rebook", vi, u, resp.statusCode(), resp.body()));
                return null;
            });
        }
        runAll(late, conc);
        List<P2> lateRes = p2.stream().filter(x -> x.kind().equals("late_rebook")).toList();
        long lateBad = lateRes.stream().filter(x -> x.status() != 201 || !field(x.body(), "user_id").equals(x.user())).count();
        System.out.printf("  rebooked during race: %d of %d victims; guaranteed rebook after cancel: %d%n",
                rebookWins.size(), victims.size(), lateRes.size());
        check(fails, lateBad == 0, "post-cancel rebook succeeds as the token's user, spoof ignored (bad=" + lateBad + " of " + lateRes.size() + ")");

        // Every victim's seats are now confirmed again (to a rebooker), so confirmed is back to the pre-phase-2 count,
        // and each of those seats reads "confirmed" in the show state.
        String state = get("/shows/" + show).body();
        int[] c2 = counts(state);
        Set<String> confirmedLabels = new HashSet<>();
        Matcher sm = Pattern.compile("\\{\"label\":\"([^\"]+)\",\"status\":\"confirmed\"\\}|\\{\"status\":\"confirmed\",\"label\":\"([^\"]+)\"\\}").matcher(state);
        while (sm.find()) confirmedLabels.add(sm.group(1) != null ? sm.group(1) : sm.group(2));
        long notConfirmed = victims.stream().flatMap(v -> v.req().seats().stream()).filter(l -> !confirmedLabels.contains(l)).count();
        System.out.printf("  final available=%d held=%d confirmed=%d total=%d%n", c2[0], c2[1], c2[2], c2[3]);
        check(fails, notConfirmed == 0, "every cancelled seat ended confirmed to exactly one rebooker (not confirmed=" + notConfirmed + ")");
        check(fails, c2[2] == c[2], "confirmed after phase 2 (" + c2[2] + ") == before (" + c[2] + "): every cancel matched by one rebook");
        check(fails, c2[0] + c2[1] + c2[2] == c2[3], "reconciliation after phase 2: available+held+confirmed == total");

        System.out.println(fails.isEmpty() ? "\nALL CHECKS PASSED" : "\nFAILED: " + fails.size() + " check(s)");
        System.exit(fails.isEmpty() ? 0 : 1);
    }


    static void check(List<String> fails, boolean ok, String what) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (!ok) fails.add(what);
    }

    static Res result(Req work, HttpResponse<String> response) {
        String id = response.statusCode() < 300 ? field(response.body(), "reservation_id") : null;
        String code = response.statusCode() < 300 ? "ok"
                : response.body().contains("\"error\"") ? field(response.body(), "error") : "non-domain response";
        return new Res(work, response.statusCode(), code, id);
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
        return http.get().send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> send(String method, String path, String json, Map<String, String> headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(b::header);
        return http.get().send(b.build(), HttpResponse.BodyHandlers.ofString());
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
            if (!m.find()) throw new IllegalStateException("Incomplete inventory response");
            c[i] = Integer.parseInt(m.group(1));
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
