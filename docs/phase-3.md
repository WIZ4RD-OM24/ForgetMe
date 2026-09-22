# Phase 3: Proof and deadlines

**In one sentence:** ForgetMe now keeps a diary of every step that nobody can secretly edit, hands out a certificate when a request is finished, forgets the person's email the moment a request is over, and emails the admin when a legal deadline gets close.

**Status:** ✅ done.

---

## The four new pieces

### 1. The diary (audit log)

Every important moment is written down as an **event**:

| Event | When |
|---|---|
| `RECEIVED` | Someone asked to be deleted |
| `WRONG_CODE` | A wrong code was typed (says which attempt) |
| `WAITING` | The right code was typed; cooling-off started |
| `REJECTED` / `CANCELLED` | Code expired or too many wrong codes / the person changed their mind |
| `RUNNING` | Cooling-off over, deleting started (also after an admin retry) |
| `STAGE_STARTED` | A stage began, with the systems in it |
| `TASK_DONE` / `TASK_FAILED` | A system reported back / a system ran out of attempts |
| `NEEDS_ATTENTION` | A human needs to look |
| `COMPLETED` | Every system reported back |
| `DEADLINE_WARNING` / `DEADLINE_OVERDUE` | 7 days left / past the deadline |

**What makes it tamper-proof.** Each event gets a **seal**: a fingerprint of the event *plus the seal before it*.

```
 event 1            event 2               event 3
 RECEIVED    ──►    WAITING        ──►    RUNNING
 seal A             seal B = f(A + 2)     seal C = f(B + 3)
```

Change event 2 and its seal no longer matches. Delete event 2 and event 3 no longer points to the right seal. Either way, the break shows up.

Four layers protect the diary:
1. **The database refuses** any edit or delete of a diary row.
2. **The seals** reveal anyone who switches that refusal off and edits anyway.
3. **The seals need a secret key**, so someone who only has the database can't simply recalculate them all after editing.
4. **The certificate carries the latest seal.** Even someone who has the key can't quietly rewrite history without it disagreeing with certificates already handed out.

**Checking it:** `GET /api/audit/verify` walks the whole diary from the first event and answers `{"intact": true}`, or `{"intact": false, "firstBrokenEventId": 42}`.

### 2. ForgetMe forgets too

The moment a request is **finished**, whether it was completed, cancelled or rejected, ForgetMe wipes its own (scrambled) copy of the email. What stays is a **fingerprint** of the email:
- Whoever has the secret key can check "was alice@example.com deleted?" by making the fingerprint again and comparing.
- Nobody can turn the fingerprint back into the email.

That's also why the diary must **never contain personal data**: the diary is kept forever.

### 3. The certificate (the receipt)

`GET /api/requests/{id}/certificate` (admin only; only once the request is `COMPLETED`):

```json
{
  "requestId": "5b1…",
  "subjectFingerprint": "9f86d0…",            ← not the email
  "receivedAt": "…", "dueAt": "…", "completedAt": "…",
  "onTime": true,
  "systems": [
    {"system": "mailing", "stage": 1, "result": "DELETED",  "reportedAt": "…"},
    {"system": "orders",  "stage": 2, "result": "RETAINED", "note": "invoices kept 8 years: tax law", "reportedAt": "…"}
  ],
  "history": [ {"at": "…", "event": "RECEIVED", "detail": "request filed, confirmation code emailed"}, … ],
  "auditHash": "c0ffee…",                      ← the diary's latest seal when this was issued
  "statement": "Every registered system reported back on this request. …"
}
```

### 4. Deadline warnings

Every 5 seconds, the **Deadline Watcher** looks at unfinished requests:
- **7 days or less left** → one email to the admin: "request … is due within 7 days"
- **Past the deadline** → one more email: "request … is past its legal deadline"

Each warning is sent **once**, not every 5 seconds, and written in the diary. The emails contain only the request ID, never the person's email.

## How it runs on your computer

Nothing new to start: the diary, the certificate and the Deadline Watcher all live inside ForgetMe. Two settings changed in `application.yml`:

