# ForgetMe, explained simply

*This file explains the project in plain English. It's updated every time the project changes.*
*For how each step works in detail (the code, the tools, the reasons), see the phase guides: [phase 1](docs/phase-1.md) · [phase 2](docs/phase-2.md) · [phase 3](docs/phase-3.md) · [phase 4](docs/phase-4.md) · [phase 5](docs/phase-5.md).*

## Where we are right now

**All 5 steps are built.** The only thing left is putting it online, which needs a server you rent or sign up for.
- **Step 1:** someone can ask to be deleted and prove it's them with an emailed code. *(Tried by hand ✅)*
- **Step 2:** once confirmed, ForgetMe contacts every part of the company that holds their data, in the right order, keeps retrying the ones that fail, and finishes when everyone has reported back.
- **Step 3:** every step is written in a diary nobody can secretly edit; a finished request gets a receipt (certificate); ForgetMe forgets the email once a request is over; the admin is emailed when a deadline gets close.
- **Step 4:** a plug-in lets any app join ForgetMe with about 5 lines, and **one command** starts a pretend company of four systems. We deleted "Alice" from all four in 48 seconds.
- **Step 5:** web pages for both sides (the person asking, and the admin), limits that stop misuse, separate production settings, API documentation, automatic testing on GitHub, and everything needed to put it on a server.

**All 30 automatic checks pass.**
**The code is on GitHub** at https://github.com/WIZ4RD-OM24/ForgetMe, and it's public, so you can put the link on your resume.
**Next:** put it online. You'll need a server (free on Oracle Cloud, or about $5 a month elsewhere). The steps are in the [phase 5 guide](docs/phase-5.md#putting-it-on-a-server-your-step), and I can walk you through them.

---

## What are we building?

A tool that helps a company **completely delete a customer's data when the customer asks**, and **prove** it did.

## The simplest way to picture it

Imagine you're moving out of a city. You have to tell *everyone*: the bank, the gym, the phone company, the electricity company. Some reply right away. Some ignore you, so you call again. Some say "we have to keep your tax papers for 8 years." At the end you want **a receipt** from each one.

ForgetMe is that **moving-out helper**, but for a company's computer systems. A customer says "forget me," and ForgetMe:
1. makes sure it's really them, *(Step 1 ✅)*
2. contacts every part of the company that holds their information, *(Step 2 ✅)*
3. keeps chasing the ones that don't answer, *(Step 2 ✅)*
4. hands back a receipt proving everything was handled. *(Step 3 ✅)*

## Why does this matter?

- **It's the law.** In Europe (GDPR), India (DPDP Act) and California (CCPA), people can ask companies to delete their data. Companies get a deadline (about a month in Europe) and face big fines if they miss it.
- **It's genuinely hard.** A customer's info isn't in one place. It's in the accounts list, the orders, uploaded photos, the mailing list and more. It's easy to forget one.
- **Big companies pay a lot for tools like this.** Small companies usually do it by hand and make mistakes.

## What's built so far, in plain words

### Step 1: "Is it really you?"
- You type your email; a 6-digit code arrives; you type it back.
- The code expires in 24 hours. 5 wrong guesses and the request is locked, like a bank card after wrong PINs.
- Your email is stored scrambled, and the code isn't stored at all (only a fingerprint of it).
- A 30-day legal deadline clock starts the moment you ask.

### Step 2: "Everyone, please delete this person"
- The admin lists every part of the company that holds data (a **connector**), and gives each a **stage** number.
- After a short wait (in case you change your mind), ForgetMe messages every stage-1 connector. When they've all replied, it moves to stage 2, and so on.
- Each message carries a **secret stamp**, so nobody can fake one. The replies must be stamped too.
- A connector that doesn't answer gets called again, waiting longer each time. After 5 tries, a human is asked to look (**needs attention**) and can press **retry**.
- Each connector replies with one of: **deleted**, **blanked out** (anonymized), or **kept, with a reason** (like tax records).
- When every connector has replied, the request is **completed**.

