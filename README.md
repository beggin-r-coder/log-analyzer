# AI Log Analyzer: BFSI Observability Demo

A Spring Boot + H2 log analytics dashboard for a fictional bank (**NovaBank**).
Logs are written to a persistent H2 database, parsed from many real-world formats,
classified into BFSI incident categories, and shown on a live dashboard.

## Quick demo

**On your laptop** (needs Java 17+):

```bash
sh demo.sh
```

On Windows run `demo.cmd`. Then open http://localhost:8080.

- **Visitors:** click **Explore as guest** (read-only) and follow the 2-minute tour card on the dashboard.
- **You:** sign in as `admin` / `admin123` (change it with `APP_PASSWORD=... sh demo.sh`).
- **Error analysis** runs on the built-in NLP engine (pattern mining plus a BFSI failure playbook); nothing leaves the
  app. Gemini is switched off by default; see "AI error analysis" below to turn it on.

**Free online demo on Render:** New, then Blueprint, then pick this repository. `render.yaml` sets up a free
instance in demo mode. Enter an `APP_PASSWORD` for yourself. Free instances sleep
after about 15 idle minutes (the first visit then takes about a minute) and regenerate demo data after restarts. The
settings were tested in a container limited to 512 MB, the free plan's memory.

**What the guest can and cannot do:** browse everything, simulate incidents, run the error analysis and export
PDFs. Only the admin can pause the live stream for everyone,
store logs from the parser, and open the H2 console.

**5-minute demo script**

1. Log in as guest; point out the KPIs with change vs the previous period and the live stream.
2. Tour step 1, **Simulate an incident**: the UPI switch starts failing.
3. Wait about a minute, then step 2, **Last 15 minutes**: errors spike, the banner appears and health drops.
4. Step 3, **Analyze the errors**: NLP groups them into patterns; root cause, a recovery checklist and incident cards.
5. Step 4, **Open one error**: its pattern and frequency, similar errors, the trace, recovery steps and **Copy as ticket**.
6. The **Parser** tab: **Load samples**, then **Parse** to show 12 log formats detected automatically.
7. Step 5, **Export the PDF report**; then open the site on your phone.

## Presentation mode

Click **Present** in the top bar (or press **P**) for a full-screen, guided 2-minute story built for pitches and
hackathons. It uses scripted, realistic figures for a large retail bank at peak hour, so it looks the same every time
and never touches the real data or the incident list:

1. **Welcome** and **The problem**: 42.7 million log lines a day, with a scanning beam over a wall of mixed-format logs.
2. **Live system**: an animated service map with transactions flowing between 12 services.
3. **Incident**: the UPI switch fails; an 8× time-lapse clock, a climbing error counter, a failure-rate gauge and
   NPCI errors streaming in.
4. **Detection**: detected in 38 seconds; INC-2481 opens and the on-call engineer is paged and acknowledges.
5. **Business impact**: ₹2.66 Cr of payments at risk, 14,382 failed transactions, the monthly SLA budget burning.
6. **Diagnosis**: errors collapse into one pattern, linked to a firewall change three minutes earlier, with a recovery plan.
7. **Resolution**: the timeline fills in and recovery time is compared with the previous 47-minute average.
8. **Why it matters** and **Thank you**.

It plays on its own with narration captions. Use `←` `→` to move, `Space` to pause, `N` for captions, `F` for full
screen and `Esc` to exit. Add `#present` to the dashboard address to open it straight away. It works in the GitHub
Pages demo too.

## Browser-only demo (GitHub Pages)

`docs/` holds a copy of the dashboard that runs entirely in the browser, for networks where app hosts are blocked.
It uses the real pages, CSS and JavaScript; `docs/js/demo-backend.js` answers the API calls in the browser with a port
of the backend (log generator, universal parser, classifier, dashboard aggregations, search, NLP analysis), using
data exported from the Java classes. Charts and PDF libraries are bundled, so it works without CDNs. Data is
simulated per browser tab; nothing is sent anywhere.

