# Live acceptance verification — current status, 2026-10-03

Primary URL: https://p01--seat-reservation--tc65zqzsjs45.code.run/

Northflank Sandbox application: 0.2 shared vCPU / 512 MB; private PostgreSQL 18 in the same project. Latest published code includes the transport optimization; documentation/UI changes do not convert a failing external test into a pass.

## Latest user-run public workload

20,000 requests, 20,000 concurrency, 5,000 seats; show `e9c69e3f-629d-4d38-b5db-4d191a1af9da`. Client dispatch: 0.114 s; reserve completion: 79.1 s. Client dispatch is not verified arrival timing.

| Response | Count |
|---|---:|
| 200 replay | 309 |
| 201 confirmed reservation | 3,197 |
| 409 key reused | 46 |
| 409 per-user limit | 175 |
| 409 seat taken | 10,457 |
| 503 non-domain response | 5,816 |
| Network errors | 0 |

**Overall: FAIL (three checks).** Zero 5xx and only-domain responses fail. During-burst inventory observation also fails: three successful polls, two failed reads, zero observed violations. Missing observations are not evidence of invariant preservation throughout the burst.

Final inventory: available=1,319, held=0, confirmed=3,681, total=5,000. Observed single hot-seat winners, no double sale, per-user limit, idempotency, final reconciliation and metric comparisons passed. Phase 2 passed owner-only cancellation, spoofed identity, double cancellation and exactly one eventual rebooker per freed seat. A reservation can contain multiple seats, hence 3,197 new reservations versus 3,681 confirmed seats.

The preceding Northflank/remote-Neon run took 155.8 s and returned 4,805 HTTP 503s. The next co-located database run was faster but still failed. The precise cause of the latest public 503s has not been established for each request. Earlier worker queue and CPU-throttling samples indicate saturation; provider-level and application-level failures require separate evidence.

Raw reported test output, local passing output and structured log samples: [evidence/README.md](evidence/README.md). No failing reservation response is retried or excluded from the acceptance result.

## Historical Render evidence — 2026-10-02


Target: https://seat-reservation-62kf.onrender.com/  
Application commit under test: `bd1924ea069d6cb28a9b9bb5643496c0b773c46a`  
Render settings inspected directly: Singapore; Free (0.1 CPU, 512 MB); HTTP health-check path empty.

## Exact single-seat storms

All users were distinct, authenticated buyers with distinct keys. Each test created a fresh show containing only A12. Connections and credentials were prepared before the measured storm. No reservation request was retried.

| Buyers | Client submission span | Completion time | 201 | 409 seat_taken | 429 | 502 | Transport failures | Result |
|---|---|---|---|---|---|---|---|---|
| 500 | 0.011 s | 6.320 s | 1 | 499 | 0 | 0 | 0 | Pass |
| 20,000 | 0.082 s | 78.495 s | 1 | 6,676 | 12,103 | 1,154 | 66 | Fail |

Both final API states had one confirmed seat, zero available/held seats and total_seats=1. The 20,000-buyer test proves that submitting 20,000 requests together does **not** currently produce a clean decline for every loser on this deployment. Client submission timing is not proof of arrival timing at the service.

For the 20,000-buyer run, confirmed metrics increased by exactly 1; seat_taken metrics increased by 6,742. This is 6,676 received 409s plus the 66 HTTP/2 GOAWAY failures whose responses the clients did not receive. The application exposed no reserve 429/502 response series. Uptime increased rather than resetting, so this run did not restart the process. This locates the extra failures outside the reservation controller; the precise edge throttling policy has not been confirmed.

An external watcher obtained 22 consistent inventory snapshots over setup and execution; 19 reads failed. No observed snapshot broke reconciliation, but failed reads leave gaps in observation and prevent claiming continuous availability.

Reproduce with JDK 17 and the deployed ADMIN_KEY:

```sh
java HotSeat.java https://seat-reservation-62kf.onrender.com 500
java HotSeat.java https://seat-reservation-62kf.onrender.com 20000
```

The harness reads ADMIN_KEY from the environment or a git-ignored `.env`. Never commit that file.

## Mixed workload

The external working-tree `burst.sh` / `Burst.java` run used 20,000 requests, 1,000 concurrent workers and 5,000 seats. It passed all checks in 245.7 seconds (81 requests/second), with zero 5xx and zero network errors. Outcomes were 3,602 confirmations, 15,523 seat_taken, 264 per_user_limit, 530 idempotent replays, and 81 key-reuse declines.

