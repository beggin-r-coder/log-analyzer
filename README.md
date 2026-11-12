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

## Features

- **Persistent H2 database** (`./data/bfsi-logs.mv.db`): data survives restarts, with 7-day retention.
- **Live BFSI log stream** from 12 services (core banking, UPI switch, card auth, payment gateway, ATM switch,
  net banking, loan origination, KYC, fraud engine, treasury FX, insurance claims, mobile banking).
  Account numbers, cards and VPAs are masked. Traffic follows a banking-hours curve, with random incidents.
- **Universal log parser** that auto-detects JSON (including nested ECS), Spring Boot, Log4j/Logback, Python logging,
  logfmt, Nginx/Apache access, Apache error, syslog RFC 5424 / RFC 3164, pipe-delimited and bracketed formats,
  plus a generic fallback. Multi-line stack traces are folded into their event, and zoned timestamps are normalised.
- **Dashboard:** time ranges from 15 minutes to 7 days, and an error trend labelled in real local time (1 PM, 2 PM…).
  Buckets are aligned to the viewer's time zone. Click a chart, incident or service to drill into its logs.
- **Log explorer:** multi-word search (message, trace ID, host, IP, logger…), level, service and category filters,
  server-side sorting on every column, paging, live tail, the raw line on click, and CSV export.
- **Live log stream:** a real-time feed pushed over Server-Sent Events (`/api/logs/stream`) the moment a log is committed
  to H2. Each row shows time, day and date with Today / Yesterday / weekday labels, grouped by day. It supports pause,
  level and text filters, and a click for the raw line.
- **Customisable dashboard:** add, remove and reorder 20 widgets (8 KPIs, 8 charts, live feed and tables) from the
  Customize drawer. The layout is saved in the browser.
- **PDF export:** a professional multi-page A4 report with a cover page, executive summary, auto-generated key findings,
  a contents list with page numbers, high-resolution charts, colour-coded tables, and branded headers/footers with
  "Page X of Y". It follows the widgets you have visible.
- **Responsive:** phones get two-per-row KPIs, card-style log lists, a sort dropdown and full-screen panels;
  tablets pair cards two per row.
- **Login & production hardening:** session login with CSRF protection, a 401 for expired API sessions, a public
  health check, the H2 console off in production, a capped Gemini budget, and gzip with long-cached static files.
- **Demo controls:** pause or resume the live stream, and "Simulate incident".

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
GET  /api/logs/stream          (Server-Sent Events: "logs" events with newly stored records)
GET  /api/logs/{id}            GET /api/logs/facets          GET /api/logs/export?...
POST /api/logs/ingest?service=my-app   (text/plain body, any format; stored)
POST /api/logs/parse                   (preview only)
POST /api/simulator/pause | resume | incident?service=card-auth&minutes=5
```

```bash
curl -X POST --data-binary @app.log -H 'Content-Type: text/plain' "http://localhost:8080/api/logs/ingest?service=my-app"
```

Configuration lives in `src/main/resources/application.properties` (`logs.simulator.*`, `logs.retention-days`).

**Branding:** "NovaBank" is a fictional demo bank, so no real institution's name or brand is used. Change it with
`app.brand.name` and `app.brand.tagline`; the dashboard header, PDF cover, page headers and file name all follow.
