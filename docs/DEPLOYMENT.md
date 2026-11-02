# Deployment — Dokploy

One image, one port. The backend serves the built React console, so there is no separate web server,
no proxy config beyond Dokploy's own, and no CORS to maintain. PostgreSQL is the only other container.

```
Internet ──TLS──> Dokploy (Traefik) ──> app:8080 ──> db:5432
```

## 0. Prerequisites

The repository must be reachable by Dokploy, i.e. pushed to GitHub (Dokploy builds from a git remote):

```bash
cd /opt/data/bizsim
git remote add origin git@github.com:<you>/bizsim.git
git push -u origin master
```

## 1. Create the service

Dokploy → **Create Service → Compose**, then:

| Field | Value |
|---|---|
| Repository | your bizsim repo |
| Branch | `master` |
| Compose path | `docker-compose.dokploy.yml` |

## 2. Environment variables

Dokploy → **Environment** tab. Each row below is one `KEY=value` line there; both services read the
resulting `.env`, so no credential is ever written into a tracked file.

| Key | Value |
|---|---|
| POSTGRES_DB | `bizsim` |
| POSTGRES_USER | `bizsim` |
| POSTGRES_PASSWORD | a strong password you generate |
| BIZSIM_DB_USER | `bizsim` |
| BIZSIM_DB_PASSWORD | the same value as POSTGRES_PASSWORD |
| BIZSIM_JWT_SECRET | output of `openssl rand -base64 48` |
| SPRING_PROFILES_ACTIVE | `seed` — pilot only, see below |

- `BIZSIM_JWT_SECRET` must be at least 32 bytes or the application refuses to start. That is
  deliberate: a short key is not a key.
- `SPRING_PROFILES_ACTIVE=seed` creates the demo tenant and three accounts on first boot. **Pilot
  only** — there is no self-service registration yet, so this is currently the only way to get a
  facilitator login. Remove it for a real deployment and create accounts in SQL.

## 3. Domain

Dokploy → **Domains** → add your hostname, service `app`, port `8080`, HTTPS on (Let's Encrypt).
Nothing needs to be published on the VPS; Traefik routes to the container port.

## 4. First boot — what the logs must show

```
db: database 'bizsim' exists; Flyway will apply any pending migrations
Successfully applied 6 migrations to schema "public", now at version v6
Started BizsimApplication in ~5 seconds
```

If the third line is missing but the first two are there, the schema is fine and the failure is in the
application — read up from the bottom of the log, not the top.

## 5. Verify

```bash
curl -fsS https://<your-domain>/api/ping            # ok
curl -fsS https://<your-domain>/                    # the console HTML
```

Then open `https://<your-domain>/` and sign in with the seeded facilitator account
(tenant `demo`, see `DevSeeder` for the addresses and the shared pilot password). Change those
credentials before anyone else gets the URL — the pilot password is public knowledge by design.

## 6. Backups

The database is the only state that matters:

```bash
docker compose exec db pg_dump -U bizsim bizsim | gzip > bizsim-$(date +%F).sql.gz
```

A round is computed and stored once; restoring that dump restores every scoreboard and statement.
Migrations are replayable from empty, so a dump plus the repository is a complete restore.

## 7. What this deployment does not do yet

- **No TLS inside the container.** Dokploy terminates it; the container speaks HTTP to Traefik.
- **One instance only.** The event stream keeps subscribers in memory, so two replicas would each hold
  half the screens. Scaling out needs a broker (D20 notes this); it is not a one-line change.
- **The image has not been built on the development machine** (no Docker daemon there). Every step it
  runs — `npm ci && npm run build`, `mvn package`, `java -jar` with `bizsim.web.dir` — has been run by
  hand on that machine, but the first real build is the first Dokploy deploy. Expect to iterate once.
