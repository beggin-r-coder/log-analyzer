# AI Log Analyzer: BFSI Observability Demo

A Spring Boot + H2 log analytics dashboard for a fictional bank (**NovaBank**).
Logs are written to a persistent H2 database, parsed from many real-world formats,
classified into BFSI incident categories, and shown on a live dashboard.

## Run

```bash
./mvnw spring-boot:run
```

Open http://localhost:8080. On first start, 7 days of history (~100k events) are generated in about 10 s.
After that, new logs stream in every 2 seconds.

| URL | What |
|---|---|
| `/` | Live dashboard: KPIs, health score, error trend, categories, incidents, service health, log explorer |
| `/parser` | Parser playground: paste any logs and see how they are detected and classified |
| `/h2-console` | H2 console (JDBC URL `jdbc:h2:file:./data/bfsi-logs`, user `sa`, empty password) |

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
- **Demo controls:** pause or resume the live stream, and "Simulate incident".

## REST API

```
GET  /api/dashboard?range=24h&tz=330
GET  /api/logs?q=npci timeout&levels=ERROR,FATAL&service=upi-switch&sort=timestamp&dir=desc&page=0&size=50
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
