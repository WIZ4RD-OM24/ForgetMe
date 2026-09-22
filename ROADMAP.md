# Roadmap

Each milestone ends with something you can demo. Rough pace: one weekend each.
Each phase has a plain-English write-up in [docs/](docs/).

**Now:** M5 built; only the live deployment is left, and that needs a server you own. **Next:** deploy, then polish (see Later).

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

## ✅ M3: Proof and deadlines · [docs/phase-3.md](docs/phase-3.md)
- [x] Flyway `V3`: `audit_event`; every state change writes a hash-chained event (via `AuditLog.move`)
- [x] Chain uses HMAC with a server key, length-prefixed fields, microsecond timestamps; appends serialized with an advisory lock
- [x] Append-only trigger on `audit_event`
- [x] `GET /api/audit/verify` recomputes the chain and reports the first broken link
- [x] Completion certificate (JSON): per-connector result and note, timestamps, on-time flag, full history, anchoring `auditHash`
- [x] On *any* final state: erase the stored email, keep the keyed `subject_hash`
- [x] Deadline watcher: emails the admin once at 7 days left and once when overdue; both audited
- [x] Tests: edited event caught, deleted event caught, trigger refuses edits, certificate contents, email erased, one alert per level
- [x] Checked the tests catch real bugs: disabling the "was this edited?" check makes them fail

**Done when:** a finished request has a certificate, no readable PII remains, and tampering is detected. ✅

## ✅ M4: Connector starter + demo world · [docs/phase-4.md](docs/phase-4.md)
- [x] `forgetme-spring-boot-starter`: auto-config, an `ErasureHandler` bean (simpler than the planned `@ErasureHandler` annotation scanning), signature check, handler runs before answering, signed report on a virtual thread
- [x] At-least-once delivery documented: handlers must be idempotent; lost reports are covered by the orchestrator re-sending
- [x] Starter and orchestrator pinned to the same signature by a shared known-answer test
- [x] Connectors can be registered with their own secret (32+ chars), for scripted setup
- [x] Demo app, one program playing four roles via Spring profiles:
  - [x] `users` (stage 3): deletes the account
  - [x] `orders` (stage 2): removes the email from orders, `RETAINED` for invoices
  - [x] `uploads` (stage 2): deletes the customer's folder on disk (MinIO dropped: a folder shows the same thing with nothing extra to run)
  - [x] `mailing` (stage 1): unsubscribes; fails the first try of every request (deterministic instead of random, so every demo shows a retry)
- [x] Multi-stage `Dockerfile`; Compose `demo` profile starts everything plus a one-shot setup step that registers the four systems
- [x] Ran the full demo end to end: Alice deleted everywhere in 48 s, Bob untouched, certificate complete, audit intact

**Done when:** `docker compose up`, file one request, and watch it clean all four services. ✅

## 🟡 M5: Ship it · [docs/phase-5.md](docs/phase-5.md)
- [x] Admin page (Thymeleaf): request list with deadlines and audit status, per-connector progress, full history, retry button, certificate link
- [x] Rate limiting: per IP (`forgetme.filings-per-hour`) and 3 per email address per day
- [x] `forgetme.allowed-email-domains` so a public demo can only email `example.com`
- [x] `prod` profile with no default secrets: missing environment variables stop startup
- [x] Production timings: cooling-off 24 h, retries 10/20/40/80 min, report deadline 2 h
- [x] OpenAPI docs (springdoc) at `/swagger-ui.html`
- [x] GitHub Actions: `./mvnw -B verify` on every push (Testcontainers included), badge in the README
- [x] Deployment files: production compose, Caddy (automatic HTTPS), `.env.example`, server walkthrough
- [x] k6 load test + measured numbers in the README
- [x] Architecture diagram and CV bullet in the README
- [ ] Deploy to a free or cheap host with a live demo URL *(needs a server: Oracle Always Free or ~$5/month VPS)*
- [ ] Short demo recording (optional; the live URL may be enough)

**Done when:** a stranger can open the link, run the demo and understand the README without asking you anything.

## Later (only if there's time)
- Access requests: collect a user's data from every connector into a downloadable ZIP behind a signed, expiring link (adds a `type` column: `ERASE` / `ACCESS`)
- Send tasks in parallel (virtual threads) if slow connectors hold up the queue
- Edit or retire a connector without affecting requests already in flight
- Send emails through an outbox table, so a database rollback can never leave a user holding a code for a request that doesn't exist
- Crypto-shredding: per-user encryption keys, so deleting the key "deletes" data inside backups
- Replay deletions after a backup restore using `subject_hash`
- Signed PDF certificate for people who need a document to file
- Starter support for long-running deletions (answer 202 first, report when done)
- Extract the signing code into a small shared protocol module if a third component needs it
- Move rate-limit counters to Redis when more than one instance runs
- Actuator + Grafana dashboard if trends over time become useful
- Deadline alerts to Slack or a pager, not just email
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
- 2026-09-22: M3 built. HMAC hash-chained audit log with an append-only trigger and a verify endpoint; certificates anchored by the latest audit hash; email erased on every final state (not just `COMPLETED`); deadline alerts emailed to the admin. `code-secret` renamed to `hash-secret`, now used for all keyed fingerprints. 19 tests pass.
- 2026-09-22: M3 pushed to GitHub.
- 2026-09-22: M4 built. Connector starter, four-role demo app, Dockerfile, Compose `demo` profile with scripted registration, optional connector secrets. 25 tests pass (21 orchestrator + 4 starter). Full demo run: 48 s from confirmation to certificate.
- 2026-09-22: M4 pushed to GitHub.
- 2026-09-22: M5 built. Admin page, rate limits, allowed email domains, `prod` profile, springdoc, GitHub Actions, deployment files (Caddy + compose), k6 load test. 29 tests pass. Measured: 200 file-and-verify journeys in 3.0 s (p95 582 ms), then all 200 fanned out across 4 connectors in 30 s (~400/min, 800 jobs, 2,200 audit events). Live deployment still pending a server.
