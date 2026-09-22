# Phase 1: Asking to be deleted, and proving it's you

**In one sentence:** someone types their email, gets a 6-digit code, types it back, and their request is recorded and waiting. Nothing gets deleted yet; that's phase 2.

**Status:** ✅ done and tried by hand.

---

## What happens, step by step

1. **Someone asks.** They send their email to `POST /api/requests`.
2. **ForgetMe records it.** It cleans up the email (trims spaces, lowercases it), scrambles it (encryption), creates a 6-digit code, and saves the request with status `RECEIVED` and a **30-day deadline**.
3. **The code goes out by email.** Only a fingerprint of the code is saved, never the code itself.
4. **They type the code back** at `POST /api/requests/{id}/verify`:
   - right code → status `WAITING` (waiting for phase 2 to pick it up)
   - wrong code → "wrong code, N attempts left"
   - 5 wrong codes, or more than 24 hours later → `REJECTED` for good
5. **They can change their mind** with `POST /api/requests/{id}/cancel`, any time before deletion starts.
6. **An admin can look it up** with `GET /api/requests/{id}`, which needs a username and password.

```
 you ──email──► ForgetMe ──saves (scrambled)──► database
                   │
                   └──code──► email inbox (Mailpit on your computer)
 you ──code───► ForgetMe ──checks fingerprint──► WAITING ✅
```

## How it runs on your computer

Three things run at once:

| What | Where it runs | Address | Job |
|---|---|---|---|
| **ForgetMe app** | Java, in your terminal | `localhost:8080` | The program we wrote |
| **PostgreSQL** | Docker | `localhost:5432` | The database: keeps the requests |
| **Mailpit** | Docker | `localhost:1025` (mail in), `localhost:8025` (inbox web page) | A fake inbox, so no real emails are sent |

Start them:
```powershell
docker compose up -d                              # starts PostgreSQL + Mailpit
.\mvnw.cmd -pl orchestrator spring-boot:run       # starts ForgetMe
```

When ForgetMe starts, **Flyway** looks at the database and creates the tables if they're missing. The table design is in `V1__privacy_request.sql`.

## The code, file by file

All code is in `orchestrator/src/main/java/dev/forgetme/`.

| File | What it does | Why it's built this way |
|---|---|---|
| `ForgetMeApplication` | The on-switch. Starts everything. | Standard Spring Boot starting point. |
| `RequestController` | The **front desk**. Defines the web addresses, checks the input (is it an email? is the code 6 digits?), and turns results into answers like 200, 400, 409, 410. | Keeps "web stuff" separate from the rules, so the rules are easy to read and test. |
| `RequestService` | The **rules**: file, verify, cancel. | A wrong code *returns* a result instead of throwing an error. Throwing would undo the whole database change, including the "+1 wrong attempt", so people could guess forever. |
| `RequestStatus` | The **rulebook** of statuses and which moves are allowed. | One place for all the rules. If someone adds a new status later, the code won't compile until they say where it may move. |
| `PrivacyRequest` | What one request looks like in the database. | Only small, named actions can change it (`moveTo`, `wrongCode`), so nothing can put it in a nonsense state. |
| `PrivacyRequestRepository` | Reads and saves requests. `findLockedById` locks the row. | The lock makes simultaneous guesses **wait in line**, so nobody can fire 100 guesses at once to get around the 5-guess limit. |
| `Crypto` | Scrambles emails (AES-GCM) and fingerprints codes (HMAC). | AES-GCM also spots tampering. The code fingerprint uses a secret key *and* the request ID, so a stolen database can't be used to work out codes. |
| `SecurityConfig` | Decides who may use which address. | Filing, verifying and cancelling are open to anyone. Everything else needs the admin password. |

Other files:

| File | What it does |
|---|---|
| `orchestrator/src/main/resources/application.yml` | Settings: database address, mail server, admin password, secret keys (safe-for-development defaults) |
| `orchestrator/src/main/resources/db/migration/V1__privacy_request.sql` | The `privacy_request` table |
| `compose.yaml` | Tells Docker to start PostgreSQL and Mailpit |
| `pom.xml`, `orchestrator/pom.xml` | The shopping list of libraries, and how to build |
| `mvnw`, `mvnw.cmd` | Downloads Maven for you and runs it |

## The tools, and what each one does

| Tool | Its job in this phase |
|---|---|
| **Java 21** | The language |
| **Spring Boot 4.1** | Glues everything together: web server, database access, security, email, settings |
| **Spring Web MVC** | Turns web requests into Java method calls, and Java objects into JSON |
| **Spring Data JPA / Hibernate** | Saves Java objects to the database and reads them back, without hand-writing SQL |
| **Spring Security** | The admin password check and the "who may do what" rules |
| **Bean Validation** | Checks input with labels like `@Email` and `@Pattern` |
| **Spring Mail** | Sends the code email |
| **PostgreSQL** | The database |
| **Flyway** | Creates and upgrades the tables from numbered SQL files (`V1`, `V2`, …) |
| **Docker + Compose** | Runs PostgreSQL and Mailpit with one command |
| **Mailpit** | Catches emails so you can read them at `localhost:8025` |
| **Maven (via `mvnw`)** | Downloads libraries, builds, runs tests |
| **JUnit 5** | Runs the tests |
| **Testcontainers** | Starts a throwaway PostgreSQL in Docker just for the tests |
| **Mockito** | Swaps the real email sender for a fake one in tests, so a test can read the code |

## The tests

| Test | What it checks |
|---|---|
| `RequestStatusTest` (3) | Allowed moves work; finished requests can't move; you can't skip verification or cancel mid-deletion |
| `CryptoTest` (3 of 4) | Scrambling round-trips; the same email scrambles differently each time; tampering is caught; a code only matches its own request |
| `RequestFlowTest` → `fileVerifyThenCancel` | Whole journey: file → email is scrambled in the database → admin-only lookup → wrong code → right code → `WAITING` → cancel → can't cancel twice |
| `RequestFlowTest` → `fiveWrongCodesRejectTheRequest` | 4 wrong codes get a 400, the 5th gets 410, and after that even the right code is refused |

Run them: `.\mvnw.cmd test`

**A bug the tests caught:** an email with an accidental space around it was rejected as "not an email". Now spaces are trimmed first.

## Kept simple on purpose

| Shortcut | Fine for now because | Change it when |
|---|---|---|
| The code email is sent inside the database transaction | Failures are rare, and the worst case is a code for a request that wasn't saved | Emails must never be lost or doubled (use an "outbox" table) |
| One admin user from settings | It's a demo | Several people need to log in |
| No rate limit | Runs on your computer | It goes on the internet (M5) |
| Dev secrets in `application.yml` | Easy to run locally | Deploying (M5): set real ones as environment variables |

## Things you can say in an interview

- "A wrong code returns a result instead of throwing, because an exception would roll back the transaction, and with it the attempt counter."
- "Verification locks the row with `SELECT … FOR UPDATE`, so parallel guesses queue up instead of racing past the 5-attempt limit."
- "Codes are stored as an HMAC bound to the request ID, so a leaked database can't be brute-forced offline."
- "Emails are encrypted with AES-GCM, which also detects tampering."