Each of five hot seats had exactly one 201. Maximum held by any user was 4, including the 10-parallel-request limit attack. The 4,110 confirmed seats matched seats in successful responses; final counts were available=890, held=0, confirmed=4,110, total=5,000. All 20 successful inventory polls reconciled. Confirmation/decline counter deltas and seat gauges matched the API.

Phase 2 tested 200 victims: attacker cancellation returned 404; both owner cancellations returned 200 cancelled; spoofed bodies did not change token-derived identity; freed seats had at most one new winner. 183 victims were rebooked during the cancellation race and the remaining 17 were guaranteed rebooked afterward. Final confirmed inventory returned to 4,110, with zero phase-2 5xx.

The generator included the HTTP/2 client-sharding change in Burst.java; that transport fix is now committed as `d68ed24` so a clean checkout includes it. This test differs from the dashboard's 32-client loopback test and from the exact 20,000-buyer single-seat storm. It verifies 20,000 total requests at 1,000 concurrency, not 20,000 simultaneous arrivals.

## Conclusion

The exact 500-contender requirement is verified on the public URL. The complete 20,000-concurrent-buyer objective is **not achieved** on the current free deployment. Keeping hosting free is a user constraint. Lowering concurrency or recovering failed requests with retries does not turn the failed exact storm into a pass.

## Follow-up: autocommit decline patch (`3eee3ff`)

Already-unavailable seats now return 409 from one autocommit snapshot query. Potential winners still use the atomic reservation transaction; database state remains authoritative. Eight automated tests passed, including concurrent idempotency and cancellation replay against PostgreSQL.

With an injected 8 ms database network delay, three comparable 2,000-request runs had median duration 8.750 s before and 5.367 s after (38.7% lower). All correctness checks passed in all six runs. This controlled result is not a prediction of Render throughput.

| Exact buyers | Submission span | Completion | 201 | 409 | 429 | 502 | 520 | Transport failures | Result |
|---|---|---|---|---|---|---|---|---|---|
| 500 | 0.013 s | 9.392 s | 1 | 499 | 0 | 0 | 0 | 0 | Pass |
| 20,000 | 0.105 s | 94.525 s | 1 | 8,733 | 10,685 | 560 | 1 | 20 | Fail |

Both final states contained exactly one confirmed seat. The sampled 429 response contained `Too Many Requests`, `Retry-After: 1`, and Cloudflare headers. Sampled 502/520 responses also had Cloudflare headers. These identify the response path, not the exact throttling policy or its configuration. More requests received clean declines than in the earlier run, but completion was slower; this single comparison does not establish a general live speed improvement. The strict 20,000-at-once objective remains unmet.

The follow-up mixed run used 20,000 requests at 1,000 concurrency and completed in 210.2 s (95 requests/s). Outcomes: 3,596 confirmations, 15,521 seat-taken responses, 267 user-limit declines, 534 replays, 81 key-reuse declines, and one 520 response (`error code: 520`). No network errors occurred. All seat uniqueness, user limits, idempotency, identity, cancellation/rebooking, and 20 inventory reconciliation polls passed. Final inventory was 892 available, 0 held, 4,108 confirmed of 5,000 seats. The run failed two checks: zero 5xx, and seat-taken metric delta 15,522 versus 15,521 received responses. A decline response lost at the edge is consistent with this discrepancy; that causal explanation is an inference. Faster completion than the previous mixed run does not establish reliable zero-error performance.

## Control experiment: the same storm against a no-op endpoint

Question: is the 20,000-at-once failure caused by reservation cost, or by the platform in front of the application? Control: fire the same storm at `/actuator/health/liveness`, which does no database work and reads no request body.

