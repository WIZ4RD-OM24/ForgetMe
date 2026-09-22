# Phase 2: Asking every system to delete

**In one sentence:** once a request is confirmed and the cooling-off wait is over, ForgetMe sends a "please delete this person" job to every connected system, in stages, keeps retrying the ones that fail, and finishes when every system has reported back.

**Status:** ✅ done. 14 automatic tests pass.

---

## The idea in everyday terms

Think of a **manager with a checklist**:
- Every few seconds the manager looks at the checklist (a **tick**).
- Each line is a **task**: "Orders team, delete Alice."
- The manager phones each team (**sends**). If nobody picks up, they call again later, waiting longer each time (**backoff**).
- Each team calls back when done (**report**), saying *deleted*, *anonymized* or *kept because of a law*.
- Teams are grouped into **stages**. Stage 2 only starts when everyone in stage 1 has reported back.
- If a team never answers after 5 calls, the manager flags it for a human (**NEEDS_ATTENTION**). The human can press **retry**.

## What happens, step by step

1. **The admin registers each system** (a **connector**): a name, a web address, and a stage number. ForgetMe gives back a **secret**, shown only once. The connector uses it to check that messages really come from ForgetMe, and to sign its own replies.
2. **A request is confirmed** (phase 1). It waits for the **cooling-off period**: 1 minute on your computer, days in real life.
3. **Every 5 seconds the Dispatcher wakes up** and does three chores:
   - **a.** Requests whose code expired (24 h) and were never confirmed → `REJECTED`
   - **b.** Requests whose cooling-off is over → `RUNNING`, and one task is created for each connector in the **first stage**
   - **c.** Every task that's due gets **sent**
4. **Sending a task:** ForgetMe posts the job to the connector, signed with that connector's secret.
   - The connector answers "got it" (any 2xx) → task `SENT`
   - Error, or no answer within 10 s → task back to `PENDING`, tried again after 10 s, then 20 s, 40 s, 80 s
   - 5 tries used up → task `FAILED` → request `NEEDS_ATTENTION`
5. **The connector deletes, then reports back** to `/api/callbacks/{taskId}`, signed: `DELETED`, `ANONYMIZED` or `RETAINED` (+ a note, like "invoices kept 8 years for tax law"). → task `DONE`
6. **No report within 5 minutes?** The job is sent again, with the **same task ID**, so the connector can tell it's a repeat.
7. **All tasks in the stage are `DONE`** → the next stage's tasks are created. **After the last stage** → request `COMPLETED`.
8. **The admin can press retry** (`POST /api/requests/{id}/retry`) on a `NEEDS_ATTENTION` request. Failed tasks get 5 fresh tries.

```
            stage 1                 stage 2 (at the same time)
WAITING ──► mailing ──report──► ┌─► orders  ──report──┐
 (cool-off)   ✗ ✗ ✓             └─► uploads ──report──┴──► COMPLETED ✅
             (retries)
```

## What a connector receives, and how it replies

ForgetMe sends:
```
POST http://orders-service/privacy/erase
X-ForgetMe-Timestamp: 1790090000
X-ForgetMe-Signature: sha256=3f9a…      ← secret stamp over "timestamp.body"

{"taskId":"…","requestId":"…","email":"alice@example.com","callbackUrl":"http://localhost:8080/api/callbacks/…"}
```

The connector answers `202` straight away, does the deletion in its own time, then reports back to `callbackUrl`, signed the same way:
```
{"result":"RETAINED","note":"invoices kept 8 years: tax law"}
```

## How it runs on your computer

The same three things as phase 1 (ForgetMe, PostgreSQL, Mailpit). The Dispatcher runs **inside** ForgetMe on a timer, so there's nothing new to start. You'll see its work in the ForgetMe window as log lines:
```
Request 5b1…: cooling-off over, starting
Request 5b1…: stage 1 started
Task 9c2…: mailing failed (attempt 1): I/O error … Connection refused
Task 9c2…: sent to mailing (attempt 2)
Task 9c2…: connector reported DELETED
Request 5b1…: completed
```

