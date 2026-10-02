# Live acceptance verification — 2026-10-02

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
