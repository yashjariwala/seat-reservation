# Submission note / email draft

Hello,

The seat-reservation implementation and supporting materials are available at https://github.com/yashjariwala/seat-reservation. The live free-tier deployment is https://p01--seat-reservation--tc65zqzsjs45.code.run/; README.md contains setup, API, metrics, captured logs and reproducible external-test commands. WRITEUP.md explains atomicity, idempotency, lock ordering, consistency and AI assistance.

I want to disclose a remaining acceptance failure. The local 20,000-concurrent HTTP/2 test passes, but the latest public run received 5,816 HTTP 503 responses, and two inventory reads failed. Observed uniqueness, per-user limits, idempotency, ownership and final reconciliation passed, but this does not meet the full zero-5xx/public observation requirement.

The free application allocation is 0.2 shared vCPU and 512 MB RAM. Earlier measurements showed worker backlogs and CPU throttling; moving PostgreSQL into the same hosting project improved one reported run's completion time from 155.8 to 79.1 seconds, without eliminating public failures. I cannot attribute every 503 solely to CPU or claim the hosting platform is the only cause.

Could you clarify whether the full 20,000-simultaneous test must pass against the provided free-tier public instance, or whether separate local correctness evidence and documented public capacity limits are acceptable? I have included the failing external evidence rather than marked the requirement complete.

Thank you,
Yash

---

This is a draft, not a sent email. Supply the deployed admin key privately to reviewers if needed; never commit it. A live-log screen recording is not included and should be provided separately if required.