**Publish:** Settings, then Pages, then "Deploy from a branch", then pick the branch and the `/docs` folder, then Save.
The site appears at `https://<user>.github.io/log-analyzer/`.

**Rebuild after changing the app:**

```bash
python3 scripts/static-demo/build.py
```

## Run locally

```bash
./mvnw spring-boot:run
```

Open http://localhost:8080 and sign in. With no `APP_PASSWORD` set, a random password is generated and printed in the
console at startup (look for "Generated login for this run"). To choose your own:

```bash
APP_PASSWORD=your-password ./mvnw spring-boot:run
```

On first start, 7 days of demo history (about 100k events) are generated in the background; after that new logs
stream in every 2 seconds.

| URL | What |
|---|---|
| `/` | Live dashboard |
| `/parser` | Parser playground |
| `/h2-console` | H2 console (local only, turned off in production). JDBC URL `jdbc:h2:file:./data/bfsi-logs`, user `sa`, empty password |
| `/actuator/health` | Health check for hosting platforms (public) |

## Deploy

The app ships as a Docker image that runs with the `prod` profile: the H2 console is off, cookies are HTTPS-only, it
runs as a non-root user, and the database lives in `/app/data` (mount a volume there or data is lost on redeploy).

### Docker / any VPS

```bash
cp .env.example .env
```

Edit `.env` and set at least `APP_PASSWORD`. Then:

```bash
docker compose up -d --build
```