| Setting | Meaning |
|---|---|
| `hash-secret` | The secret key for every fingerprint: codes, emails, diary seals (renamed from `code-secret`) |
| `admin-email` | Who gets deadline warnings. Locally that's `admin@forgetme.local`, and it lands in Mailpit |

### Try it by hand

**1. Start fresh.** This wipes your local test data, including the pretend connector from phase 2, so requests finish instantly:
```powershell
docker compose down -v
docker compose up -d
.\mvnw.cmd -pl orchestrator spring-boot:run
```

**2. File and confirm a request** (the phase 1 commands). With no connectors registered, it completes as soon as the 1-minute cooling-off is over.

**3. See a deadline warning** (do this within that minute, while the request is still waiting). Pretend weeks went by:
```powershell
docker compose exec postgres psql -U forgetme -c "update privacy_request set due_at = now() + interval '3 days'"
```
Within 5 seconds, a warning email for the admin appears at http://localhost:8025.

**4. After the minute, get the certificate and check the diary:**
```powershell
$admin = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:admin")) }
Invoke-RestMethod -Uri "http://localhost:8080/api/requests/$($r.id)/certificate" -Headers $admin | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri http://localhost:8080/api/audit/verify -Headers $admin
```

**5. Play the attacker.** Try to edit the diary. The database refuses:
```powershell
docker compose exec postgres psql -U forgetme -c "update audit_event set detail = 'nothing to see' where id = 2"
```
Now switch the guard off first, like an attacker with full database rights would:
```powershell
docker compose exec postgres psql -U forgetme -c "alter table audit_event disable trigger audit_event_append_only; update audit_event set detail = 'nothing to see' where id = 2; alter table audit_event enable trigger audit_event_append_only;"
```
Run the diary check again: `intact` is now `false`, and it points at event 2.

## The code, file by file

New in this phase (in `orchestrator/src/main/java/dev/forgetme/`):

| File | What it does | Why it's built this way |
|---|---|---|
| `AuditLog` | Writes diary events with their seals (`record`), changes a status *and* writes it down in one go (`move`), lists a request's history, and checks the whole chain (`verify`). | All status changes go through `move`, so none can go unrecorded. It refuses to run outside a database transaction, because that's what keeps writers in single file. |
| `ProofController` | The certificate endpoint and the diary check endpoint. | Everything "proof" lives in one place. |
| `DeadlineWatcher` | Every 5 seconds, finds unfinished requests near or past their deadline and emails the admin once per level. | Kept separate from the Dispatcher, which is about sending jobs, not deadlines. |

Changed:

| File | What changed |
|---|---|
| `PrivacyRequest` | Wipes the email on any finish; keeps the email fingerprint, the finish time, and which deadline alert was sent |
| `Crypto` | Adds the email fingerprint and the diary seal; one `hash-secret` now covers all fingerprints |
| `RequestService`, `Dispatcher` | Every status change now goes through `audit.move(...)`; stages, reports, failures and wrong codes are written down too |
| `V3__audit_and_proof.sql` | The `audit_event` table, the "refuse edits" rule (a **trigger**), and the new request columns |
| `application.yml` | `hash-secret`, `admin-email` |

## Why it's built this way

| Choice | Plain-English reason |
|---|---|
| **A chain of seals, not just a list** | A plain list can be edited silently. A chain breaks visibly at the first edited line. |
| **Seals made with a secret key** | With a plain fingerprint, anyone with database access could edit a line and recalculate every seal after it. With a key, they can't. |
| **The database itself refuses edits** | The simplest guard goes first. The seals are there for someone who gets past it. |
| **The certificate carries the latest seal** | It anchors the diary outside the database: history can't be rewritten without disagreeing with receipts people already hold. |
| **Diary writers go one at a time** | If two events were written at the same instant, both could attach to the same previous seal, and the chain would split in two. A database lock (an **advisory lock**) makes writers wait their turn. |
| **Times are cut to microseconds before sealing** | The database stores time to the microsecond, but Java's clock has more digits. Sealing the extra digits would make every later check fail: a false alarm. |
| **Each field is sealed with its length in front** | Otherwise "AB" + "C" and "A" + "BC" would look the same, and an attacker could shift text between fields. |
| **The email is wiped on *every* finish** | A cancelled or rejected request doesn't need the email any more either. Keep only what you need. |
| **No personal data in the diary, ever** | The diary is kept forever, so anything in it would never be deleted. Connectors must not put personal data in their notes. |
| **The request is saved before its first diary line** | The diary line points at the request, so the request must already be in the database. |
| **Diary lines are cut to 500 characters** | Otherwise a very long note from a connector would make its report fail, and the connector would retry forever. |
| **One alert per level, remembered on the request** | So the admin gets one email, not one every 5 seconds. |

