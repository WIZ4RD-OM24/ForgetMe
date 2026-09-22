# ForgetMe, explained simply

*This file explains the project in plain English. It's updated every time the project changes.*
*For how each step works in detail (the code, the tools, the reasons), see the phase guides: [phase 1](docs/phase-1.md) · [phase 2](docs/phase-2.md).*

## Where we are right now

**Steps 1 and 2 of 5 are done.**
- **Step 1:** someone can ask to be deleted and prove it's them with an emailed code. *(Tried by hand ✅)*
- **Step 2:** once confirmed, ForgetMe contacts every part of the company that holds their data, in the right order, keeps retrying the ones that fail, and finishes when everyone has reported back.

**All 14 automatic checks pass.**
**The code is on GitHub** at https://github.com/WIZ4RD-OM24/ForgetMe. It's private for now, so only you can see it. Make it public when you want to show it on your resume.
**Next:** Step 3, the proof: a receipt at the end, a diary nobody can secretly edit, and deadline warnings.

---

## What are we building?

A tool that helps a company **completely delete a customer's data when the customer asks**, and **prove** it did.

## The simplest way to picture it

Imagine you're moving out of a city. You have to tell *everyone*: the bank, the gym, the phone company, the electricity company. Some reply right away. Some ignore you, so you call again. Some say "we have to keep your tax papers for 8 years." At the end you want **a receipt** from each one.

ForgetMe is that **moving-out helper**, but for a company's computer systems. A customer says "forget me," and ForgetMe:
1. makes sure it's really them, *(Step 1 ✅)*
2. contacts every part of the company that holds their information, *(Step 2 ✅)*
3. keeps chasing the ones that don't answer, *(Step 2 ✅)*
4. hands back a receipt proving everything was handled. *(Step 3)*

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

### Why the order matters

The main customer account is like **your contact list**. It holds the email, phone number and IDs that the other parts need to find the right data. Delete the contact list first and you can't reach anyone else. So:

1. **First:** stop anything still happening (stop marketing emails, block login)
2. **Then:** clean up everywhere else, all at once
3. **Last:** delete the main account
4. **Finally:** ForgetMe forgets too *(Step 3)*. It deletes its own copy of the customer's details and keeps only a scrambled fingerprint.

## The plan, step by step

| Step | What you'll be able to show |
|---|---|
| 0 ✅ | The plan (these documents) |
| 1 ✅ | Someone can ask to be deleted and confirm with an email code |
| 2 ✅ | ForgetMe contacts every part of the company in order, and keeps retrying the ones that fail |
| 3 | A receipt at the end, a tamper-proof diary, and warnings when a deadline is close |
| 4 | A pretend company (4 tiny apps) to demo on, plus a plug-in so other programmers can connect their apps in 5 lines |
| 5 | It's live on the internet, tested, with a simple admin page and a short demo video |

## Try it yourself

**Before you start:** open Docker Desktop and wait until it says it's running.
*If it gets stuck on "starting"* (this happened once, right after installing): quit it fully (right-click the whale icon near the clock → Quit), then open it again.

**Start everything** (in PowerShell, inside the project folder):
```powershell
docker compose up -d
```
```powershell
.\mvnw.cmd -pl orchestrator spring-boot:run
```

Then follow the "try it" section of the phase guide you want to see: [phase 1](docs/phase-1.md) (ask and confirm) or [phase 2](docs/phase-2.md) (watch ForgetMe contact a connector and retry).

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
| Row lock | Making requests for the same record wait their turn instead of all barging in at once |
| Race condition | Two things happening at the same instant and getting each other's timing wrong |
| Deadlock | Two workers each waiting for the other to go first, forever |
| Job queue | A to-do list the system works through, so nothing is forgotten if something crashes |
| `SKIP LOCKED` | A database trick so two workers never grab the same to-do item |
| Kafka | A big messaging system many companies use. We're *not* using it: the database's to-do list does the job with less to run |
| Idempotent | Doing something twice has the same effect as doing it once. Important because retries can repeat a message |
| Saga | A multi-step process across many systems. Ours only moves forward, since you can't "un-delete" |
| PII | "Personally identifiable information": names, emails, phone numbers |
| Docker / Docker Compose | Runs the database and fake inbox on your computer with one command |
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
