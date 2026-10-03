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

The successful reserve path now sends two client statements (excluding transaction begin/commit): the batched preflight read and one call to `public.reserve_booking`. The function performs the reservation insert, guarded user-count upsert, sorted seat locks and conditional update inside PostgreSQL. This removes three client/server round trips without removing any gate. Java retains the surrounding transaction and counts confirmations only after COMMIT. The function's exception block rolls back partial writes for its three private domain SQLSTATEs; unexpected database/trigger failures propagate normally. The function is installed from `booking.sql` after schema initialization and runs with the caller's privileges. An existing-key race still waits on the unique index and reads the committed reservation through the VOLATILE function's fresh statement snapshot. Already-taken-seat declines and preflight replays remain batched autocommit reads.

In a controlled 20,000-request mixed workload at 128 concurrency, with 8 ms injected per database protocol ReadyForQuery message, three paired before/after runs passed every check. Median reservation time was 21.0 s before versus 11.2 s after (46.7% lower); median process CPU across each entire test, including setup and cancellation, was 13.234 versus 9.800 s (25.9% lower). This used unrestricted local CPU and is not a simulation or guarantee of Render Free performance.

Concurrent preflight reads are coalesced by two workers into batches of at most 64, with at most 2 ms spent gathering each batch. A bounded 128-entry queue applies backpressure. One `jsonb_to_recordset` statement joins each request's show, requested seats and idempotency key in one PostgreSQL snapshot; an ordinal maps the result back to that buyer. There is no inventory cache. Booking and cancellation retain the original transactions and lock ordering. The caller waits for its final response; no 202 acknowledgement or retry is substituted. `reservation_preflight_batch_size` exposes the number of reads served per successful query.

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
- **Invariant drift** — `sum(seats{show_id=X})` ≠ the show's total, or a seat gauge disagreeing with a consistent API inventory snapshot. Confirmation counter deltas must match new 201 responses; cumulative counters cannot be compared directly with net inventory after cancellations or process restarts. Should be impossible; if it fires, stop sales.
- **Latency / saturation** — p99 reserve latency and `hikaricp_connections_pending` climbing: requests queuing for DB connections. Usually the precursor to timeouts.

Not paged: high `seat_taken` / `per_user_limit` rates. During an on-sale those are the expected outcome.

## Deployment limits and acceptance status

The primary deployment is Northflank Sandbox: the application has 0.2 shared vCPU and 512 MB RAM, with a private PostgreSQL 18 add-on in the same project. The latest public test dispatched 20,000 reservations at 20,000 concurrency in 0.114 seconds. It completed reservations in 79.1 seconds but received 5,816 HTTP 503 responses. Inventory observation had three successful polls and two failed reads. **The full public correctness bar remains unmet.** Client dispatch timing does not establish server arrival timing.

Observed hot-seat winners, ownership, per-user limits, idempotency, final reconciliation, metrics comparisons and cancellation/rebooking checks passed. This does not turn the overall run into a pass: some losing buyers received 503 rather than 409, and observation gaps prevent a complete during-burst claim.

An earlier Northflank run against remote Neon took 155.8 seconds and received 4,805 HTTP 503 responses. Moving PostgreSQL into the hosting project reduced completion time in the next reported run, but did not eliminate failures. This is one before/after observation, not a controlled capacity benchmark. Earlier resource samples showed CPU throttling and an HTTP worker backlog with memory headroom. These are signs of saturation; they do not prove CPU is the sole cause of every public 503 or establish the proxy's precise rejection policy.

Locally, the 20,000-concurrent HTTP/2 workload passed with a 256 MB heap; Docker runs with a 512 MB application limit also passed at one and two CPUs. These local CPU allocations differ from the public free tier. The PostgreSQL regression suite previously passed all 22 tests, including rollback and injected-failure recovery. A multiplexed HTTP/2 pass does not establish an independent-connection HTTP/1.1 pass.

The transport uses Undertow with 64 workers, two I/O threads, small direct buffers and request-body buffering before worker dispatch. The application still returns final 201/409 decisions; a 202 queue acknowledgement would change the required API rather than satisfy it. Detailed results and public captured logs: [LIVE-VERIFICATION.md](LIVE-VERIFICATION.md), [evidence](evidence/README.md).

## AI usage

AI assistance was substantial: implementation, review, regression tests, load tools, documentation and deployment diagnosis. The earlier repository write-up attributes the initial build to Claude Code; subsequent fixes and submission preparation used Codex. This disclosure does not claim that the author manually wrote every line.

Human direction included the Java stack, the assignment's atomic correctness requirements, zero-cost hosting, authorization of deployment changes, and running and sharing the public CLI test results. AI proposed implementation details including database booking gates, deterministic lock ordering, batched preflight reads, stable metric registration, transport tuning and co-located PostgreSQL. Tests and operational evidence informed those changes; the remaining public failures are disclosed rather than treated as successful domain declines.

Reviews identified ambiguous idempotency comparisons, token-minting access control, invalid-input handling, amount overflow, reconciliation checks and cancellation/rebooking test coverage. The author should be able to explain and defend the implementation and the limits of its evidence; no unperformed manual fault experiment is claimed here.

## What I'd do next

- Diagnose public 503s with correlated ingress and application request IDs, then validate the exact external workload again without retries that hide failures.
- Automate the existing real-PostgreSQL regression and restart tests in CI.
- Add alerting for external failure rate, inventory drift, readiness and saturation.
- If the product requires payment holds, add explicit hold/confirm/expiry transitions and an expiring identity-provider token. These are future features, not current behavior.
