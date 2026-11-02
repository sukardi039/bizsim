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

POST /api/runs                          create a run with 1..10 companies
GET  /api/runs/{id}                     run, companies, players, current round
POST /api/runs/{id}/teams               add a company (OPEN runs only)
POST /api/runs/{id}/players             assign a player to a company
POST /api/runs/{id}/rounds/next         facilitator: close + compute, then open the next month
POST /api/runs/{id}/rounds/close        facilitator: close + compute without opening the next

POST /api/runs/{id}/teams/{tid}/decisions   player: submit the month (upsert per round)
GET  /api/runs/{id}/teams/{tid}/context     form data; R&D shows a cost band, never the hidden cost
GET  /api/runs/{id}/teams/{tid}/statements  line-item statements, one per round
GET  /api/runs/{id}/scoreboard              KPIs per company per round
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
  39 tests, all against real PostgreSQL.

Next: S3 STOMP push + facilitator console, S4 decision form, S5 debrief export.

