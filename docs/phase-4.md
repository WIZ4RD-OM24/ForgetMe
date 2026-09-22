# Phase 4: The plug-in, and a pretend company to try it on

**In one sentence:** other programmers can now connect their app to ForgetMe with one dependency and about 5 lines of code, and one command starts a pretend company of four systems so you can watch a real deletion happen from start to finish.

**Status:** ✅ done. 25 automatic tests pass, and the full demo was run end to end.

---

## Part 1: the plug-in (the "starter")

Until now, a system that wanted to work with ForgetMe had to do a lot itself: open a web address, check the secret stamp on every job, run the deletion, then send back a stamped report. The **starter** does all of that. The app only says *how to delete a person*:

```java
@Bean
ErasureHandler erasure() {
    return subject -> {
        orders.removeEmail(subject.email());
        return ErasureResult.retained("invoices kept 8 years for tax law; email removed");
    };
}
```

plus one setting, the secret ForgetMe gave it when it was registered:

```yaml
forgetme.connector.secret: <the secret>
```

### What the starter does when a job arrives

1. ForgetMe knocks on `POST /privacy/erase` (the starter opens this door automatically).
2. The starter checks the **secret stamp**. Wrong or older than 5 minutes → `401`, nothing happens.
3. It runs the app's `ErasureHandler`.
   - If that **fails** (throws an error) → answers `500`. ForgetMe tries again in 10 s, 20 s, …
   - If it **works** → answers `202` ("got it") straight away…
4. …and a moment later, on a separate lightweight thread, sends ForgetMe a **stamped report**: `DELETED`, `ANONYMIZED` or `RETAINED` + a note.

If the report gets lost, nothing breaks: ForgetMe hears nothing, sends the job again, the handler runs again (deleting something already gone just succeeds), and a new report goes out.

## Part 2: the pretend company

Four small systems, each holding a bit of customer data:

| System | Stage | What it holds | What it does when asked to delete | Look at it |
|---|---|---|---|---|
| **mailing** | 1 | Newsletter list | Removes the email. **Fails the first try of every request on purpose**, so you can watch a retry | http://localhost:8084/data |
| **orders** | 2 | Past orders | Keeps the orders (tax law), but removes the email from them → `RETAINED` with the reason | http://localhost:8082/data |
| **uploads** | 2 | Profile photos, one folder per customer | Deletes the customer's folder | http://localhost:8083/data |
| **users** | 3 | Customer accounts | Deletes the account, last of all | http://localhost:8081/data |

They start with data for **alice@example.com** and **bob@example.com** (and carol on the mailing list), so you can delete Alice and see that Bob is left alone.

### Start everything with one command

Make sure nothing else is using port 8080. If ForgetMe is still running from `mvnw`, stop it with Ctrl+C.

Then start fresh. This wipes your local test data, including connectors you registered by hand in earlier phases (like the phase 2 "mailing" one that points nowhere), so the demo's four systems are the only ones:

```powershell
docker compose down -v
docker compose --profile demo up --build
```

The first time takes a few minutes, because it builds everything inside Docker. It starts 8 containers: the database, the fake inbox, ForgetMe, the four systems, and a one-time **setup** step that registers the four systems with ForgetMe. Wait for the line `Demo ready`.

### Delete Alice

In a second PowerShell window:
```powershell
$r = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/requests -ContentType 'application/json' -Body '{"email":"alice@example.com"}'
```
Get the code from http://localhost:8025, then:
```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/requests/$($r.id)/verify" -ContentType 'application/json' -Body '{"code":"123456"}'
```
Now open the four `/data` pages above and refresh them. In about a minute (20 s cooling-off, one retry for mailing, then the stages), Alice disappears: first from mailing, then from uploads and orders together, then from users. Bob stays.

The certificate at the end:
```powershell
$admin = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:admin")) }
Invoke-RestMethod -Uri "http://localhost:8080/api/requests/$($r.id)/certificate" -Headers $admin | ConvertTo-Json -Depth 5
```

**What we saw when we ran it:** finished in 48 seconds. Mailing failed once and worked on the retry; uploads and orders ran together; users went last. The certificate listed all four results, and the diary check said intact (11 events).

To stop: Ctrl+C, then `docker compose --profile demo down`. The demo systems keep their data in memory, so the next start has Alice back.

## How it runs

```
                     ┌───────────── Docker ─────────────────────────────────────┐
 you ──► :8080 ──►   │ ForgetMe ──jobs──► mailing :8084   orders :8082           │
                     │    ▲              uploads :8083    users  :8081           │
                     │    └──────── stamped reports ◄──────────┘                 │
                     │ PostgreSQL (ForgetMe's data)    Mailpit :8025 (fake inbox)│
                     └───────────────────────────────────────────────────────────┘
```

- **One demo app, four copies.** All four systems are the same program. A setting (the Spring **profile**) decides which one each copy plays.
- **One Dockerfile, two images.** It builds everything once, then makes an image for ForgetMe and one for the demo app.
- **`compose.yaml` has two modes.** `docker compose up -d` still starts just the database and inbox, for working on ForgetMe itself. `--profile demo` adds everything else.

## The code, file by file

**The starter** (`forgetme-spring-boot-starter/src/main/java/dev/forgetme/connector/`):

