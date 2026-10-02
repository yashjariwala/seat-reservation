# Write-up

## The atomic decision

Every reserve is one Postgres transaction (READ COMMITTED) that passes three gates. Each gate is a single statement whose `WHERE` clause *is* the check, so there is no read-then-write window. Any decline throws, and the whole transaction rolls back.

1. **Idempotency** — `INSERT INTO reservations ... ON CONFLICT (user_id, idem_key) DO NOTHING`.
2. **Per-user limit** — `UPDATE user_counts SET seat_count = seat_count + n WHERE show_id=? AND user_id=? AND seat_count + n <= limit`. 0 rows updated ⇒ over limit. Ten parallel requests from one user serialize on that one counter row; the guard is re-evaluated against the committed value each time, so at most `limit` seats ever get through.
3. **Seats** — `SELECT ... WHERE label = ANY(?) ORDER BY label FOR UPDATE`, then `UPDATE seats SET status='confirmed', reservation_id=? WHERE ... AND status='available'`. If fewer than n rows change, roll back (`seat_taken`).

**Why the hot seat is race-free:** 500 requests for A12 all try to lock the same row. Postgres grants it to one; the rest wait. The winner flips it to `confirmed` and commits. Each waiter then re-checks `status='available'` against the committed row (READ COMMITTED re-evaluation on update), matches 0 rows and gets a clean 409. One 201, 499 × 409, no error path.

**Multi-seat, no deadlock:** every transaction takes locks in the same global order — reservation key → user counter → seats sorted by label. Two requests for `{A12,A13}` and `{A13,A12}` both lock A12 first, so neither can hold what the other waits for. Cancel follows the same order (it locks its seats `ORDER BY label` before freeing them).

**Partial requests:** all-or-nothing. If any requested seat is taken, nothing is reserved. Chosen because it is simpler to make correct (it falls out of the transaction rolling back), and a buyer asking for two adjacent seats rarely wants just one.

**Reconciliation invariant:** every seat is one row with exactly one status, so `available + held + confirmed == total` holds by construction. `GET /shows/{id}` reads all seats in one query (one snapshot), so the counts it returns always sum to the total, even mid-burst.

## Idempotency

- **Stored in:** the `reservations` row itself — `UNIQUE (user_id, idem_key)` plus a `request_hash` (show id + sorted seat list). Keys are per user, so one user can never collide with another's key.
- **Exactly once:** a concurrent retry's `INSERT` blocks on the unique index until the first transaction finishes. If the first committed, the retry hits the conflict, reads the stored row and returns it (200, `idempotent_replay`). If the first rolled back, the retry proceeds as a fresh request.
- **Same key, different body:** the hash differs ⇒ 409 `idempotency_key_reused`. The hash length-prefixes each label (`["A","B"]` → `1:A1:B`, `["A,B"]` → `3:A,B`) so two different seat lists can never collide.
- **Declines don't burn the key:** a declined request rolls back its reservation row, so retrying the same key re-evaluates rather than replaying a 409. Nothing was charged, so this is safe.

## Holds & expiry

Model chosen: reserve confirms immediately (matches the specified 201 body) and `POST /reservations/{id}/cancel` releases. Cancel:

- runs only for the token's user (`WHERE id=? AND user_id=?`; someone else's id ⇒ 404, which doesn't reveal it exists),
- frees seats `WHERE reservation_id = <this one>`, so it can never resurrect a seat now confirmed to someone else,
- decrements the user counter, and is idempotent (cancelling twice returns the cancelled reservation).

The `held` status exists in the schema for the next step: a `held` + `held_until` hold that a payment `confirm` turns into `confirmed`. Expiry would be lazy (`WHERE status='available' OR (status='held' AND held_until < now())` in the seat gate) plus a sweeper to keep counts accurate.

## Consistency vs availability under a partition

Postgres is the only source of truth, and the system chooses **consistency**. If the app can't reach the DB, readiness goes to 503 (so the platform stops routing to it) and reserves fail rather than guess. Selling a seat twice is worse than refusing to sell for a minute. A cache or a second store in the decision path would trade that away.

## Observability — what pages me at 2am

- **Any sustained 5xx** (`http_server_requests_seconds_count{status=~"5.."}`) — by design every domain outcome is 4xx, so 5xx means a real fault.
- **Readiness failing** — DB unreachable; sales are stopped.
- **Invariant drift** — `sum(seats{show_id=X})` ≠ the show's total, or `reservations_confirmed_total` diverging from confirmed rows. Should be impossible; if it fires, stop sales.
- **Latency / saturation** — p99 reserve latency and `hikaricp_connections_pending` climbing: requests queuing for DB connections. Usually the precursor to timeouts.

Not paged: high `seat_taken` / `per_user_limit` rates. During an on-sale those are the expected outcome.

## AI usage

> **TODO (Yash): rewrite this section in your own words before submitting.** Facts to start from are below.

- Built with Claude Code (Claude Opus). I set the stack (Java/Spring), the 5× load target (100k requests), and reviewed each step; Claude proposed the schema, the three-gate transaction and lock ordering, wrote most of the code, the burst tool and these docs.
- Things caught during the build: the first package name `in.seats` is illegal in Java (`in` is a keyword); the burst's key-reuse check initially assumed the original request always arrives first, which is wrong under concurrency, and was rewritten as "every success on a key has the same body".
- What I verified myself: _(fill in — e.g. the break-it experiments: removing `AND status='available'`, removing `ORDER BY label`, replacing the counter UPDATE with SELECT-then-UPDATE, and what the burst showed for each)_.

## What I'd do next

- Hold + confirm with expiry (above), and a payment step that confirms by reservation id.
- Fast-path decline: a cheap unlocked status read before taking the row lock, so hot-seat losers don't queue on the lock.
- Load test the deployed instance at higher concurrency and size the DB pool against the provider's connection limit.
- Real auth (IdP-issued JWT with expiry) instead of the demo HMAC token.
- Alerting rules checked into the repo alongside the metrics.