### Step 3: "Prove it"
- **A diary nobody can secretly edit.** Every step is written down. Each line carries a seal made from the line itself *and the seal before it*, like a chain. Change or remove any line and the chain visibly breaks from that point. The database also flat-out refuses edits, and the seals need a secret key, so even someone with database access can't redo them.
- **A receipt (certificate)** when a request is finished: what every system did (deleted, blanked out or kept with a reason), when, whether it was on time, the full history, and the diary's latest seal, so the receipt and the diary can always be checked against each other.
- **ForgetMe forgets too.** The moment a request is over (done, cancelled or rejected), ForgetMe wipes its own copy of the email. It keeps only a fingerprint that proves *who* was deleted but can't be turned back into the email.
- **Deadline warnings.** The admin gets one email when a request has 7 days left, and one more if it goes past its deadline.

### Step 4: "Make it easy to join, and show it working"
- **The plug-in (a "starter").** Another programmer adds it to their app and writes one small piece: *how to delete a person in my app*. The plug-in does everything else: opens the door ForgetMe knocks on, checks the secret stamp, runs their deletion, and sends a stamped reply back.
- **The pretend company.** Four small systems, each holding some customer data:
  - a **mailing list**, which on purpose fails its first try every time, so you can watch ForgetMe retry
  - **orders**, which keeps the orders for tax law but removes the email from them
  - **uploads**, which deletes the customer's photo folder
  - **user accounts**, which are deleted last
- **One command starts it all**, inside Docker: ForgetMe, the four systems, the database and the fake inbox. Then you ask to delete Alice and watch her disappear from each system in the right order, while Bob stays.

### Step 5: "Make it real"
- **A web page for the person asking**, at the main address. Three screens: type your email → type the 6-digit code from your inbox → watch each system report in, ending with a receipt. It refreshes itself, and there's a cancel button while it's still waiting. No commands, no login: the page's address is the only thing you need to keep.
- **An admin web page** at `/admin`: every request, how many days are left before the legal deadline, what each system did, the full history, and a **retry** button. No personal details are shown, only request IDs.
- **Limits that stop misuse:** one internet address can only file so many requests an hour, and one email address can only be targeted 3 times a day, so nobody can use ForgetMe to flood someone's inbox.
- **Production settings:** a separate mode where nothing has a default password or key. If a secret is missing, the app refuses to start rather than quietly using a public one. Waiting times become realistic too: a day to change your mind, retries spread over hours.
- **API documentation** at `/swagger-ui.html`, written automatically from the code.
- **Automatic testing:** every time code is uploaded to GitHub, all the tests run there too, and the README shows a green tick.
- **Ready for a server:** the files needed to run it online with automatic HTTPS (the padlock in the browser).
- **Real measured numbers** (see below) instead of guesses.
- **A security check of the whole thing** (see below), and the four holes it found are fixed.

### Is it safe?

We went through it looking for holes. The good parts were already good: the code you get by email is never stored as-is, emails are locked with the same kind of encryption a bank uses, nothing personal is ever written to the diary or the logs, and every page escapes what it prints, so nobody can sneak code into it.

Four things needed fixing, and they're fixed:

| What was wrong | Why it mattered | Fixed by |
|---|---|---|
| Nothing stopped someone guessing the admin password over and over | Given enough time, a weak password falls | 10 wrong tries and that address is shut out for the hour |
| A visitor could invent a fake "who I am" label and reset their own limit | The "so many requests an hour" limit could be walked straight past | The web server in front now overwrites that label with the real one |
| The app ran as the computer's most powerful user inside its box | If it were ever broken into, the damage would be worse | It now runs as an ordinary user |
| The file holding the real passwords could be committed by accident | Secrets on a public page | Git now ignores it |

Two things are deliberate, not accidents: the demo's fake inbox is open to everyone (that's how a stranger reads their own code), and the admin can point ForgetMe at any address they like (an admin is trusted by definition).

### How fast is it?

Measured on this laptop, with everything running in Docker:

| Test | Result |
|---|---|
| 200 people asking to be deleted and confirming by code, 20 at a time | **3 seconds**, nothing failed |
| Waiting time for the person: filing / confirming | under 0.6 s / 0.3 s for 95% of them |
| Those 200 people fully deleted from all 4 systems | **30 seconds** (about 400 people a minute) |
| Diary entries written, all checked and intact | 2,200 |

