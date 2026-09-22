# Roadmap

Each milestone ends with something you can demo. Rough pace: one weekend each.
Each phase has a plain-English write-up in [docs/](docs/).

**Now:** M2 done. **Next:** M3, proof and deadlines.

## ✅ M0: Plan
- [x] Pick the project and a working name
- [x] README, ROADMAP, for-you.md

## ✅ M1: Requests can be filed and verified · [docs/phase-1.md](docs/phase-1.md)
Goal: a user files a deletion request, confirms it by email, and you can see its status.
- [x] Maven multi-module parent with `orchestrator` (starter and demo modules get added in M4, when they have code)
- [x] Maven wrapper (`mvnw`) so no Maven install is needed
- [x] Docker Compose: Postgres + Mailpit
- [x] Flyway `V1`: `privacy_request` table
- [x] `RequestStatus` enum + `moveTo()` that rejects illegal moves, with a unit test
- [x] `POST /api/requests`, `POST /verify`, `POST /cancel`, `GET /api/requests/{id}` (admin only)
- [x] One-time code: stored as a keyed hash, expires in 24h, single-use, max 5 attempts
- [x] Row lock on verify/cancel so parallel guesses can't beat the attempt limit
- [x] Emails encrypted at rest (AES-256-GCM), with a unit test
- [x] `due_at` = received + 30 days
- [x] Full-flow test: file → wrong code → right code → `WAITING` → cancel; and 5 wrong codes → `REJECTED`
- [x] Run the full-flow test with Docker (Testcontainers + Postgres 17)
- [x] Run the app and try file → email code → verify by hand, with Mailpit

**Done when:** the integration test passes and the flow works with curl. ✅

## ✅ M2: Fan-out to connectors · [docs/phase-2.md](docs/phase-2.md)
Goal: a verified request reaches every connector, in stages, and survives failures.
- [x] Flyway `V2`: `connector`, `task` tables, `run_after` column
- [x] Connector registry endpoints (admin); secret generated, shown once, stored encrypted
- [x] Cooling-off timer moves `WAITING → RUNNING`
- [x] Same timer sweeps unverified requests past their code expiry to `REJECTED`
- [x] Worker claims tasks with `FOR UPDATE SKIP LOCKED` + a lease, and sends HMAC-signed `POST`s outside the transaction
- [x] Callback endpoint with signature + timestamp check (before parsing the body)
- [x] Exponential backoff; send again if no report within a timeout
- [x] Out of attempts → task `FAILED` → request `NEEDS_ATTENTION`; admin retry endpoint
- [x] Stage N+1 starts only when every stage N task is `DONE`
- [x] Admin view shows each connector's progress
- [x] Tests with fake connectors (the JDK's built-in `HttpServer`, so no WireMock needed): flaky connector, silent connector, out of attempts + admin retry, forged / stale / broken reports, duplicate report, expired codes
- [x] Checked the tests catch real bugs: breaking the stage rule on purpose makes them fail

**Done when:** a request passes through 3 fake connectors, one fails twice then succeeds, and the request ends `COMPLETED`. ✅ (`deletesStageByStageAndSurvivesAFlakyConnector`)

## M3: Proof and deadlines
- [ ] Flyway `V3`: `audit_event`; every state change writes a hash-chained event
- [ ] `GET /api/audit/verify` recomputes the chain and reports the first broken link
- [ ] Completion certificate (JSON): per-connector result, timestamps, retained reasons
- [ ] After completion: erase stored identifiers, keep salted `subject_hash`
- [ ] Deadline watcher: warn at 7 days left, alert when overdue
- [ ] Test: tamper with one audit row and check the verify endpoint catches it

**Done when:** a finished request has a certificate, no readable PII remains, and tampering is detected.

## M4: Connector starter + demo world
- [ ] `forgetme-spring-boot-starter`: auto-config, `@ErasureHandler`, signature check, idempotency, async report
- [ ] `users-service` (stage 3): deletes the account
- [ ] `orders-service` (stage 2): anonymizes orders, `RETAINED` for invoices
- [ ] `uploads-service` (stage 2): deletes files from MinIO
- [ ] `mailing-stub` (stage 1): unsubscribes, fails randomly
- [ ] Full Docker Compose: one command starts everything

**Done when:** `docker compose up`, file one request, and watch it clean all four services.

## M5: Ship it
- [ ] Minimal admin page (Thymeleaf): request list, per-connector status, retry button
- [ ] Rate limiting on public endpoints (today anyone can trigger confirmation emails to any address)
- [ ] Real secrets from environment variables; no dev defaults in production
- [ ] Production timings: cooling-off in days, retries spread over hours
- [ ] OpenAPI docs (springdoc)
- [ ] GitHub Actions: build + all tests on every push
- [ ] Deploy to a free or cheap host with a live demo URL
- [ ] k6 load test; put the real numbers in the README
- [ ] Architecture diagram, demo GIF, final resume bullet

**Done when:** a stranger can open the link, run the demo and understand the README without asking you anything.

## Later (only if there's time)
- Access requests: collect a user's data from every connector into a downloadable ZIP behind a signed, expiring link (adds a `type` column: `ERASE` / `ACCESS`)
- Send tasks in parallel (virtual threads) if slow connectors hold up the queue
- Edit or retire a connector without affecting requests already in flight
- Send emails through an outbox table, so a database rollback can never leave a user holding a code for a request that doesn't exist
- Crypto-shredding: per-user encryption keys, so deleting the key "deletes" data inside backups
- Replay deletions after a backup restore using `subject_hash`
- Kafka as an alternative to HTTP for connectors

## Not doing
- Legal certification or "compliance in a box" claims
- Scanning databases to discover personal data
- A separate single-page frontend

## Log
- 2026-09-22: Project chosen; README, ROADMAP and for-you.md written.
- 2026-09-22: M1 built on Spring Boot 4.1 (latest; the plan originally said 3). Dropped the separate `VERIFIED` status, since a verified request goes straight to `WAITING`. 8 tests; all pass against real Postgres 17. The full-flow test caught a bug: emails with surrounding spaces were rejected. Fixed by trimming before validation.
- 2026-09-22: Docker Desktop set up (Java 21 now the default `JAVA_HOME`). All 8 tests pass, 0 skipped, including the full flow on Testcontainers.
- 2026-09-22: M1 confirmed by hand (file → Mailpit code → verify). Started per-phase docs in `docs/`.
- 2026-09-22: M2 built. Dispatcher with lease-based `SKIP LOCKED` claiming, signed requests and reports, exponential backoff, stages, `NEEDS_ATTENTION` + admin retry. Fake connectors use the JDK's `HttpServer` instead of WireMock. Task statuses simplified to `PENDING / SENT / DONE / FAILED`, with `RETAINED` as a result rather than a status. 14 tests pass.
- 2026-09-22: Pushed to GitHub as a private repo: https://github.com/WIZ4RD-OM24/ForgetMe