The timings are settings in `application.yml`:

| Setting | On your computer | Meaning |
|---|---|---|
| `cooling-off` | 1 minute | How long a confirmed request waits before deleting starts |
| `retry-backoff` | 10 s (then 20, 40, 80) | Wait before trying a failed connector again |
| `callback-timeout` | 5 minutes | Send again if a connector hasn't reported back by then |
| `tick` | 5 seconds | How often the Dispatcher wakes up |

### Try it by hand

There are no real connectors until phase 4, but you can watch the retries against an address where nothing is listening. In PowerShell, with ForgetMe running:

```powershell
$admin = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:admin")) }
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/connectors -Headers $admin -ContentType 'application/json' -Body '{"name":"mailing","endpointUrl":"http://localhost:9999/erase","stage":1}'
```
Then file and confirm a request as in phase 1. After a minute, the ForgetMe window shows the failed attempts. After about 2.5 minutes, check the request:
```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/requests/$($r.id)" -Headers $admin
```
It says `NEEDS_ATTENTION`, and the task shows 5 attempts and the last error.

## The code, file by file

All in `orchestrator/src/main/java/dev/forgetme/`. New in this phase:

| File | What it does | Why it's built this way |
|---|---|---|
| `Dispatcher` | **The manager.** `tick()` runs every 5 s: expires old codes, starts requests, sends due tasks. Also handles reports (`recordResult`), moving to the next stage (`advance`), and the admin retry. | Everything that moves a request forward lives in one file, so you can read the whole process top to bottom. |
| `Task` | One line of the checklist: which request, which connector, which stage, status, attempts, result, note. | Small named actions (`sent`, `retryAt`, `fail`, `done`) are the only way to change it. |
| `Connector` | One registered system: name, address, stage, secret (scrambled). | The secret is scrambled, not fingerprinted, because ForgetMe needs it back to sign messages. |
| `TaskRepository`, `ConnectorRepository` | Read and save tasks and connectors. | `findLockedById` locks a row, like in phase 1. |
| `ConnectorController` | `POST` / `GET /api/connectors` for the admin. | Hands out the secret once; it's never shown again. |
| `CallbackController` | Where connectors report back. | It checks the **signature first**, on the raw text, before reading anything. Untrusted input is never even parsed. |

Changed in this phase:

| File | What changed |
|---|---|
| `Crypto` | Added `sign` / `verify` (the secret stamp) and `newSecret` |
| `RequestController` | Added `POST /retry`; the admin view now lists each connector's progress |
| `PrivacyRequest`, `RequestService` | Confirming a request now sets when the cooling-off ends (`runAfter`) |
| `SecurityConfig` | Report addresses are open, because the signature is the check |
| `ForgetMeApplication` | `@EnableScheduling` switches on the timer |
| `V2__connectors_and_tasks.sql` | New `connector` and `task` tables, plus the `run_after` column |
| `application.yml` | The timing settings above |

## Why it's built this way

| Choice | Plain-English reason |
|---|---|
| **The checklist is a database table, not Kafka** | Kafka is a whole extra system to run. A table does the job, and survives crashes because it's saved on disk. |
| **"Claim, then send"** | The Dispatcher first marks up to 10 due tasks as "mine for the next minute" (a **lease**) in a quick database step, then makes the slow phone calls *after* letting go of the database. If the app crashes mid-call, the lease runs out and the task is picked up again. |
| **`SKIP LOCKED`** | If two copies of ForgetMe run at once, they skip each other's tasks instead of both sending the same one. |
| **The request is locked while deciding what's next** | Without it, two reports finishing the last two tasks at the *same moment* could each think the other is still busy, and the request would get stuck forever. That's a **race condition**. |
| **Always lock the request first, then the task** | If one part of the code locked A then B, and another locked B then A, they could wait for each other forever. That's a **deadlock**. One fixed order prevents it. |
| **Waiting longer after each failure** | A struggling system isn't flooded with calls. |
| **Resending uses the same task ID** | Connectors can spot a repeat and just say "already done". |
| **Signature + timestamp on every message** | Nobody can fake a report. A copied message is refused after 5 minutes, and a repeat inside those 5 minutes does nothing, because duplicate reports are ignored. |
| **Short, visible transactions (`TransactionTemplate`)** | Each database step is clearly marked in the code, and it avoids a common Spring trap where `@Transactional` silently does nothing when a class calls its own method. |