Method: about 250 HTTP/2 connections (at most 80 streams each, under the edge's per-connection stream cap) were opened and warmed first; then N GETs were issued at once, with no retries. Same public URL, same day.

| Endpoint | Requests at once | Completion | 200 | 429 | 502 | 520 | Served by app |
|---|---|---|---|---|---|---|---|
| `/actuator/health/liveness` | 2,000 | 22.6 s | 2,000 | 0 | 0 | 0 | 100% |
| `/actuator/health/liveness` | 20,000 | 57.4 s | 11,959 | 7,132 | 908 | 1 | 59.8% (~208/s) |
| `/shows/{id}/reserve`, one hot seat (above, after `3eee3ff`) | 20,000 | 94.5 s | 1 × 201 + 8,733 × 409 | 10,685 | 560 | 1 | 43.7% (~92/s) |

Reading:

- The no-op endpoint fails the 20,000-at-once storm with the same failure classes (Cloudflare-fronted 429/502/520) as the reservation storm. 40.2% of liveness requests never received a 200, although each one costs the application almost nothing.
- Liveness completed about 2.3× more requests per second than reserve. So reservation cost does matter for throughput, but even at near-zero cost the edge rejects a large share of a 20,000-request instant storm on this instance. Making reserve as cheap as liveness would, at best, bring its failure rate down to the liveness level, not to zero.
- The application emitted no 5xx series during these runs; the rejections are produced before requests reach it.

Conclusion: on the free deployment (0.1 CPU behind Render's Cloudflare edge), no application or query change can produce 20,000 clean responses to 20,000 simultaneous requests. Closing that gap requires capacity outside the application (a larger instance or a host without a rate-limiting edge), which was kept out of scope by the free-hosting constraint.


## Bounded read batching follow-up (`e1f2243`)

Concurrent reservation preflights share PostgreSQL statement snapshots in batches of up to 64, collected for at most 2 ms by two workers. Inventory is not cached; final 201/200/409 semantics and booking/cancellation transactions remain unchanged. Nine automated tests passed, including 50 mixed requests across independent shows and invalid seat/show inputs.

With the same injected 8 ms database delay and 2,000-request dashboard preset, before timings were 5.344, 5.380 and 5.332 s; after timings were 3.713, 4.483 and 3.616 s. Median completion dropped 30.5% (5.344 to 3.713 s); all 11 checks passed in all six runs. These controlled results do not establish a live speed gain.

The exact live 500-buyer test passed: one 201, 499 seat-taken 409s, zero other outcomes, 0.010 s submission span, and 10.697 s completion. Final inventory contained exactly one confirmed seat. This live completion was slower than the prior run.

The exact 20,000-buyer follow-up failed: 0.102 s submission span, 86.086 s completion, 1 confirmed response, 9,057 seat-taken declines, 9,113 HTTP 429s, and 1,829 HTTP 502s. No transport errors were reported. Sampled failures had Cloudflare headers; 429 included `Retry-After: 1`. Final inventory contained exactly one confirmed seat. The application exposed 9,558 reserve responses across the 500- and 20,000-buyer tests, all 201/409, and its uptime continued. The new batch summary counted 807 queries serving 9,558 preflights. Subtracting the first 500-buyer test (37 queries) gives 770 queries for 9,058 preflights in the large storm: 91.5% fewer preflight database round trips than one query per request. Despite this reduction, the public acceptance criteria remain unmet.

The batched mixed run (20,000 requests, 1,000 workers, 5,000 seats) passed every check in 182.0 s (110 requests/s), with zero 5xx and zero network errors. Outcomes were 3,601 confirmations, 15,525 seat-taken declines, 263 user-limit declines, 530 replays, and 81 key-reuse declines. All five hot seats had exactly one winner; observed inventory reconciled in 20 polls; all metric deltas matched received responses. Cancellation/identity/rebooking phase passed: 173 victims were rebooked in the race and 27 afterward; final inventory was 890 available, 0 held, 4,110 confirmed of 5,000. This run used the bounded-worker generator before the asynchronous simultaneous mode and stronger failed-poll checks were added. It is not evidence for 20,000 simultaneous reservations.

The new asynchronous mixed test dispatched all 20,000 reservations in 0.121 s at 20,000 concurrency against a fresh 5,000-seat show. It completed the reservation phase in 111.9 s, with 2,717 received 201s, 168 replays, 6,401 seat-taken declines, 70 user-limit declines, 28 key-reuse declines, 9,487 rate-limit responses, 1,128 HTTP 502s, one HTTP 520, and zero transport errors. Five checks failed: zero 5xx, only domain outcomes, confirmed seats versus successful client responses (3,169 versus 3,168), inventory observation coverage (2 successful polls and 100 failed reads), and confirmation counter delta versus received 201s (2,718 versus 2,717). Every hot seat had exactly one received 201; observed uniqueness, limits, key consistency, final reconciliation, and cancellation/token-identity/rebooking checks passed. Final counts were 1,831 available, 0 held, 3,169 confirmed of 5,000. A committed confirmation whose response was lost is consistent with the one-reservation mismatch, but that explanation is an inference. This is the closest tested match to the assignment's full simultaneous mixed workload, and it failed.

Reproduce that simultaneous mixed test with `./burst.sh https://seat-reservation-62kf.onrender.com 20000 20000 5000`. The asynchronous mode keeps at most 80 reserve streams per HTTP/2 client, warms connections before measurement, does not retry reservation requests, and separately reports dispatch timing. Failed inventory reads are counted, and missing count fields are errors rather than implicit zeros. Client dispatch within one second remains distinct from verified arrival timing at the service.

## Local adversarial verification — 2026-10-02

This is local correctness/recovery evidence, separate from the failed public simultaneous burst above.

- The final suite passed 18 tests with PostgreSQL and fault injection enabled, zero failed/skipped tests. An expanded model run also passed 25 seeded workloads of 400 operations each (10,000 operations). Model workloads are sequential state-machine comparisons, not a concurrency load benchmark. Ten separate cancellation/rebooking races combined owner double-cancels, attacker cancels and overlapping seat sets, with database audits running concurrently.
- Independent one-statement audits checked duplicate ownership, missing/mismatched reservation-seat links, available seats retaining owners, stored user counters versus confirmed reservation cardinalities, user limits, declared inventory totals and leaked pending reservations. Deliberate corruption of ownership, user counters, reservation arrays and totals was detected by the audit.
- A trigger failed a seat write after reservation/counter insertion: the request returned an expected 500; every write rolled back, no confirmation metric was counted, and retrying the same key succeeded.
- Terminating a database backend during a paused seat write also rolled back every row; the connection pool recovered and the same key could succeed afterward.
- Closing a client connection during an in-progress write did not prevent the eventual commit. A retry returned the same reservation with HTTP 200, with one stored reservation and one counted seat.
- `scripts/verify_restart.py` passed four checks: a fresh process becomes ready; a SIGKILL before commit leaks no booking/counter/ownership; the restarted app accepts the rolled-back key; and a committed booking survives a second SIGKILL, with a same-key retry replaying exactly one durable reservation. This verifies a local cold start and crashes, not Render's cold start or edge behavior.

Three reproduced defects were fixed and regression-tested: unhandled failures were logged as 200 before the container generated their 500 response; a persisted key with changed seats could return 400 during seat/amount validation rather than 409; and trailing empty token segments were ignored by Java's default string splitting. The token issue accepted an alternate spelling of the same valid token, not a forged identity. Committed responses retain their actual logged status even when subsequent I/O fails.

Fault-injection 500s are intentional tests of rollback, not evidence of passing the assignment's zero-5xx public burst. These checks strengthen confidence in specific properties but are not a proof of absence of all possible bugs. The full public acceptance bar remains unmet.

## Server-side booking gates follow-up

The three mutation gates now run in `public.reserve_booking`, invoked as one prepared statement after the batched preflight. The Java transaction still owns commit/rollback, and confirmation metrics remain deferred until commit. Sorted locks, conditional seat updates and the per-user unique key are preserved. Private domain exceptions roll back the function's partial changes; other failures propagate.

Validation: all 19 final tests passed with local PostgreSQL and fault injection enabled. The expanded 25-seed/400-step model run passed 10,000 operations. The new direct-function test verified that partial seat updates, unknown seats and limit declines leave no extra reservation/counter/ownership even with an autocommit caller. All four process-crash/cold-start recovery checks passed.

| Paired 20,000-request run, 128 concurrency | Before reservation seconds | After reservation seconds | Before full-test CPU seconds | After full-test CPU seconds |
|---|---|---|---|---|
| 1 | 21.0 | 11.0 | 15.490 | 10.113 |
| 2 | 20.7 | 11.2 | 12.283 | 9.214 |
| 3 | 21.0 | 11.7 | 13.234 | 9.800 |

All six runs passed the full generator checks, including cancellation/identity/rebooking. Each used a fresh 5,000-seat show and the same injected 8 ms delay per PostgreSQL ReadyForQuery message. Median reservation time improved 46.7%; median full-test process CPU improved 25.9%. The measurement used unrestricted local CPU, not Render's 0.1 CPU quota.

## Public verification of server-side booking gates — 2026-10-02

Render successfully deployed commit `c3020c5b41ca9edd0373cb812b67a7797e17aa91`; the public health endpoint reported UP, including PostgreSQL and readiness. This is deployment readiness evidence, not a separate idle cold-start experiment.

The distinct-buyer single-seat test dispatched 500 requests in 0.008 seconds and completed in 7.542 seconds: exactly one 201 and 499 seat-taken 409s, with exactly one confirmed seat afterward. It passed.

The full mixed test used `./burst.sh https://seat-reservation-62kf.onrender.com 20000 20000 5000` on fresh show `1d6beefa-652f-46a1-8c39-35a516db8ddc`. Client dispatch took 0.103 seconds; the reservation phase completed in 87.5 seconds. Outcomes: 2,493 confirmations, 126 replays, 5,324 seat-taken declines, 57 limit declines, 19 key-reuse declines, 10,669 HTTP 429s, 1,312 HTTP 502s, and zero transport failures. All outcomes sum to 20,000.

Three checks failed: zero 5xx, only successful/replayed reservations or domain declines, and inventory observation coverage (two successful polls, 104 failed reads, zero observed invariant violations). Each hot seat had one received 201, but the rejected responses mean that not every losing contender received the required 409. Observed uniqueness, per-user limits, idempotency and final reconciliation passed. All reservation metric deltas matched received domain responses. Final counts exactly matched client-confirmed seats: 2,081 available, zero held, 2,919 confirmed out of 5,000.

The subsequent 1,200-request cancellation/attack/rebooking phase passed every check with zero 5xx: all 200 attacker cancellations returned 404; 400 owner cancellations returned 200; 179 seats were rebooked during the race and the remaining 21 afterward. Token-derived identity, unique rebooking and final reconciliation held.

The full simultaneous public acceptance bar remains unmet. Faster completion than earlier individual live runs does not establish a controlled production speed improvement, especially because the proportion of rejected requests changed. The controlled local paired benchmark above establishes the optimization's measured benefit under its stated conditions. Raw logs for this run are `/tmp/seatlab-function-live-500.log` and `/tmp/seatlab-function-live-simultaneous.log` on the development machine.

## Local transport optimization — 2026-10-02 (not deployed)

The previous local HTTP/1.1 full-concurrency run failed with 7,558 transport errors. Enabling HTTP/2 on Tomcat exposed a heap exhaustion failure. Reducing Tomcat's initial stream window to 1 KB was insufficient: an OOM heap dump contained approximately 102 MB in 13,005 byte arrays of length 8,192; 25 MB in 25,462 arrays of length 1,024; and large ByteChunk/CharChunk/MessageBytes populations. This demonstrates significant transport memory, not a measured per-booking transaction allocation. The experimental Tomcat customizer was removed.

The current local implementation uses Undertow in place of embedded Tomcat while retaining Spring MVC, auth, logging, metrics, PostgreSQL booking gates and transaction semantics. It uses HTTP/2, 64 worker threads, two I/O threads, 1 KB direct buffers, a 120s no-request timeout and a 64 MB JVM direct-memory cap in addition to the 256 MB heap. Burst.java now prefers HTTP/2 for plain HTTP as well as HTTPS, reports the warm-up protocol, and summarizes root causes of transport failures. `BURST_HTTP_VERSION=1` preserves an explicit HTTP/1.1 test. No reservation retries were added.

Three local HTTP/2 mixed runs at 20,000 requests/20,000 concurrency/5,000 seats passed every generator check, including phase 2, with zero HTTP 5xx and zero transport failures. Dispatch spans were 0.078, 0.228 and 0.097 seconds; reservation completion times were 4.3, 4.8 and 4.7 seconds. The first used Undertow defaults; the final two used the explicit worker/buffer settings and direct-memory cap. Sampled memory in those two runs peaked at 400/405 MB RSS and 225/222 MB heap respectively. These are sampled peaks, not guaranteed maxima. Local CPU was unrestricted and PostgreSQL was on loopback. Client dispatch does not verify server arrival timing.

The separate Undertow HTTP/1.1 run still failed: 9,010 transport errors (8,665 connection-reset-by-peer, 102 connection-reset and 243 broken-pipe), zero HTTP 5xx, no observed correctness violations and no server OOM. Sampled memory peaked near 348 MB RSS and 157 MB heap. The configured Mac ephemeral port range has 16,384 ports, but resets alone do not prove port exhaustion as the cause. This result remains an unresolved independent-connection overload failure.

All 19 regression tests passed with fault injection enabled, with zero failures/errors/skips. All four process crash/restart checks passed with Undertow. The reproducible local command is `python3 scripts/verify_local_burst.py`; it builds, creates its own temporary loopback database and JVM, runs the simultaneous test, saves artifacts and cleans up. Latest artifacts are in `/var/folders/83/k2m6l69d6ms_vzt2h9g9rt_00000gn/T/seatlab-local-burst-gx2m7bpn/`. The full public bar remains unmet; these changes have not been deployed or live-tested.
