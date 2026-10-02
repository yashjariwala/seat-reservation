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


## Deploy (Render + Neon, both free)

1. Neon: create a project → copy host, database, user, password.
2. Render: *New → Blueprint* → this repo (uses `render.yaml`). Set
   `DATABASE_URL=jdbc:postgresql://<host>/<db>?sslmode=require`, `DATABASE_USER`, `DATABASE_PASSWORD`.
   `TOKEN_SECRET` and `ADMIN_KEY` are generated; copy `ADMIN_KEY` for `burst.sh`.
3. No Render health check, deliberately: Render evicts an instance whose check takes >5s for 15s and restarts it after 60s; on 0.1 CPU a burst queues health checks past that, so the check would take down a busy-but-correct instance. In Render → Settings, leave **Health Check Path empty**. A crashed process is still restarted.

Config: `PORT`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `DB_POOL_SIZE` (10), `TOKEN_SECRET`, `ADMIN_KEY`.
