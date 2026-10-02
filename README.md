# Seat Reservation at Scale

Sells assigned seats for a show without ever double-selling, exceeding a per-user limit, or double-charging a retry — under on-sale stampedes. Java 17 · Spring Boot 3 · Postgres.

**Live:** https://seat-reservation-62kf.onrender.com (free tier — first request after idle cold-starts in ~1 min; `burst.sh` waits for readiness)

Design and trade-offs: [WRITEUP.md](WRITEUP.md).

Latest external acceptance evidence: [LIVE-VERIFICATION.md](LIVE-VERIFICATION.md). The exact 500-buyer hot-seat test passed; the exact 20,000-buyer storm currently fails due to responses outside the reservation controller.

## Run it

```bash
docker compose up --build          # app + Postgres on http://localhost:8080
```

Without Docker (needs JDK 17 and a local Postgres with a `seats` database):

```bash
./mvnw spring-boot:run
```

In VS Code: install the *Extension Pack for Java* + *Spring Boot Extension Pack*, open the folder, run `App.java`.

## Burst (one command)

```bash
./burst.sh <BASE_URL> [requests=20000] [concurrency=1000] [seats=5000]
ADMIN_KEY=<key> ./burst.sh https://seat-reservation-62kf.onrender.com
```

Creates a fresh show, then fires concurrently: a hot-seat storm (40% of traffic on 5 seats), a per-user-limit attack (20 users × 10 parallel requests on limit 4), exact retries of in-flight requests (10%), same-key-different-seats (1%), and random traffic. Then phase 2: for 200 confirmed reservations, concurrently, the owner cancels twice, an attacker tries to cancel, and three other users try to rebook the freed seats with `"user_id": <owner>` spoofed in the body. Prints the outcome distribution and checks every correctness rule against both the API and `/actuator/prometheus`; exits non-zero on any failure.

Local result (M-series Mac, Postgres 16, pool of 10):

| requests | concurrency | time | 5xx | checks |
|---|---|---|---|---|
| 20,000 | 1,000 | 4.8 s | 0 | all pass |
| 100,000 | 5,000 | ~10 s | 0 | all pass |

## Exact single-seat storm

```bash
ADMIN_KEY=<deployed-key> java HotSeat.java https://seat-reservation-62kf.onrender.com 500
ADMIN_KEY=<deployed-key> java HotSeat.java https://seat-reservation-62kf.onrender.com 20000
```

The harness creates a fresh show containing A12, prepares a distinct authenticated buyer and unique idempotency key for each request, warms connections, then submits every reservation asynchronously without a concurrency semaphore. It requires exactly one 201, all remaining responses to be `409 seat_taken`, zero transport/unexpected responses, and one confirmed seat in the final state. Booking requests are never retried, so edge failures cannot be hidden by recovery.

It also requires the client submission span to be at most one second and prints that span separately from completion time. Client submission does not prove that all requests arrived at the server within that second: TLS, HTTP/2 flow control, the network and proxy can queue traffic. Tokens and connection warm-up are outside the measured storm. The harness can read `ADMIN_KEY` from the git-ignored `.env`; it never prints credentials. Use the mixed-workload `burst.sh` as well to test limits, idempotency and cancellation. With `ADMIN_KEY` in `.env`, run `./burst.sh https://seat-reservation-62kf.onrender.com 20000 20000 5000` to dispatch all 20,000 mixed reservations together using asynchronous I/O. The default 1,000-concurrency run is a bounded workload and does not establish the full simultaneous bar. The tool warms connections first, reports client submission span separately, rejects non-domain responses, and reports failed inventory polls rather than treating missing counts as zero.

## Local simultaneous verification

With Java 17+, Python 3 and a running local PostgreSQL (including `createdb`, `dropdb` and `ps`), run:

```bash
python3 scripts/verify_local_burst.py
```

This builds the current code, starts its own JVM with a 256 MB heap and 64 MB direct-memory cap, creates a disposable loopback database, and dispatches 20,000 mixed reservations at 20,000 concurrency. It saves app/build/burst logs and sampled memory measurements, then removes its own database and server. It does not contact the live deployment or limit CPU to Render's quota.

The server uses Undertow with 64 workers, two I/O threads and 1 KB pooled direct buffers. The burst prefers HTTP/2, including cleartext HTTP/2 locally, and prints the negotiated warm-up protocol. Set `BURST_HTTP_VERSION=1` to test HTTP/1.1 separately; the local independent-connection storm currently fails with connection resets. A multiplexed HTTP/2 pass does not establish an HTTP/1.1 pass. Reservation requests are never retried, and transport failures still fail the test.

## Live dashboard

Open `/` to view live health, throughput, latency, reservation outcomes and seat reconciliation. The **Start burst** button creates a fresh show and runs one of two fixed presets:

| Preset | Requests | Concurrent clients | Seats |
|---|---|---|---|
| Quick demo | 2,000 | 16 | 500 |
| Assignment burst | 20,000 | 32 | 5,000 |