The app is now on port 8080. Put it behind HTTPS (e.g. Caddy or Nginx with Let's Encrypt) and set
`SECURE_COOKIES=true` once it is served over HTTPS.

### Render

New, then Blueprint, then pick this repository. `render.yaml` defines the service, the health check and a 1 GB disk
for the database. Enter `APP_PASSWORD` (and optionally `GEMINI_API_KEY`) when asked. A persistent disk needs a paid
plan; on the free plan the demo data is regenerated after each restart.

### Railway (all in the browser)

1. Sign in at [railway.com](https://railway.com) with GitHub.
2. **New Project**, then **Deploy from GitHub repo**, then pick `log-analyzer`. Railway finds `railway.toml` and the
   `Dockerfile`.
3. Open the service, then **Settings > Source**, and set the branch to the one with this code (until it is merged
   into `main`).
4. **Variables** tab, add:

   | Variable | Value |
   |---|---|
   | `DEMO_MODE` | `true` |
   | `APP_PASSWORD` | a password for yourself |
   | `SEED_DAYS` | `2` |
   | `JAVA_OPTS` | `-Xmx256m -XX:MaxMetaspaceSize=128m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError` |

5. **Settings > Networking > Generate Domain**. Open the `...up.railway.app` link and click **Explore as guest**.

Railway sets `PORT` itself. Without a volume, demo data is regenerated after each redeploy, which is fine for a demo.

### Fly.io (needs the `fly` command-line tool)

Install `flyctl` from [fly.io/docs/flyctl/install](https://fly.io/docs/flyctl/install/), then in the project folder:

```bash
fly launch --copy-config --no-deploy
```

Accept the suggested unique app name; it is written into `fly.toml`.

```bash
fly secrets set APP_PASSWORD=your-password
```

```bash
fly deploy --ha=false
```

`--ha=false` keeps a single machine, which this app needs (the H2 database and logins live on one instance). The app
sleeps when idle and wakes on the next visit (a few seconds' delay). Open `https://<your-app>.fly.dev`.

### Other hosts

Deploy from the `Dockerfile`, set `APP_PASSWORD` (and `DEMO_MODE=true` for a demo), run a single instance, use
`/actuator/health` as the health check, and optionally attach a volume at `/app/data`. The port is read from `PORT`.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `APP_USERNAME` / `APP_PASSWORD` | `admin` / generated | Dashboard login |
| `AI_ENABLED` | `false` | Use Gemini for the error analysis (NLP-only when false) |
| `GEMINI_API_KEY` | empty | Gemini key, used only when `AI_ENABLED=true` |
| `GEMINI_MODEL` | `gemini-3.8-flash` | Gemini model |
| `AI_MAX_CALLS_PER_HOUR` | `60` | Cap on paid Gemini calls; beyond it the offline analysis is used |
| `PORT` | `8080` | HTTP port |
| `DATA_DIR` | `./data` (`/app/data` in Docker) | Where the H2 database file is stored |
| `BRAND_NAME` / `BRAND_TAGLINE` | `NovaBank` / `BFSI Observability` | Branding |
| `SIMULATOR_ENABLED` | `true` | Generate demo logs |
| `SEED_DAYS` / `SIMULATOR_RATE` | `7` / `12` | Demo history length and events per minute |
| `RETENTION_DAYS` | `7` | Older logs are purged hourly |
| `SECURE_COOKIES` | `true` in prod | HTTPS-only session cookies |
| `H2_CONSOLE_ENABLED` | `true` locally, `false` in prod | H2 web console |
| `APP_SECURITY_ENABLED` | `true` | Set to `false` only on a private network |
| `NOTIFY_WEBHOOK_URL` | empty | Slack or Microsoft Teams incoming webhook; new and resolved incidents are posted there |
| `DASHBOARD_URL` | empty | Public dashboard address, added as a link to notifications |
| `SLA_TARGETS` | `upi-switch:99.5,core-banking:99.9,…` | Availability target per service (percent) |
| `SLA_DEFAULT` | `99.0` | Target for services not listed in `SLA_TARGETS` |
| `INCIDENT_EVALUATE_MS` | `20000` | How often alert rules are evaluated |

## Features

### For managers: business impact and incidents

- **Business Impact:** an estimate of the money at risk (₹, shown in lakh / crore), failed customer transactions and
  customers affected in the selected range, with the services where it failed. Amounts are read from the failed
  events where logged, otherwise from the median successful ticket size of that service; retries of the same
  transaction or customer are counted once. "How it's estimated" shows the method.
- **SLA & error budget:** availability per service in 5-minute slots against its target (UPI 99.5%, core banking
  99.9%, …), minutes of downtime, error budget used and a Met / At risk / Breached status.
- **Incident workflow:** alert rules (error rate or error count over a window, per service or for all) run every
  20 seconds. A breach opens an incident, routed to the owning on-call team (e.g. `payments-upi`), with the latest error and
  a suggested first step from the failure playbook. People **acknowledge**, add **notes** and **resolve** it. Each incident
  has a timeline, its own business impact, links to its logs and to error analysis, and "Copy summary" for a
  post-incident report. Incidents close by themselves after 10 healthy minutes. The incidents the rules would have raised
  are reconstructed from stored history on first start, so MTTA / MTTR are meaningful straight away.
- **Alert rules editor** (admins; viewers can read the rules), a nav badge with the active incident count, a toast when a new
  incident opens, and an optional Slack / Teams webhook.
- All of this is in the PDF report: impact figures, the SLA table and the incident list, plus key findings on the cover.

### Platform

- **Persistent H2 database** (`./data/bfsi-logs.mv.db`): data survives restarts, with 7-day retention.
- **Live BFSI log stream** from 12 services (core banking, UPI switch, card auth, payment gateway, ATM switch,
  net banking, loan origination, KYC, fraud engine, treasury FX, insurance claims, mobile banking).
  Account numbers, cards and VPAs are masked. Traffic follows a banking-hours curve, with random incidents.
- **Universal log parser** that auto-detects Oracle Siebel CRM logs, JSON (including nested ECS), Spring Boot, Log4j/Logback, Python logging,
  logfmt, Nginx/Apache access, Apache error, syslog RFC 5424 / RFC 3164, pipe-delimited and bracketed formats,
  plus a generic fallback. Multi-line stack traces are folded into their event, and zoned timestamps are normalised.
- **Dashboard:** time ranges from 15 minutes to 7 days, and an error trend labelled in real local time (1 PM, 2 PM…).
  Buckets are aligned to the viewer's time zone. Click a chart, incident or service to drill into its logs.
- **Log explorer:** multi-word search (message, trace ID, host, IP, logger…), level, service and category filters,
  server-side sorting on every column, paging, live tail, the raw line on click, and CSV export.
- **Live log stream:** a real-time feed pushed over Server-Sent Events (`/api/logs/stream`) the moment a log is committed
  to H2. Each row shows time, day and date with Today / Yesterday / weekday labels, grouped by day. It supports pause,
  level and text filters, and a click for the raw line.
- **Customisable dashboard:** add, remove and reorder 24 widgets (8 KPIs, 3 business widgets, 8 charts, live feed and tables) from the
  Customize drawer. The layout is saved in the browser.
- **PDF export:** a professional multi-page A4 report with a cover page, executive summary, auto-generated key findings,
  a contents list with page numbers, high-resolution charts, colour-coded tables, and branded headers/footers with
  "Page X of Y". It follows the widgets you have visible.
- **Responsive:** phones get two-per-row KPIs, card-style log lists, a sort dropdown and full-screen panels;
  tablets pair cards two per row.
- **Login & production hardening:** session login with CSRF protection, a 401 for expired API sessions, a public
  health check, the H2 console off in production, a capped Gemini budget, and gzip with long-cached static files.
- **Demo controls:** pause or resume the live stream, and "Simulate incident".

## Oracle Siebel CRM logs

The parser understands Siebel component, server and web server extension (SWSE) logs, as found under
`siebsrvr/enterprises/<ENTERPRISE>/<SERVER>/log`:

```
2021 2026-11-04 13:05:01 0000-00-00 00:00:00 +0530 00000000 001 003f 0001 09 SCCObjMgr_enu 2097155 5360 4940 d:\siebel\log\SCCObjMgr_enu_0020_2097155.log 8.1.1.11 [23030] ENU
ObjMgrSessionInfo	ObjMgrLogin	3	0000000257c41c8c:0	2026-11-04 13:05:02	Login name : AGENT0421
SQLError	Error	1	000000025a1c1a24:0	2026-11-04 13:05:06	SBL-DBC-00105: An error has occurred executing a Sql statement.
[DataDirect][ODBC Oracle Wire Protocol driver][Oracle]ORA-12170: TNS:Connect timeout occurred
```

- **File header:** the component (e.g. `SCCObjMgr_enu`) becomes the service, the task id the thread, and event times are
  converted from the header's UTC offset.
- **Events:** `EventType<TAB>SubType<TAB>Severity<TAB>SARM ID<TAB>Timestamp<TAB>Message`. Tabs or runs of spaces are both
  accepted. The SARM ID is stored as the trace ID, so all events of one user request are linked.
- **Levels:** severity 0-5 maps to FATAL / ERROR / WARN / INFO / DEBUG / TRACE. Siebel writes routine server events
  (ProcessCreate, LstnObjCreate…) at level 1, so 0-1 only count as errors when the event type or text says so: an
  `SBL-xxx-nnnnn` code, `err=`, `ORA-`, "failed", "terminated" and similar. "was terminated", "crash" and "core dump" are FATAL.
- **Multi-line events:** SQL statements, bind variables and ODBC driver messages stay with the event they belong to.
- **Categories:** Siebel Database Connector (SBL-DBC), EAI & Integration (SBL-EAI), Workflow (SBL-BPR / SBL-OMS),
  Server & Components (SBL-SVR / SMI / NET, crashes), Login & Security (SBL-SEC), Data Validation (SBL-DAT), and
  other SBL errors.
- **Analysis:** error patterns keep `SBL-` and `ORA-` codes and mask Siebel row ids (`1-ABC123`). Entities include
  Siebel error codes, row ids and object names (business services, business components, web services, workflows).
  The playbook has Siebel-specific root causes and recovery steps.

The Parser page has a **Siebel sample** button, and the simulator includes three Siebel components (Call Center and EAI
object managers, Workflow Process Manager).

## AI error analysis (Google Gemini + NLP)

- **AI Error Analysis panel:** a summary, probable root cause, impact, severity with confidence, step-by-step recovery
  (a checklist), prevention and per-incident cards for all errors in the selected range.
- **Single-error analysis:** click **Analyze** on any log in the Log Explorer or Live Stream. A side panel shows the
  NLP context instantly: the error pattern, how often it occurred in 24h, extracted entities, similar errors
  (TF-IDF) and related events with the same trace ID. One click asks Gemini for root cause and recovery steps.
  **Copy as ticket** produces Markdown for Jira/ServiceNow.
- **Local NLP in plain Java:** template mining groups errors into patterns, so Gemini receives about 15 counted
  patterns instead of thousands of lines. It also does entity extraction, TF-IDF keywords and similarity. Without an
  API key, an offline playbook of BFSI failure modes is used instead, so the feature still works.
- **Safety:** emails, card numbers (Luhn-checked), Aadhaar, PAN, phone numbers, client IPs, tokens and passwords are
  redacted before anything leaves the app. Log text is marked as untrusted in the prompt. Every analysis is stored in
  the `ai_analyses` table for audit, and repeat requests reuse it instead of paying for another call.

### Setup

Gemini is **off by default** (`AI_ENABLED=false`): the analysis then uses only the local NLP engine and the offline
playbook, and the UI does not mention AI. To turn Gemini on:

1. Create a key at [Google AI Studio](https://aistudio.google.com/apikey).
2. Set it as an environment variable together with the switch (never put the key in `application.properties` or
   commit it):

```bash
export AI_ENABLED=true GEMINI_API_KEY="your-key"
```

On Windows PowerShell use `$env:GEMINI_API_KEY="your-key"`.

3. Optionally choose another model: `export GEMINI_MODEL=gemini-3.5-flash-lite` (cheaper). The default is
   `gemini-3.8-flash`.
4. Start the app. The panel's chip changes from "Offline NLP mode" to "Gemini · model".

## REST API

```
GET  /api/dashboard?range=24h&tz=330
GET  /api/logs?q=npci timeout&levels=ERROR,FATAL&service=upi-switch&sort=timestamp&dir=desc&page=0&size=50
POST /api/ai/overview?range=24h      POST /api/ai/logs/{id}      GET /api/ai/logs/{id}/context      GET /api/ai/status
GET  /api/logs/stream          (Server-Sent Events: "logs" with newly stored records, "incident" on incident changes)
GET  /api/logs/{id}            GET /api/logs/facets          GET /api/logs/export?...
POST /api/logs/ingest?service=my-app   (text/plain body, any format; stored)
POST /api/logs/parse                   (preview only)
POST /api/simulator/pause | resume | incident?service=card-auth&minutes=5[&burst=2] | incident/stop
GET  /api/impact?range=24h                     (money at risk, failed transactions, customers, SLA, MTTA/MTTR)
GET  /api/incidents?status=active|all|resolved&range=7d      GET /api/incidents/{id}   (with timeline and impact)
POST /api/incidents/{id}/ack     POST /api/incidents/{id}/resolve {"note": "..."}     POST /api/incidents/{id}/notes {"text": "..."}
POST /api/incidents/evaluate[?force=true]       (run the alert rules now)
GET  /api/alert-rules            POST/PUT/DELETE /api/alert-rules[/{id}]   (admin only)
```

```bash
curl -X POST --data-binary @app.log -H 'Content-Type: text/plain' "http://localhost:8080/api/logs/ingest?service=my-app"
```

Configuration lives in `src/main/resources/application.properties` (`logs.simulator.*`, `logs.retention-days`).

**Branding:** "NovaBank" is a fictional demo bank, so no real institution's name or brand is used. Change it with
`app.brand.name` and `app.brand.tagline`; the dashboard header, PDF cover, page headers and file name all follow.
