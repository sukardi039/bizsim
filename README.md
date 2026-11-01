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
```

## Run it

Requires JDK 21 and Maven. The backend needs PostgreSQL; a throwaway one is in
`docker-compose.yml` (port 5433).

```bash
docker compose up -d db
cd backend
mvn spring-boot:run          # Flyway migrates on start
curl -s localhost:8080/api/ping
```

Configuration is environment-only:

| Variable | Purpose | Default |
|---|---|---|
| `BIZSIM_DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5433/bizsim` |
| `BIZSIM_DB_USER` / `BIZSIM_DB_PASSWORD` | credentials | `bizsim` / `bizsim` |
| `BIZSIM_JWT_SECRET` | HMAC key, **≥ 32 bytes** | dev-only value, refuses to start if short |
| `BIZSIM_PORT` | HTTP port | `8080` |

## Test

```bash
cd backend && mvn test
```

Tests run against a real PostgreSQL started in-process (Zonky embedded), so `jsonb`, identity
columns and the Flyway migrations are exercised as they will run in production. No Docker and no
root are required.

## API so far (S0)

```
GET  /api/ping              no auth
POST /api/auth/login        {tenantCode, email, password} -> {token, expiresInSeconds, ...}
GET  /api/me                Bearer token -> caller identity from the token
```

Every other path returns 401 without a valid token — including unknown paths, so an unauthenticated
caller cannot map the API.

## Status

S0 complete: schema baseline, tenant-scoped auth, CI.
S1 complete: run/team/player management, round state machine (open → close → advance), 16 tests.

S2 (decisions, scoring, results) is blocked on the six domain answers in the delivery plan §7 —
specifically: what a team decides per round, the market model, and the KPI set. Those are product
facts, not design choices. `ScoringStrategy` is the first S2 task and it gets no formula until those
answers land.
