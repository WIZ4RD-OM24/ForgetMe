# Phase 5: Shipping it

**In one sentence:** ForgetMe now has a web page for the admin, protection against abuse, separate production settings, API docs, automatic testing on GitHub, everything needed to put it on a server, and real measured numbers.

**Status:** 🟡 everything done except the final step, putting it on a real server, which needs a server you own. All the files for it are ready.

---

## What's new

### 0. A web page for the person asking to be deleted

Open **http://localhost:8080/** and it's three screens, no commands needed:

1. **Ask.** Type your email, press "Send me a code".
2. **Confirm.** Type the 6-digit code from your inbox. A wrong code says so and lets you try again.
3. **Watch.** The page updates itself every 5 seconds: first "deleting starts at …, you have a moment to change your mind" with a cancel button, then each system appearing with what it did (*deleted*, *kept for tax law*, and so on), and finally **your receipt**: when it finished, whether it was inside the legal deadline, your fingerprint and the proof code.

The address of that page is the only way back to the request, and it contains a random ID nobody can guess. There's no login, because there's no account to log into.

### 1. An admin web page

Open **http://localhost:8080/admin** and log in (`admin` / `admin` locally).

- **The list:** every request, newest first, with its status and how many days are left before the legal deadline. At the top: whether the audit diary is intact, and how many events it holds. Below: the connected systems.
- **One request:** each system's result and note, the whole history, a **Retry** button when a request needs attention, and a link to the certificate when it's finished.

No personal data is shown anywhere: requests are identified by their ID.

### 2. Protection against abuse

| Limit | What it stops |
|---|---|
| 20 requests per hour from one internet address (5 in production) | Someone hammering the service or using it to send masses of email |
| 3 requests per email address per day | Someone pestering one person's inbox from many addresses |
| Optional allow-list of email domains | A public demo can accept only `example.com`, so it can never email a real person |

### 3. Production settings

A separate production mode (`prod`). In it, **no secret has a default**: the app refuses to start unless the encryption key, the fingerprint secret and the admin password are all supplied from the environment. Waiting times also change to realistic ones:

| Setting | Local | Production |
|---|---|---|
| Cooling-off | 1 minute | 24 hours |
| Retry waits | 10 s, 20 s, 40 s… | 10 min, 20 min, 40 min… |
| Report deadline | 5 minutes | 2 hours |
| Requests per address per hour | 20 | 5 |

### 4. API documentation

**http://localhost:8080/swagger-ui.html** lists every endpoint, with the fields each one takes, generated from the code.

### 5. Automatic testing on GitHub

Every push runs the whole test suite on GitHub's machines (`.github/workflows/ci.yml`). It uses a real database in Docker, just like on your computer. The README shows a green or red badge.

### 6. Ready to deploy

| File | What it's for |
|---|---|
| `deploy/compose.prod.yaml` | Production version: nothing exposed directly, secrets from a `.env` file, everything restarts if the server reboots |
| `deploy/Caddyfile` | Caddy, a small web server that gets HTTPS certificates automatically |
| `deploy/.env.example` | The list of secrets to fill in |

### 7. Measured numbers

See below.

## The numbers (measured, not guessed)

Run on this laptop, with everything (ForgetMe, the database, and the four demo systems) in Docker on the same machine.

**Part 1: people filing requests.** 200 complete journeys (file a request → read the code from the inbox → confirm it), 20 at a time:

| Measure | Result |
|---|---|
| Finished | 200 of 200, no failures |
| Total time | 3.0 seconds |
| Requests handled | ~199 web requests/second, ~66 complete journeys/second |
| Filing: 95% finished within | 582 ms |
| Confirming: 95% finished within | 224 ms |

**Part 2: ForgetMe doing the deleting.** The same 200 requests, each fanned out to 4 systems:

| Measure | Result |
|---|---|
| All 200 fully deleted in | 30 seconds (about **400 requests a minute**) |
| Connector jobs done | 800 |
| Average tries per job | 1.25 (the demo mailing system fails on purpose the first time) |
| Diary entries written | 2,200, chain intact |

### How to repeat it

```powershell
docker compose --profile demo up -d --build
docker run --rm -i --network java-project_default -e BASE=http://orchestrator:8080 -e MAIL=http://mailpit:8025 grafana/k6 run - < loadtest/file-and-verify.js
```
Then watch ForgetMe work through them, and measure:
```powershell
docker compose exec postgres psql -U forgetme -tAc "select count(*) filter (where status='COMPLETED') || '/' || count(*) from privacy_request"
docker compose exec postgres psql -U forgetme -tAc "select count(*), round(extract(epoch from (max(closed_at) - min(run_after)))) as seconds from privacy_request where status='COMPLETED'"
```

## Putting it on a server (your step)

You need a Linux server with Docker. Two good options: Oracle Cloud's **Always Free** tier (costs nothing, but signing up is fiddly and asks for a card to prove who you are), or a small rented server for about **$4–6 a month** (Hetzner, DigitalOcean and similar). Avoid AWS, Google Cloud and Azure for this: their free tiers are easy to overshoot and they charge automatically.