### Why the order matters

The main customer account is like **your contact list**. It holds the email, phone number and IDs that the other parts need to find the right data. Delete the contact list first and you can't reach anyone else. So:

1. **First:** stop anything still happening (stop marketing emails, block login)
2. **Then:** clean up everywhere else, all at once
3. **Last:** delete the main account
4. **Finally:** ForgetMe forgets too *(Step 3 ✅)*. It deletes its own copy of the customer's email and keeps only a fingerprint.

## The plan, step by step

| Step | What you'll be able to show |
|---|---|
| 0 ✅ | The plan (these documents) |
| 1 ✅ | Someone can ask to be deleted and confirm with an email code |
| 2 ✅ | ForgetMe contacts every part of the company in order, and keeps retrying the ones that fail |
| 3 ✅ | A receipt at the end, a tamper-proof diary, and warnings when a deadline is close |
| 4 ✅ | A pretend company (4 tiny apps) to demo on, plus a plug-in so other programmers can connect their apps in 5 lines |
| 5 ✅ | A simple admin page, testing on GitHub, real speed numbers, and everything ready to go online |

## Try it yourself

**Before you start:** open Docker Desktop and wait until it says it's running.
*If it gets stuck on "starting"* (this happened once, right after installing): quit it fully (right-click the whale icon near the clock → Quit), then open it again.

**The quickest way to see everything: the pretend company.** Follow the [phase 4 guide](docs/phase-4.md). In short:
```powershell
docker compose down -v
```
```powershell
docker compose --profile demo up --build
```
Then open **http://localhost:8080/**, type `alice@example.com`, get the code from the fake inbox at **http://localhost:8025**, and watch Alice disappear from the four systems. The admin's view of the same thing is at **http://localhost:8080/admin** (username `admin`, password `admin`).

*If port 8080 is already in use, start it with `$env:FORGETME_PORT = "8090"` in front of the compose command, and use 8090 in the addresses.*

**Or run just ForgetMe** (in PowerShell, inside the project folder):
```powershell
docker compose up -d
```
```powershell
.\mvnw.cmd -pl orchestrator spring-boot:run
```

Then follow the "try it" section of the phase guide you want to see: [phase 1](docs/phase-1.md) (ask and confirm), [phase 2](docs/phase-2.md) (watch ForgetMe contact a connector and retry) or [phase 3](docs/phase-3.md) (get a certificate, see a deadline warning, and try to tamper with the diary).

To run the automatic checks:
```powershell
.\mvnw.cmd test
```

## Tech words you'll see

