# bizsim — management simulation

Multi-tenant web simulation for management training: teams run a virtual company over
facilitator-driven rounds, submit decisions, get a deterministic score, and the trainer exports a
debrief.

## Layout

```
backend/                  Spring Boot 3.3, Java 21
  src/main/java/com/bizsim/
    auth/                 JWT issue/verify + the auth filter
    tenant/               TenantContext (the only source of tenant identity)
    user/                 tenant-scoped user repository
    web/                  /api/ping, /api/me
  src/main/resources/db/migration/   Flyway migrations (V1 = ten core tables)
web/                      React 19 + TypeScript + Vite (sprint S3 onward)
docs/DECISIONS.md         the rulings the code must obey
docs/MIGRATIONS.md        schema rules, failure runbook, manual CLI commands
```

## Run it

Requires JDK 21 and Maven. The backend needs PostgreSQL; a throwaway one is in
`docker-compose.yml` (port 5433).

```bash
docker compose up -d db          # optional: a local PostgreSQL on 5433
cd backend
mvn spring-boot:run              # creates the database if missing, then migrates the schema
curl -s localhost:8080/api/ping
```

Starting the backend is all that is needed to get a working schema:

1. **Database** — if the database in `BIZSIM_DB_URL` does not exist, it is created on startup
   (`DatabaseBootstrap`, connects to the `postgres` maintenance database). Set
   `BIZSIM_DB_CREATE=false` where the application role has no `CREATEDB` right.
2. **Schema** — Flyway applies `db/migration/V*.sql` in order on every startup and records what it
   applied in `flyway_schema_history`. On an existing database it applies only what is new; on a
   fresh one it creates every table, index and constraint.
3. **Data** — lives in PostgreSQL, not in the application. Restarting the backend never recreates or
   empties a table. There is no `ddl-auto`: the schema only changes through a reviewed migration, so
   a deploy cannot silently alter a table that holds a class's history.

Configuration is environment-only:

| Variable | Purpose | Default |
|---|---|---|
| `BIZSIM_DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5433/bizsim` |
| `BIZSIM_DB_USER` / `BIZSIM_DB_PASSWORD` | credentials | `bizsim` / `bizsim` |
| `BIZSIM_DB_CREATE` | create the empty database on first start | `true` |
| `BIZSIM_JWT_SECRET` | HMAC key, **≥ 32 bytes** | dev-only value, refuses to start if short |
| `BIZSIM_PORT` | HTTP port | `8080` |

### No Docker, no PostgreSQL install

```bash
scripts/dev-up.sh      # real PostgreSQL from the Maven test dependency, then the backend
scripts/smoke.py       # drives a whole session against the running backend
scripts/dev-down.sh    # stops both
```

`dev-up.sh` starts PostgreSQL on 5433 from the test-scoped Zonky binaries and the application on 8080
with the `dev` profile, which seeds a demo tenant and three accounts: a facilitator, a player and an
admin, all sharing one demo password (see `DevSeeder` for the addresses).

`smoke.py` exercises the real API end to end: login, create a three-company run with two cycles, open
a round, read the decision form context, see the market report refused with 402 and then bought,
submit a decision, get an over-capacity plan rejected with a worker count, close the round, print the
scoreboard and the line-item statement, and prove a double close computes nothing twice.

## Facilitator console (`web/`)

```bash
scripts/dev-up.sh     # backend + database
scripts/dev-web.sh    # console on 0.0.0.0:5173 (installs deps on first run)
npm run build         # static bundle in web/dist, served by any static host
```

Opening it from another machine: the console binds to all interfaces, so use the host's address
(`http://<host>:5173`) or, if the port is not exposed, tunnel it —
`ssh -L 5173:localhost:5173 <user>@<host>` and then open `http://localhost:5173`.

**Single port instead.** Build the console and start the backend with the directory; it then serves
the UI at `/` on the same port as the API — no second server, no proxy, no CORS:

```bash
cd web && npm run build && cd ..
BIZSIM_WEB_DIR=$PWD/web/dist BIZSIM_PORT=8080 mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev
# or: java -jar backend/target/bizsim-backend-*.jar --spring.profiles.active=dev --bizsim.web.dir=$PWD/web/dist
```

If the directory does not exist, the backend runs API-only and behaves exactly as before.

Sign in with the seeded facilitator account, create a run (companies and cycles are chosen there),
then drive the session with **Open next round** and **Compute round**. **Delete run** removes a
simulation and everything played in it — the way to clear a rehearsal before the real class. The
scoreboard refreshes from a server-sent event stream, so a second browser on the same run sees the
same round land within a second.