## The tools, and what each one does

New in this phase:

| Tool | Its job |
|---|---|
| **Spring `@Scheduled`** | The timer that runs `tick()` every 5 seconds |
| **Spring `JdbcClient`** | Runs the one hand-written SQL query that claims tasks (`UPDATE … FOR UPDATE SKIP LOCKED … RETURNING`) |
| **Spring `TransactionTemplate`** | Wraps each short database step in a transaction |
| **Spring `RestClient` + Java's `HttpClient`** | Sends jobs to connectors, with time limits (5 s to connect, 10 s to answer) |
| **Jackson** | Turns Java objects into JSON and back |
| **HMAC-SHA256** (built into Java) | The secret stamp on every message |
| **Java's built-in `HttpServer`** | Plays pretend connectors in the tests. It's built into Java, so we didn't need WireMock |

## The tests

`RequestFlowTest` runs each scenario for real: real web calls, a real database in Docker, and pretend connectors on your computer. The timer is switched off in tests, and each test "ticks" by hand, one step at a time.

| Test | The story it checks |
|---|---|
| `deletesStageByStageAndSurvivesAFlakyConnector` | 3 connectors in 2 stages. The stage 1 one fails twice, then works. Stage 2 waits, then both run together. One keeps invoices (`RETAINED`). The request ends `COMPLETED`. A repeated report changes nothing. |
| `outOfAttemptsNeedsAttentionUntilAnAdminRetries` | 5 failures → `NEEDS_ATTENTION` → nothing more happens on its own → admin retry (non-admins refused) → succeeds → `COMPLETED` |
| `sendsAgainWhenAConnectorNeverReportsBack` | A connector says "got it" but never reports → it's sent again with the same task ID |
| `rejectsForgedStaleAndBrokenReports` | Wrong secret → 401; 10-minute-old message → 401; unknown task → 404; nonsense result → 400; non-admin can't register connectors |
| `unverifiedRequestsExpire` | A request never confirmed within 24 h → `REJECTED` |
| `CryptoTest` → signatures | A changed message, a wrong secret or an old timestamp all fail the check |

**Did the tests actually catch bugs?** To check, we broke the "wait for stage 1" rule on purpose in a copy of the code. The stage test failed, as it should.

## Kept simple on purpose

| Shortcut | Fine for now because | Change it when |
|---|---|---|
| Tasks are sent one after another | Connectors answer in milliseconds | Slow connectors hold up the queue (send in parallel with virtual threads) |
| Every request goes to every connector | That's the point of a deletion request | Some connectors only hold some kinds of data |
| No way to edit or remove a connector | Demo setup is fixed | Real systems come and go |
| Retries span ~2.5 minutes | Easy to watch on your computer | Real use: spread retries over hours (M5) |

## Things you can say in an interview

- "The job queue is a Postgres table. Workers claim a batch with `FOR UPDATE SKIP LOCKED` and a lease, commit, and only then make the HTTP calls, so no lock is held during network I/O, and a crash just lets the lease expire."
- "I lock the request row before deciding the next stage. Otherwise two callbacks completing the last two tasks concurrently would each see the other as unfinished, and the request would stall."
- "Locks are always taken in the same order, request then task, to rule out deadlocks."
- "Messages are HMAC-signed over timestamp plus body. I verify before parsing, reject anything older than 5 minutes, and duplicates are harmless because task handling is idempotent."
