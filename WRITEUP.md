# Write-up

## The atomic decision

A reserve first performs one autocommit snapshot read, then opens a Postgres transaction (READ COMMITTED) only if booking might succeed. The mutation transaction passes three atomic gates. A decline inside it rolls back every mutation; a fast decline or existing-key replay performs no mutations.

0. **Fast decline (not a gate, an optimisation)** — an unlocked `count(*) FILTER (WHERE status='available')` over the requested seats. The same SQL snapshot joins the user's idempotency key, so a committed winner is visible together with its sold seat. An existing key replays or rejects a changed body; otherwise unavailable seats return 409 without starting a transaction or locking anything. A stale read can only cause a decline, never a sale, so it is safe; the gates below remain the only path to `confirmed`. Most of a stampede is `seat_taken`, so this is most of the throughput on a small CPU.
1. **Idempotency** — `INSERT INTO reservations ... ON CONFLICT (user_id, idem_key) DO NOTHING`.
2. **Per-user limit** — `INSERT INTO user_counts (..., seat_count) VALUES (..., n) ON CONFLICT (show_id, user_id) DO UPDATE SET seat_count = user_counts.seat_count + EXCLUDED.seat_count WHERE user_counts.seat_count + EXCLUDED.seat_count <= limit`. The request size is checked first, so a new counter also cannot exceed the limit. 0 rows updated ⇒ over limit. Ten parallel requests from one user serialize on that one counter row; the guard is re-evaluated against the committed value each time, so at most `limit` seats ever get through.
3. **Seats** — `SELECT ... WHERE label = ANY(?) ORDER BY label FOR UPDATE`, then `UPDATE seats SET status='confirmed', reservation_id=? WHERE ... AND status='available'`. If fewer than n rows change, roll back (`seat_taken`).

**Why the hot seat is race-free:** 500 requests for A12 all try to lock the same row. Postgres grants it to one; the rest wait. The winner flips it to `confirmed` and commits. Each waiter then re-checks `status='available'` against the committed row (READ COMMITTED re-evaluation on update), matches 0 rows and gets a clean 409. One 201, 499 × 409, no error path.

**Multi-seat, no deadlock:** every transaction takes locks in the same global order — reservation key → user counter → seats sorted by label. Two requests for `{A12,A13}` and `{A13,A12}` both lock A12 first, so neither can hold what the other waits for. Cancel follows the same order (it locks its seats `ORDER BY label` before freeing them).

**Partial requests:** all-or-nothing. If any requested seat is taken, nothing is reserved. Chosen because it is simpler to make correct (it falls out of the transaction rolling back), and a buyer asking for two adjacent seats rarely wants just one.

**Reconciliation invariant:** `GET /shows/{id}` counts statuses over all seat rows in one query (one snapshot, so it is consistent mid-burst) and returns `total_seats` as the count *declared at creation* (stored on `shows`). The two are independent, so `available + held + confirmed == total_seats` is a real check — a lost or duplicated seat row breaks it. The same pair is exported as `seats{status}` and `seats_declared` for the dashboard and alerts.

## Efficiency

The successful reserve path uses five SQL statements instead of eight (excluding transaction begin/commit): one combined metadata/seat-state query, the reservation insert, one guarded counter upsert, sorted seat locks, and the conditional seat update. The reservation is inserted as confirmed but remains invisible until the whole transaction commits; a decline rolls it back along with the counter and seat changes. This removes a redundant reservation status update without weakening atomicity. Metadata, requested seat state and the existing idempotency row now share one autocommit query. Already-taken-seat declines and existing-key replays use one statement and no transaction begin/rollback. If a key commits after that snapshot, the transactional unique-key gate still serializes the attempted booking correctly.

Seat metrics keep the same gauge instances for each show/status. A refresh publishes an immutable snapshot rather than unregistering and recreating all series. The scrape lock pins that snapshot across both seat families, and absent statuses explicitly read as zero. Reservation counters are registered once and reused. These changes reduce network calls, row rewrites, and registry allocation while preserving the public API.

## Idempotency

- **Stored in:** the `reservations` row itself — `UNIQUE (user_id, idem_key)`, with the show id and the sorted seat array. Keys are per user, so one user can never collide with another's key.
- **Exactly once:** a concurrent retry's `INSERT` blocks on the unique index until the first transaction finishes. If the first committed, the retry hits the conflict, reads the stored row and returns it (200, `idempotent_replay`). If the first rolled back, the retry proceeds as a fresh request.
- **Same key, different body:** compare the stored `show_id` and seat array with the request's (sorted) ⇒ any difference is 409 `idempotency_key_reused`. Comparing the stored fields directly (an earlier version compared a comma-joined string, where `["A","B"]` and `["A,B"]` collided) can't be ambiguous and works for every row ever written.
- **Declines don't burn the key:** a declined request rolls back its reservation row, so retrying the same key re-evaluates rather than replaying a 409. Nothing was charged, so this is safe.

