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

The generator included the existing local HTTP/2 client-sharding change in Burst.java; this pre-existing user edit is not part of the verification commit. This test differs from the dashboard's 32-client loopback test and from the exact 20,000-buyer single-seat storm. It verifies 20,000 total requests at 1,000 concurrency, not 20,000 simultaneous arrivals.

## Conclusion

The exact 500-contender requirement is verified on the public URL. The complete 20,000-concurrent-buyer objective is **not achieved** on the current free deployment. Keeping hosting free is a user constraint. Lowering concurrency or recovering failed requests with retries does not turn the failed exact storm into a pass.