1. **Point a name at the server.** No need to buy a domain: if your server's address is `203.0.113.10`, then `203-0-113-10.sslip.io` already points there.
2. **On the server:**
   ```bash
   git clone https://github.com/WIZ4RD-OM24/ForgetMe.git && cd ForgetMe
   cp deploy/.env.example deploy/.env
   openssl rand -base64 32   # run twice: one for the encryption key, one for the fingerprint secret
   nano deploy/.env          # fill in DOMAIN, the two secrets, a database password and an admin password
   docker compose --env-file deploy/.env -f compose.yaml -f deploy/compose.prod.yaml --profile demo up -d --build
   ```
3. **Open `https://<your-domain>/admin`.** Caddy gets the HTTPS certificate on its own. Visitors read their demo codes at `https://<your-domain>/mail`, and see what each system still holds at `https://<your-domain>/demo/users/data` and so on.
4. **Optional:** put the demo data back every hour, so each visitor can delete Alice again:
   ```bash
   (crontab -l 2>/dev/null; echo "0 * * * * cd ~/ForgetMe && docker compose restart mailing orders uploads users") | crontab -
   ```

Only `example.com` addresses are accepted in production, so nobody's real inbox can be reached from the public demo.

## The code, file by file

| File | What it does |
|---|---|
| `PublicPageController` | The three screens for the person asking. One page (`/r/{id}`) covers confirm, watch and receipt: it shows whichever part fits the request's current status |
| `templates/public/home.html`, `status.html` | Those pages |
| `AdminPageController` | Builds the two admin pages. Dates are formatted here so the templates stay simple |
| `templates/admin/requests.html`, `request.html` | The two pages (Thymeleaf) |
| `static/admin.css` | ~25 lines of styling, light and dark |
| `RateLimiter` | Counts what each internet address does, in one-hour windows |
| `application-prod.yml` | Production settings, with no default secrets |
| `.github/workflows/ci.yml` | Runs the tests on every push |
| `deploy/*` | Production compose file, Caddy config, secrets template |
| `loadtest/file-and-verify.js` | The k6 load test |

**Changed:** `RequestController` now checks the address limit and reuses a new `RequestService.progress(id)`, which the admin page uses too. `SecurityConfig` has a second rule set for the admin pages.

## Why it's built this way

| Choice | Plain-English reason |
|---|---|
| **Pages built by the server, not a separate JavaScript app** | Two small HTML files and ~40 lines of styling do the job. A React app would be more to build, more to run, and would show nothing extra |
| **One page for confirm, watch and receipt** | It's one thing to the visitor: "my request". One address to keep, and it always shows whatever's true right now |
| **The page refreshes itself every 5 seconds** | A plain HTML line does it. No JavaScript, no live connection to keep open |
| **A separate rule set for the admin pages** | Browser pages need protection against a trick where another website makes your browser press a button on this one (CSRF). The JSON API doesn't need it, and turning it on there would just get in the way. |
| **Limits counted in memory** | No extra system to run. If ForgetMe ever runs as several copies, each would count on its own, so that would move to a shared store. |
| **A per-email limit as well as per-address** | The address limit alone wouldn't stop someone using many addresses to spam one person's inbox. |
| **Production has no default secrets** | A missing secret should stop the app at startup, loudly, rather than quietly using a value from a public repository. |
| **Caddy in front** | Free automatic HTTPS, and nothing else is exposed to the internet. |
| **sslip.io instead of buying a domain** | It turns any IP address into a name, which is all the certificate needs. |
| **Dates formatted in Java, not in the page** | Keeps the templates free of clever expressions. |

## The tests

| Test | What it checks |
|---|---|
| `RequestFlowTest` → `anyoneCanAskConfirmAndWatchFromTheWebPages` | The whole visitor journey through the forms: ask → wrong code is refused with a message → right code → "confirmed" → cancel |
| `RateLimiterTest` (2) | Allows up to the limit and then stops; counts each caller separately; forgets after an hour |
| `RequestFlowTest` → `oneAddressCantBeFloodedWithRequests` | The 4th request for the same address in a day is refused (429), capital letters don't sneak past, other addresses are unaffected |
| `RequestFlowTest` → `adminPageListsRequestsAndIsPrivate` | The page needs a login, lists the request, shows the audit status, and the detail page shows the status |
| Everything from phases 1–4 | Still passing: 29 tests in total |

Checked by hand as well: all four screens clicked through in a real browser (ask → code → confirmed → receipt), the admin pages, and the production mode refusing to start without secrets. 30 tests in total.

## Kept simple on purpose

| Shortcut | Fine for now because | Change it when |
|---|---|---|
| Admin login is the browser's built-in password box | One admin, one password | Several people need accounts |
| The visitor's page is plain HTML, refreshed every 5 s | Nothing moves fast enough to need more | You want a live progress bar (then use server-sent events) |
| Limits reset on restart | The app restarts rarely | It runs as several copies |
| No metrics dashboard | The admin page and logs are enough | You need trends over time (Actuator + Grafana) |
| The demo's data comes back only on restart | A cron line handles it | Never |

## Things you can say in an interview

- "The public endpoints are rate-limited per IP and per subject; the per-subject limit exists because the IP limit alone doesn't stop someone spamming one person's inbox from a botnet."
- "The admin UI is a separate Spring Security filter chain with CSRF enabled, because form posts from a browser need it while the stateless JSON API doesn't."
- "The prod profile deliberately has no defaults for secrets, so a missing environment variable fails startup instead of silently using a value from the repository."
- "Measured with k6: 200 end-to-end request-and-confirm journeys in 3 s, p95 filing 582 ms; the dispatcher then completed all 200 across four connectors in 30 s, about 400 requests a minute."