## The tools, and what each one does

| Tool | Its job in this phase |
|---|---|
| **HMAC-SHA256** (built into Java) | Makes the seals and the email fingerprint, with the secret key |
| **PostgreSQL trigger** (a tiny function in the database) | Refuses any edit or delete of diary rows |
| **PostgreSQL advisory lock** | Makes diary writers wait their turn |
| **Spring `@Transactional(MANDATORY)`** | Stops anyone calling the diary outside a transaction |
| **Spring `@Scheduled`** | Runs the Deadline Watcher every 5 seconds |
| **Spring Mail + Mailpit** | Sends the admin warnings (caught by Mailpit locally) |

## The tests

New or extended in `RequestFlowTest`:

| Test | The story it checks |
|---|---|
| `certificateProvesWhatEachSystemDid` | No certificate while running (409). After completion: the email is wiped, the certificate lists each system's result and the full history in order, shows the fingerprint (and no email anywhere), carries the diary's latest seal, and the diary checks out as intact |
| `auditLogCatchesAnEditedEvent` | The database refuses a direct edit. An "attacker" switches the guard off and edits event 2 → the check says broken, at event 2 |
| `auditLogCatchesADeletedEvent` | An "attacker" deletes event 2 → the check says broken, at event 3 (the one that no longer links up) |
| `deadlineWarningsReachTheAdminOnceEach` | 3 days left → exactly one warning, none for a cancelled request; past due → exactly one more; both are in the diary |
| `fileVerifyThenCancel`, `unverifiedRequestsExpire` | Now also check the email is wiped when a request is cancelled or rejected |
| `CryptoTest` → `auditLinksChangeWhenAnythingChanges` | Changing any field, the time, the previous seal or the key changes the seal; shifting text between fields does too |

**Did the tests actually catch bugs?** To check, we switched off the "was this event edited?" part of the diary check in a copy of the code. `auditLogCatchesAnEditedEvent` failed, as it should.

## Kept simple on purpose

| Shortcut | Fine for now because | Change it when |
|---|---|---|
| All diary writes, across all requests, go one at a time | Thousands of events a minute is plenty | Much heavier traffic: one chain per request, or batch sealing |
| The certificate is JSON, not a signed PDF | Easy to read and check | Someone needs a document to file or print |
| Admin alerts go by email only | Mailpit shows them locally | A team wants Slack or a pager |
| Requests filed before phase 3 have no fingerprint | Only old test data is affected | Never: new requests always get one |
| A connector *could* still put personal data in a note | The rule is written in the README | Connectors are outside your control (scrub notes before saving) |

## Things you can say in an interview

- "The audit log is a hash chain with HMAC, so editing or deleting any event breaks verification from that point, and someone with only database access can't rebuild the chain."
- "A Postgres trigger makes the table append-only; the chain catches anyone who disables it; and each certificate pins the latest hash, anchoring the log outside the database."
- "Appends take a Postgres advisory lock inside the transaction, so two concurrent events can't both link to the same predecessor and fork the chain."
- "I truncate timestamps to microseconds before hashing, because that's what Postgres stores. Otherwise every re-verification would be a false alarm."
- "The request's own copy of the email is erased on any terminal state. What remains is a keyed fingerprint, enough to prove who was deleted without keeping the address."