The request mix is 40% hot-seat contention, 10% retries, 1% key reuse, 200 per-user-limit attempts, and random single/multi-seat requests. Final checks cover all-or-nothing booking, owner-only double cancellation, guaranteed rebooking, spoofed identity, and a late cancellation after rebooking. Outcomes count the initial reservation burst; the subsequent checks also appear in service metrics.

Runs are shared across visitors on the instance. `POST /api/burst` accepts only `{"preset":"demo"}` or `{"preset":"full"}`; `GET /api/burst` returns live progress and the latest results. Only one run can execute at a time, followed by a 5-minute cooldown (`BURST_COOLDOWN_SECONDS`, default 300). Runs are limited to ten minutes, and results are held in memory until the next run or restart. The public runner creates demo inventory; it never returns credentials or accepts a target URL.

Dashboard requests use the local HTTP reservation API on the deployed server, so they exercise actual transactions and metrics without sending the admin key to the browser. They do **not** measure the Render edge/network. Use `./burst.sh <PUBLIC_URL>` for that external load test.

## API

All money is integer paise. JSON field names are snake_case.

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/auth/token` | `X-Admin-Key` | `{"user_id":"alice"}` → `{"token":...}` (stands in for an identity provider; users can't mint each other's tokens) |
| POST | `/shows` | `X-Admin-Key` | `{"name","seats":[...],"price_paise","per_user_limit"?}` → 201 |
| GET | `/shows/{id}` | — | per-seat status + `counts` + `total_seats` |
| POST | `/shows/{id}/reserve` | `Bearer` | `{"seats":[...],"idempotency_key"}` (or `Idempotency-Key` header) |
| POST | `/reservations/{id}/cancel` | `Bearer` | owner only |

Reserve outcomes:

| Status | `error` | Meaning |
|---|---|---|
| 201 | — | confirmed |
| 200 | — | idempotent replay: the original reservation, nothing changed |
| 409 | `seat_taken` | any requested seat unavailable (all-or-nothing: nothing reserved) |
| 409 | `per_user_limit` | would exceed the show's per-user limit |
| 409 | `idempotency_key_reused` | key already used with a different request |
| 400 / 401 / 404 | | bad input / bad token / unknown show |

```bash
B=http://localhost:8080
SHOW=$(curl -s -XPOST $B/shows -H 'X-Admin-Key: dev-admin-key' -H 'content-type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}' | jq -r .id)
TOKEN=$(curl -s -XPOST $B/auth/token -H 'X-Admin-Key: dev-admin-key' -H 'content-type: application/json' -d '{"user_id":"alice"}' | jq -r .token)
curl -s -XPOST $B/shows/$SHOW/reserve -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"seats":["A1"],"idempotency_key":"k-1"}'
```

## Observe

- Liveness `/actuator/health/liveness` — process up.
- Readiness `/actuator/health/readiness` — checks Postgres; returns 503 when the DB is unreachable (verified by stopping Postgres).
- Metrics `/actuator/prometheus`:
  - `reservations_confirmed_total`
  - `reservations_declined_total{reason="seat_taken|per_user_limit|idempotent_replay|idempotency_key_reused"}`
  - `seats{show_id, status="available|held|confirmed"}` — refreshed from the DB every second
  - `http_server_requests_seconds_count{status}` — 5xx rate
- Logs: one JSON line per request with `request_id` (inbound `X-Request-Id` honoured, echoed back), `user_id`, `outcome`, `status`, `duration_ms`.

To capture resource evidence during a manual live test, start this read-only recorder in another terminal before the burst:

```bash
python3 scripts/record_metrics.py https://seat-reservation-62kf.onrender.com --seconds 300 --output /tmp/seatlab-live-metrics.jsonl
```

It samples every two seconds, saves JVM memory/GC, CPU time, process uptime, connection-pool and reservation metrics, and records failed scrapes and observed uptime resets. It sends no reservations. Failed scrapes leave observation gaps; JVM metrics do not measure complete container RSS, and CPU usage gauges are not a direct percentage of Render's quota. Cumulative CPU-time deltas between successful scrapes can estimate average core usage for that interval. No credentials are needed.

The server also writes `resource_sample` structured log entries every five seconds from its own dedicated thread. These include instance ID, uptime, heap/nonheap/direct memory, CPU cores used over the actual sampling interval, cumulative GC time and DB active/idle/waiting counts. When accessible on Linux, the cgroup memory counter is included as `container_memory_bytes`; this is container memory accounting, not JVM heap or process RSS. No database query or public HTTP request is needed. Each sample also includes Undertow worker queue length (`http_worker_queue`), busy/max worker counts, and available Linux CPU-throttling counters (`cpu_throttled_periods_total/delta`, `cpu_throttled_usec_total/delta`, `cpu_periods_total/delta`). The `_delta` fields cover the actual sampling interval, not necessarily five seconds. Availability flags distinguish unsupported readings from zero; the first CPU-throttling sample has totals only. Throttled microseconds are normalized across cgroup v1/v2. These counters describe the visible cgroup, and zero does not rule out restrictions imposed by an inaccessible ancestor cgroup. The worker queue measures dispatched tasks, not all TCP connections or requests waiting upstream. Search Render logs for `resource_sample` during a burst. Samples can still be delayed by CPU starvation, GC pauses or logging backpressure, so inspect `sample_interval_seconds` and timestamp gaps. Set `resource.sampling.enabled=false` to disable it.

## Regression tests

`./mvnw test` runs unit tests. For the complete PostgreSQL suite, create a dedicated local database and enable fault injection explicitly:

```bash
createdb seat_tests
SEAT_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/seat_tests SEAT_TEST_FAULTS=true ./mvnw test
```

Fault injection requires a loopback database URL and a database name ending in `_tests`. It installs temporary, show-scoped triggers and deliberately terminates one database connection. Those tests expect HTTP 500 during the injected failure, then verify rollback and recovery; they are not zero-error burst acceptance tests.

The suite checks concurrent bookings, overlapping multi-seat requests, limits, cancellation/rebooking races, token-derived identity, lost responses, and key reuse. An independent booking model predicts randomized API outcomes, while one-statement database audits check ownership, reservation/seat links, user counters and inventory totals. The audit itself is tested against deliberately corrupted relationships.

To run 10,000 model operations, or reproduce one seed:

```bash
SEAT_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/seat_tests SEAT_TEST_FAULTS=true ./mvnw -Dseat.safety.seeds=25 -Dseat.safety.steps=400 test
SEAT_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/seat_tests ./mvnw -Dseat.safety.seed=20261002 -Dseat.safety.seeds=1 -Dseat.safety.steps=400 -Dtest=ReservationIntegrationTest#seededOperationSequencesAgreeWithIndependentBookingModel test
```

For actual process crashes and cold-start recovery:

```bash
./mvnw -DskipTests package
python3 scripts/verify_restart.py
```

This script creates and removes its own disposable local database, launches separate JVMs on an unused local port, kills its own child process before and after commit, and checks same-key recovery after each restart. It saves a JSON report and app logs in a temporary directory printed on completion. It does not connect to the public service.


To reproduce a Docker CPU-limited burst locally:

```bash
python3 scripts/benchmark_docker.py --workers 64 --cpus 0.1 --compiler c1
python3 scripts/benchmark_docker.py --workers 64 --cpus 0.1 --compiler tiered
```

Docker must be running. Each invocation builds and copies an immutable JAR, creates its own disposable PostgreSQL container/database, and runs 20,000 requests with 20,000 client concurrency against a local app limited to 0.1 CPU and 512 MB. Startup, burst logs and results are saved in the printed temporary directory; only its own containers/network are removed. `--workers 64 16` compares worker counts sequentially; `--profile` enables JFR for every variant and adds overhead. Database CPU is not capped, and local networking does not reproduce Neon latency or Render's public ingress. This test is separate from an unrestricted local pass and from live acceptance.


On 2026-10-02, CPU-limited runs of the current app with 64 workers and JFR profiling both failed: C1 returned 4,508 responses before 15,492 request timeouts; normal tiered compilation returned 2,103 responses before 17,897 timeouts. Each reservation phase reached the 180-second client deadline, and final inventory reads timed out, so full reconciliation and phase 2 were not verified. Tiered compilation also took 287.6 seconds to reach readiness. Neither run exhausted the heap; sampled worker queues peaked at 18,754 and 18,885 respectively. These are profiled local-container results, not live acceptance results or precise forecasts of Render performance. The production compiler setting was retained.


An additional 16-worker CPU-limited comparison found every servlet worker waiting for HTTP request-body data while the preflight readers and database were idle. The app now buffers small bodies asynchronously before servlet dispatch using Undertow's `RequestBufferingHandler`, capped at four pooled buffers per request (4 KiB with the configured buffer size); larger bodies retain streaming behavior. Booking decisions and HTTP status semantics are unchanged. A regression test holds eight partial bodies open with four workers, checks liveness, then completes every body and verifies successful token responses.

With this change, the profiled 16-worker run received 5,665 responses before 14,335 timeouts, compared with 165 responses and 19,835 timeouts without buffering. Both failed acceptance; final inventory reads timed out and phase 2 was not verified. The unrestricted local 20,000-request burst passed in 4.5 seconds and all 22 PostgreSQL/unit regression tests passed. These results do not establish a CPU-limited or live pass, and the 64-worker production default is retained pending comparison.


## Deploy (Render + Neon, both free)

1. Neon: create a project → copy host, database, user, password.
2. Render: *New → Blueprint* → this repo (uses `render.yaml`). Set
   `DATABASE_URL=jdbc:postgresql://<host>/<db>?sslmode=require`, `DATABASE_USER`, `DATABASE_PASSWORD`.
   `TOKEN_SECRET` and `ADMIN_KEY` are generated; copy `ADMIN_KEY` for `burst.sh`.
3. No Render health check, deliberately: Render evicts an instance whose check takes >5s for 15s and restarts it after 60s; on 0.1 CPU a burst queues health checks past that, so the check would take down a busy-but-correct instance. In Render → Settings, leave **Health Check Path empty**. A crashed process is still restarted.

Config: `PORT`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `DB_POOL_SIZE` (10), `TOKEN_SECRET`, `ADMIN_KEY`.