A trainee signs in with the player account and gets the **decision form** instead of the console:
production and prices per product, marketing per channel, production staff and machines, R&D
investment per project, and the paid market review. The same email address always sees the same
screen, decided by the role in the token. A trainee can read only their own company — asking for
another company's context, statement or market report is a 403.

The interface is available in **English** and **中文**: the language switch sits next to *sign out*
and the choice is stored on the account (tenant default when unset, D23). Only interface chrome is
translated — template content such as product or project names is shown exactly as authored.

Trainee accounts are made in **People** (builder): name, email, initial password. The builder then
seats each one in the package's **class list**, one person per company, and publishes. The facilitator
only starts a session from that package — there is no seating step during a class (D33), so a seat
left open stays open. Hand the trainee the email and password; there is no email delivery in this
product.

## Deploy

Dokploy (one image, one port, TLS at the proxy): see [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## Test

```bash
cd backend && mvn test
```

Tests run against a real PostgreSQL started in-process (Zonky embedded), so `jsonb`, identity
columns and the Flyway migrations are exercised as they will run in production. No Docker and no
root are required.

## API so far (S0 → S2)

```
GET  /api/ping                          no auth
POST /api/auth/login                    {tenantCode, email, password} -> {token, ...}
GET  /api/me                            Bearer token -> caller identity

POST /api/runs                          create a run: 1..10 companies, optional rounds/cycles
GET  /api/runs/{id}                     run, companies, players, current round
POST /api/runs/{id}/teams               add a company (OPEN runs only)
POST /api/runs/{id}/players             assign a player to a company
POST /api/runs/{id}/rounds/next         facilitator: close + compute, then open the next month
POST /api/runs/{id}/rounds/close        facilitator: close + compute without opening the next

POST /api/runs/{id}/teams/{tid}/decisions   player: submit the month (upsert per round)
GET  /api/runs/{id}/teams/{tid}/context     form data; R&D shows a cost band, never the hidden cost
GET  /api/runs/{id}/teams/{tid}/statements  line-item statements, one per round
GET  /api/runs/{id}/teams/{tid}/market-report    paid forecast; 402 until bought (D18)
GET  /api/runs/{id}/scoreboard              KPIs per company per round
POST /api/runs/{id}/rounds/clock/pause      facilitator: stop the round clock (D12)
POST /api/runs/{id}/rounds/clock/resume     facilitator: restart it, returning the stopped time
POST /api/runs/{id}/rounds/clock/extend     facilitator: {minutes} added to the deadline
GET  /api/runs/{id}/decisions               facilitator: every company's submission, or not
POST /api/runs/{id}/teams/{tid}/cash        facilitator: {delta, reason}; audited (D12)
POST /api/runs/{id}/rounds/void             facilitator: {reason}; undo the last computed round
GET  /api/runs/{id}/audit                   facilitator: the session's hand-changes, in order
GET  /api/runs/{id}/debrief/export          facilitator: ?round=&format=pdf|xlsx (D13)
```

Every other path returns 401 without a valid token — including unknown paths, so an unauthenticated
caller cannot map the API.

## Status

S0 complete: schema baseline, tenant-scoped auth, CI.
S1 complete: run/company/player management, round state machine.
**S2 complete (2 Nov 2026): the deterministic scoring engine.**
  `V3__simulation_model.sql` adds the product/channel/R&D catalogue and per-company state;
  `ScoringEngine` runs the month in a fixed order (produce → cost → demand → sell → R&D → brand →
  cash); `round_statement` keeps the line items the debrief needs. A round can be closed by the
  facilitator or by its own clock, and closing twice computes nothing twice.
  Scenario #1 is provisional and calibrated in `docs/SCENARIO_CALIBRATION.md` — the discount
  strategy wins share and loses profit, which is the lesson D14 asked for.
  64 tests, all against real PostgreSQL.
**S3 complete: live channel + facilitator console.** SSE with single-use tickets (D20); the console
  creates a run, opens and computes rounds, and shows one scoreboard per round (D21).
**S4 complete (3 Nov 2026): the trainee decision form.** `web/src/PlayerConsole.tsx` is what a
  `PLAYER` sees after sign-in: production and price per product, marketing per channel, production
  staff and machines, R&D per project, the paid market review, and a live cash-commitment line. The
  server returns the trainee's stored decision with the form so a reload reopens their own numbers,
  and a trainee may read only their own company (403 otherwise).
**S5 complete (3 Nov 2026): the debrief** — per-round statements and the class report on the
facilitator's screen (details below).
**S6a/S6b complete (3 Nov 2026): setup → templates.** `GET /api/templates` lists the global
  `standard-v1` plus the tenant's own copies; `POST /api/templates/{key}/copy` clones a template —
  definition document and every catalogue row — into a tenant-owned `DRAFT` with its own key
  (`demo-standard-v1`). The console has a **Templates** tab for it. Ruling D22: the shipped template
  is never edited, only copied.
**S6c complete (3 Nov 2026): the template editor.** Clicking a template opens the editor; it now has
  six tabs — **Settings** (all 22 keys, including per-function salaries and headcount),
  **Environment**, **Events**, **Products**, **Channels**, **R&D projects**. Settings save merges the editable scalars into the definition document, leaving
  the engine's calibration alone; the environment is a table of round positions saved whole. Only a
  copy the tenant owns is editable — the shipped template answers `409 template.read_only`. See
  `docs/SETUP_WORKBENCH.md` for the phase list.
**S6d complete (3 Nov 2026): the catalogue.** Products, Channels and R&D projects are editable tabs
  next to Settings and Environment, saved whole. Validation refuses what the engine cannot run
  (unknown advantage type, missing target product, non-positive price); a template that already has a
  simulation refuses catalogue edits (`409 template.in_use`) because its companies and decisions name
  those codes.
**D24 (3 Nov 2026): building and running are separate jobs.** A `BUILDER` prepares templates,
companies and packages; a `FACILITATOR` runs sessions from published packages and cannot edit them; an
`ADMIN` does both. A **package** is the immutable boundary — one template version plus the companies
that compete — published by a builder, consumed by a facilitator, retired rather than deleted, and
recorded on every run it produced (`run.package_id`). Publishing **freezes** the template into a
private immutable copy, so editing a draft afterwards cannot change a class that was already
prepared — and re-publishing makes a new package rather than rewriting an old one. Starting a
session is the only execution path exposed to facilitators; `POST /api/runs` (a run without a
package) is a builder's tool and requires `?facilitatorId=` — the run belongs to the facilitator who
will drive it, because a builder never does. The console shows each role only its own
workspace, and the API refuses the other direction rather than relying on a hidden button.

**S5 complete (3 Nov 2026): the debrief.** The facilitator's run view now carries a **Debrief** card:
pick a round, see one row per company (revenue, cost, profit, cash, share, lost sales, capacity used)
with the cost broken into its charged lines, plus a Class footer. It reads `round_statement` for that
round — the debrief cannot disagree with what the engine charged. Builders also get **Tidy up** in
Packages: retired packages that never ran a session, removable with a confirmation.

**S6e complete (3 Nov 2026): the workbench closes the loop.** A **Companies** tab holds reusable
  company profiles (name + which products of a template it carries; the shipped one is read-only,
  copy it to adapt). A run is now started from what you prepared: **Start a session** → template,
  tick companies, tick trainees → the run is created with those companies carrying their own product
  lines and the trainees already seated. Validation happens before anything is written, so a mismatch
  is a refusal rather than a half-built session.

**D47–D50 complete (5–6 Nov 2026): auto workers.** A company can be played by a policy instead of
a person — an identity (`AUTO_WORKER`), a versioned brain (`bot_profile`: level × personality) and a
seat (`player.bot_profile_id`), all behind a per-template switch that is off by default. The policy
runs through the same submission path as a trainee and can only see what a trainee sees; the
self-play harness proves `high > mid > low` and reproduces a run exactly. All six slices are built:
the builder's **Auto workers** library and seating a brain in a package's class list, `BotRoundService`
submitting during the round (a five-second sweep, seeded 20–70% of the template's round length),
`GET /runs/{id}/bots`, `POST /runs/{id}/bots/decide` (recorded `MANUAL`) and
`POST /runs/{id}/bots/{team}/replay`, plus the user guide (§3.5, §7.2.2). Nothing seats a bot unless
a template turns `autoWorkers` on **and** a package's class list names a brain — two deliberate
switches. Design: [docs/AUTOWORKER.md](docs/AUTOWORKER.md).

**D39/D40/D41 complete (5 Nov 2026): the class day, and the room after it.** The facilitator's four
missing powers are built: the round clock can be paused, resumed and extended (a paused clock never
closes the round; resuming returns exactly the stopped time); every company's submission is readable
while the round is open; a company's cash can be moved by hand with a mandatory, audited reason; and
the last computed round can be **voided** — every company restored from a snapshot written at compute
time, that round's results deleted, the round reopened, the run un-finished. Everything lands in
`facilitator_action`, so a class can ask why a number changed and get an answer. **Quality upgrade**
(D15) is now a playable mechanic, priced per point and switched on per template. The debrief leaves
the screen: **Present** for the projector (six KPIs, then the explanation), **Download PDF** (an
embedded CJK font, so authored Chinese names render) and **Download Excel**.

