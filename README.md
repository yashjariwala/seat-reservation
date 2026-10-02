# Seat Reservation at Scale

Sells assigned seats for a show without ever double-selling, exceeding a per-user limit, or double-charging a retry — under on-sale stampedes. Java 17 · Spring Boot 3 · Postgres.

**Live:** https://seat-reservation-62kf.onrender.com (free tier — first request after idle cold-starts in ~1 min; `burst.sh` waits for readiness)

Design and trade-offs: [WRITEUP.md](WRITEUP.md).

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

Creates a fresh show, then fires concurrently: a hot-seat storm (40% of traffic on 5 seats), a per-user-limit attack (20 users × 10 parallel requests on limit 4), exact retries of in-flight requests (10%), same-key-different-seats (1%), and random traffic. Prints the outcome distribution and checks every correctness rule against both the API and `/actuator/prometheus`; exits non-zero on any failure.

Local result (M-series Mac, Postgres 16, pool of 10):

| requests | concurrency | time | 5xx | checks |
|---|---|---|---|---|
| 20,000 | 1,000 | 4.8 s | 0 | all pass |
| 100,000 | 5,000 | ~10 s | 0 | all pass |

## API

All money is integer paise. JSON field names are snake_case.

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/auth/token` | — | `{"user_id":"alice"}` → `{"token":...}` (demo login) |
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
TOKEN=$(curl -s -XPOST $B/auth/token -H 'content-type: application/json' -d '{"user_id":"alice"}' | jq -r .token)
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

## Deploy (Render + Neon, both free)

1. Neon: create a project → copy host, database, user, password.
2. Render: *New → Blueprint* → this repo (uses `render.yaml`). Set
   `DATABASE_URL=jdbc:postgresql://<host>/<db>?sslmode=require`, `DATABASE_USER`, `DATABASE_PASSWORD`.
   `TOKEN_SECRET` and `ADMIN_KEY` are generated; copy `ADMIN_KEY` for `burst.sh`.
3. Render health check uses the readiness probe, so a deploy only goes live once the DB is reachable.

Config: `PORT`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `DB_POOL_SIZE` (10), `TOKEN_SECRET`, `ADMIN_KEY`.
