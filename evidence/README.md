# Submission evidence

These are captured observations, not a live log stream or proof of future behavior.

- [Public Northflank tests](northflank-public-bursts-2026-10-03.txt): user-provided CLI results before and after switching from remote Neon to private Northflank PostgreSQL. The latest run fails three checks.
- [Local simultaneous test](local-simultaneous-2026-10-03.txt): 20,000 requests at 20,000 concurrency, unrestricted local CPU, 256 MB heap; all checks pass. The temporary artifact path was omitted.
- [Structured request logs](northflank-request-logs-2026-10-03.jsonl): owner-authenticated capture from the earlier Northflank/Neon burst (20:09–20:12 UTC, October 2), including request IDs, outcomes and durations. This is an excerpt, not every request.
- [Resource samples](northflank-resource-samples-2026-10-03.jsonl): read-only monitoring captured during the earlier Northflank/Neon test, not the later private-Postgres run. UTC timestamps on October 2 correspond to October 3 in India. Do not combine samples from different runs as one measurement.

Public current endpoints:

- [Readiness](https://p01--seat-reservation--tc65zqzsjs45.code.run/actuator/health/readiness)
- [Prometheus](https://p01--seat-reservation--tc65zqzsjs45.code.run/actuator/prometheus)
- [Demonstration dashboard](https://p01--seat-reservation--tc65zqzsjs45.code.run/)

Live runtime logs require the owner's Northflank login. To display and capture logs during a manually started burst:

```sh
npx --yes @northflank/cli get service logs --project seat --service seat-reservation --types runtime --tail
```

Record that terminal with the system screen recorder while running the external burst in another terminal. Include request lines with request IDs and `resource_sample` lines; hide credentials and account details. No screen recording is claimed to exist in this package. Public excerpts provide captured log access; if reviewers require a recording or live provider access, supply the recording separately.

A read-only metrics recorder is also available:

```sh
python3 scripts/record_metrics.py https://p01--seat-reservation--tc65zqzsjs45.code.run --seconds 300 --output /tmp/seatlab-live-metrics.jsonl
```

It does not initiate a burst. Failed scrapes and process restarts must remain visible in the evidence.