| File | What it does | Why it's built this way |
|---|---|---|
| `ErasureHandler` | The one thing an app writes: "how to delete this person". | A plain interface, defined as a bean, is simpler than scanning the whole app for special annotations. |
| `ErasureResult` | The answer: deleted, anonymized, or retained (+ reason). | Ready-made shortcuts like `ErasureResult.deleted()` keep handlers short. |
| `ErasureEndpoint` | The `/privacy/erase` door: checks the stamp, runs the handler, answers, and sends the report. | Runs the handler *before* answering, so a failure can say "500, try again" right away. The report goes on its own thread, so the answer isn't held up. |
| `Signatures` | Makes and checks the secret stamp. | A copy of ForgetMe's own stamp code. A shared test answer in both modules guarantees they always agree. |
| `ForgetMeConnectorAutoConfiguration` | Switches the starter on in any app that has an `ErasureHandler`. | That's what makes it "just add the dependency". Spring Boot finds it through a small list file in `META-INF/spring/`. |

**The demo** (`demo/src/main/java/dev/forgetme/demo/`):

| File | What it plays |
|---|---|
| `DemoApplication` | The on-switch |
| `MailingSystem` | Newsletter list (stage 1, fails the first try) |
| `OrdersSystem` | Orders (stage 2, `RETAINED`) |
| `UploadsSystem` | Photo folders on disk (stage 2) |
| `UsersSystem` | Accounts (stage 3) |

Each one is a single small class that *is* both the web page showing its data (`/data`) and the `ErasureHandler`.

**Other new files:**

| File | What it does |
|---|---|
| `Dockerfile` | Builds the whole project inside Docker and makes the two images |
| `.dockerignore` | Leaves build leftovers out of the Docker build |
| `compose.yaml` | Now also describes ForgetMe, the four systems and the setup step (under the `demo` profile) |
| `demo/register-connectors.sh` | The setup step: waits for ForgetMe, then registers the four systems |
| `.gitattributes` | Keeps Linux line endings on shell scripts, so they still work when the project is checked out on Windows |

**Changed in ForgetMe:** registering a connector can now include your own `secret` (32+ characters). That's what lets the setup script and the demo systems agree on secrets in advance.

## Why it's built this way

| Choice | Plain-English reason |
|---|---|
| **An interface bean, not a special annotation** | Less magic, less code, and it's obvious where the handler comes from. |
| **Run the handler first, report second** | A failure turns into a quick "try again" (seconds), instead of waiting 5 minutes for a report that never comes. |
| **Report on a separate thread** | ForgetMe gets its "202, got it" immediately. |
| **Handlers must cope with running twice** | Retries mean the same job can arrive again; "already gone" must count as success. |
| **One demo program, four roles** | One file each instead of four whole projects. The point of the demo is ForgetMe, not the demo systems. |
| **Demo data in memory / plain files** | No extra databases or storage services to run. The roadmap said MinIO for uploads; a folder on disk shows the same thing. |
| **Secrets can be supplied at registration** | Lets a script set everything up with one command. Generated secrets are still the default. |
| **The stamp code is copied, with a shared test** | The starter stays small and independent, and the shared test answer catches any drift. |
| **Compose "profiles"** | The everyday developer setup (`docker compose up -d`) didn't change. The demo is opt-in. |

## The tools, and what each one does

| Tool | Its job in this phase |
|---|---|
| **Spring Boot auto-configuration** | Switches the starter on by itself when an app has an `ErasureHandler` |
| **Spring profiles** | Decide which of the four roles a demo copy plays |
| **Virtual threads** (Java 21) | Lightweight threads for sending reports |
| **Java's built-in `HttpClient`** | Sends the report from the starter |
| **Dockerfile (multi-stage)** | Stage 1 builds with Maven; stages 2 and 3 copy just the finished program into small Java runtime images |
| **Docker Compose profiles** | Keep the full demo separate from the everyday setup |
| **curl** (in a tiny container) | The setup step that registers the four systems |

## The tests

| Test | What it checks |
|---|---|
| `ErasureEndpointTest` → `runsTheHandlerAndReportsBackSigned` | A stamped job → 202, the handler ran, and a stamped report with the right result reached a pretend ForgetMe |
| `ErasureEndpointTest` → `refusesJobsWithoutTheRightStamp` | Wrong secret → 401, nothing deleted, no report |
| `ErasureEndpointTest` → `aFailingHandlerAsksForARetry` | Handler error → 500 and no report, so ForgetMe retries |
| `SignaturesTest` + `CryptoTest` → the shared known answer | Starter and ForgetMe produce the exact same stamp |
| `RequestFlowTest` → `aConnectorCanBringItsOwnSecret` | A supplied secret is used; one under 32 characters is refused |
| **The whole demo, run for real** | Docker build → 4 systems registered → delete Alice → retry on mailing → stages in order → Alice gone everywhere, Bob untouched → certificate correct → diary intact |

## Kept simple on purpose

| Shortcut | Fine for now because | Change it when |
|---|---|---|
| The handler must finish within about 10 s | Demo deletions take milliseconds | A system needs minutes (answer 202 first, run later, report when done) |
| The starter's path is fixed at `/privacy/erase` | One door is enough | An app already uses that path |
| Demo data resets on restart | It's a demo | Never |
| Demo secrets are written in `compose.yaml` | They're labelled demo-only | Anything real: generate secrets and keep them out of files |
| Apps with Spring Security must allow `/privacy/erase` themselves | The stamp is the real check | The starter could add that rule automatically |

## Things you can say in an interview

- "I wrote a Spring Boot starter: add the dependency, define one `ErasureHandler` bean, and auto-configuration exposes a signed endpoint and handles reporting back."
- "The handler runs synchronously so failures come back as a 500 and get retried with backoff; the signed report is sent on a virtual thread after the 202."
- "The protocol is at-least-once: lost reports are covered by the orchestrator re-sending, so handlers are written to be idempotent."
- "`docker compose --profile demo up --build` brings up the orchestrator and four services from one multi-stage Dockerfile; one demo app plays four roles through Spring profiles."
