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