## Holds & expiry

Model chosen: reserve confirms immediately (matches the specified 201 body) and `POST /reservations/{id}/cancel` releases. Cancel:

- runs only for the token's user (`WHERE id=? AND user_id=?`; someone else's id ⇒ 404, which doesn't reveal it exists),
- frees seats `WHERE reservation_id = <this one>`, so it can never resurrect a seat now confirmed to someone else,
- decrements the user counter, and is idempotent (cancelling twice returns the cancelled reservation).

The `held` status exists in the schema for the next step: a `held` + `held_until` hold that a payment `confirm` turns into `confirmed`. Expiry would be lazy (`WHERE status='available' OR (status='held' AND held_until < now())` in the seat gate) plus a sweeper to keep counts accurate.

## Consistency vs availability under a partition

Postgres is the only source of truth, and the system chooses **consistency**. If the app can't reach the DB, readiness goes to 503 (a load balancer or monitor watching it stops routing / alerts) and reserves fail rather than guess. Selling a seat twice is worse than refusing to sell for a minute. A cache or a second store in the decision path would trade that away.

## Observability — what pages me at 2am

- **Any sustained 5xx** (`http_server_requests_seconds_count{status=~"5.."}`) — by design every domain outcome is 4xx, so 5xx means a real fault.
- **Readiness failing** — DB unreachable; sales are stopped.
- **Invariant drift** — `sum(seats{show_id=X})` ≠ the show's total, or `reservations_confirmed_total` diverging from confirmed rows. Should be impossible; if it fires, stop sales.
- **Latency / saturation** — p99 reserve latency and `hikaricp_connections_pending` climbing: requests queuing for DB connections. Usually the precursor to timeouts.

Not paged: high `seat_taken` / `per_user_limit` rates. During an on-sale those are the expected outcome.

## Running it on a 0.1-CPU free tier

The live bursts taught three things the local runs could not:

- **The platform health check was the outage.** Render gives a check 5s, stops routing after 15s of failures and restarts after 60s. Under a burst on 0.1 CPU every request — the health check included — queues past 5s, so Render cut off and restarted a busy-but-correct instance (the 502s). With one instance there is nowhere else to route, so the platform check is deliberately off; a crashed process is still restarted, and `/readiness` stays for monitoring.
- **Keep-alive must outlive the proxy's.** Tomcat closed each kept-alive connection after 100 requests; a request the proxy sent down a closing connection came back as 520/502 from the edge. Unlimited requests per connection and a 120s idle timeout fixed it.
- **Memory is the real budget, not threads.** The JVM is capped at a 256MB heap to leave room for metaspace, stacks and socket buffers inside 512MB. Virtual threads were tried and reverted: each waiting connection then holds a Tomcat processor, and 3000 of them exhaust the heap; the 64 platform threads act as a natural bulkhead.

## AI usage

> **TODO (Yash): rewrite this section in your own words before submitting.** Facts to start from are below.

- Built with Claude Code (Claude Opus). I set the stack (Java/Spring), the 5× load target (100k requests), and reviewed each step; Claude proposed the schema, the three-gate transaction and lock ordering, wrote most of the code, the burst tool and these docs.
- Things caught during the build: the first package name `in.seats` is illegal in Java (`in` is a keyword); the burst's key-reuse check initially assumed the original request always arrives first, which is wrong under concurrency; a `seats_total` gauge silently vanished (Prometheus reserves `_total` for counters); the status page's first invariant check compared a total to itself; capping Tomcat connections caused refused sockets; virtual threads OOM'd the heap.
- Independent review (run separately and fed back in): found the comma-joined idempotency hash collision, open token minting, a null-seat 500, unchecked amount overflow, a missing cancel index, `total_seats` derived from the rows it should check, and a rebook test that could pass vacuously. All fixed and covered by the burst.
- What I verified myself: _(fill in — e.g. the break-it experiments: removing `AND status='available'`, removing `ORDER BY label`, replacing the guarded counter upsert with SELECT-then-UPDATE, and what the burst showed for each)_.

## What I'd do next

- Hold + confirm with expiry (above), and a payment step that confirms by reservation id.
- Load test the deployed instance at higher concurrency and size the DB pool against the provider's connection limit.
- Real auth (IdP-issued JWT with expiry) instead of the admin-issued HMAC token.
- Integration tests (Testcontainers Postgres) for the single-request rules, so the burst isn't the only automated check.
- Alerting rules checked into the repo alongside the metrics.