| Word | Plain meaning |
|---|---|
| Java | The programming language we're writing in |
| JAVA_HOME | A setting that tells tools which installed Java to use |
| Spring Boot | A popular toolkit that makes building Java server apps much faster. We use the newest version, 4.1. Very common in job listings |
| Maven / `mvnw` | Downloads the libraries we use and builds the app. `mvnw` fetches Maven for you, so there's nothing extra to install |
| Database / PostgreSQL | Where information is stored, like a very powerful spreadsheet |
| Flyway | Sets up and upgrades the database's tables, step by step |
| API / endpoint | A "door" other programs knock on to ask ForgetMe to do something |
| Status codes (200, 400, 409…) | Short answers from the app: 200 = "done", 204 = "done, nothing to say", 400 = "wrong input", 401 = "not allowed / bad stamp", 404 = "not found", 409 = "not allowed right now", 410 = "too late, expired" |
| Connector | A part of the company that holds personal data and can delete it (orders, photos, mailing list…) |
| Task | One connector's job for one request: "orders, please delete Alice" |
| Stage | A group of connectors that run together. Stage 2 starts only when stage 1 is finished |
| Dispatcher | The part of ForgetMe that sends the jobs out, like a manager working through a checklist |
| Tick | The Dispatcher waking up to check its list (every 5 seconds) |
| Callback / report | A connector phoning back to say "done" |
| Backoff | Waiting longer after each failed try (10 s, 20 s, 40 s…) so a struggling system isn't flooded |
| Lease | "This task is mine for the next minute." Stops two workers doing the same job, and frees the job if one crashes |
| HMAC / signature | A secret stamp so nobody can fake a message or a code |
| Encryption | Scrambling data so only someone with the key can read it |
| Hash | A one-way fingerprint: the same input always gives the same fingerprint, but you can't work backwards |
| Audit log | The diary of everything that happened to every request |
| Hash chain | Each diary line's seal includes the seal before it, so one change breaks every seal after it |
| Certificate | The receipt for a finished request |
| Subject fingerprint | What's left of a person's email after ForgetMe forgets it: enough to prove who was deleted, useless for anything else |
| Trigger | A tiny rule inside the database; ours refuses any edit to the diary |
| Advisory lock | A "one at a time, please" sign the database holds, so two diary lines are never written at the same instant |
| Row lock | Making requests for the same record wait their turn instead of all barging in at once |
| Race condition | Two things happening at the same instant and getting each other's timing wrong |
| Deadlock | Two workers each waiting for the other to go first, forever |
| Job queue | A to-do list the system works through, so nothing is forgotten if something crashes |
| `SKIP LOCKED` | A database trick so two workers never grab the same to-do item |
| Kafka | A big messaging system many companies use. We're *not* using it: the database's to-do list does the job with less to run |
| Idempotent | Doing something twice has the same effect as doing it once. Important because retries can repeat a message |
| Saga | A multi-step process across many systems. Ours only moves forward, since you can't "un-delete" |
| PII | "Personally identifiable information": names, emails, phone numbers |
| Docker / Docker Compose | Runs the database, fake inbox, ForgetMe and the pretend company on your computer with one command |
| Container / image | An *image* is a packed-up program with everything it needs; a *container* is one running copy of it |
| Dockerfile | The recipe Docker follows to build the images |
| Starter (plug-in) | A ready-made add-on for Spring Boot apps. Ours turns any app into a ForgetMe connector |
| Auto-configuration | Spring Boot noticing the plug-in and switching it on by itself |
| Profile | A named setting that changes how an app behaves. Our demo app is one program that plays four different systems depending on its profile |
| At-least-once | A job may arrive more than once, but never zero times. That's why deleting something already gone must count as success |
| Rate limit | A cap on how often someone can do something, to stop misuse |
| CSRF | A trick where another website makes your browser press a button on a site you're logged into. The admin page is protected against it |
| Thymeleaf | The tool that turns our data into the admin web pages |
| OpenAPI / Swagger | An automatically written list of everything the app's "doors" accept |
| Caddy | A small web server that sits in front and gets the HTTPS padlock automatically |
| HTTPS | The padlock in the browser: nobody in between can read what's sent |
| k6 | The tool that floods the app with fake traffic to measure its speed |
| WSL | Lets Windows run Linux in the background; Docker needs it |
| Mailpit | A fake inbox on your computer that catches test emails so none are really sent |
| Testcontainers | Starts a real, throwaway database just for the tests |
| CI (GitHub Actions) | Automatically runs all the tests every time the code changes |
| Deploy | Putting the app on the internet so anyone can try it |
| Load test | Flooding the app with fake traffic to measure how much it can handle |

## What this shows an employer

*"I can build a reliable system that coordinates many other systems, handles failures, keeps data secure and proves what happened."* That's the everyday job of a backend engineer, and most student projects don't show it.

Good interview stories so far (each phase guide has a short list you can use):
- why a wrong code must *not* cause an error (or the guess counter resets)
- why the to-do list is a database table and not Kafka
- how two simultaneous replies could have left a request stuck forever, and the lock that prevents it
- how the diary catches tampering even by someone with full database access, and why the times had to be cut to microseconds to avoid false alarms
- building a plug-in that other teams can adopt with one dependency and 5 lines, and a one-command demo anyone can run
- measuring it honestly: 66 confirmed requests a second, and about 400 people fully deleted from four systems per minute

There's a ready-made CV line in the [README](README.md#for-a-cv) you can copy once it's online.
