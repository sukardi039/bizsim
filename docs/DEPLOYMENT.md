# Deployment — Dokploy (three containers)

```
Internet ──TLS──> Dokploy/Traefik ──> web (nginx :80) ──/api──> api (java :8080) ──> db (postgres :5432)
```

- **web** — nginx serving the built React console and proxying `/api` to the backend. Attach the
  domain here. One origin for the browser, so there is no CORS and no API host baked into the bundle.
- **api** — Spring Boot. API-only image; the console is not inside it.
- **db** — PostgreSQL. Reachable only from `api`.

Nothing is published on the VPS: Dokploy routes to the container port and terminates TLS.

---

## 1. Create the service

Push the repo to GitHub first (Dokploy builds from a git remote), then in Dokploy:
**Create Service → Compose**.

| Field | Value |
|---|---|
| Repository | your bizsim repo |
| Branch | `master` |
| Compose path | `docker-compose.dokploy.yml` |

## 2. Environment variables

Dokploy → **Environment** tab. Each row is one `KEY=value` line; it becomes the `.env` that all three
containers read, so no credential is ever written into a tracked file.

### Backend → database

| Key | Value | Notes |
|---|---|---|
| `POSTGRES_DB` | `bizsim` | database name, created by the postgres image |
| `POSTGRES_USER` | `bizsim` | database owner |
| `POSTGRES_PASSWORD` | a strong password you generate | used by both `db` and `api` |
| `BIZSIM_DB_USER` | `bizsim` | same user |
| `BIZSIM_DB_PASSWORD` | the same value as `POSTGRES_PASSWORD` | |
| `BIZSIM_JWT_SECRET` | output of `openssl rand -base64 48` | **must be ≥ 32 bytes**; the app refuses to start otherwise |
| `BIZSIM_DB_URL` | set by compose to `jdbc:postgresql://db:5432/bizsim` | no need to set it by hand |
| `SPRING_PROFILES_ACTIVE` | leave unset | `seed` is a development convenience, not a deployment switch |
| `BIZSIM_BOOTSTRAP_TENANT` | your tenant code, e.g. `demo` | creates the tenant if missing |
| `BIZSIM_BOOTSTRAP_EMAIL` | the owner's email | creates that account if missing |
| `BIZSIM_BOOTSTRAP_ADMIN_SECRET` | a credential of 8+ characters | initial login; remove all of these after signing in |
| `BIZSIM_BOOTSTRAP_ROLE` | `ADMIN` (default), `FACILITATOR` or `BUILDER` | the first account's role |

### Did the deploy land?

`GET /api/version` needs no login and answers the question directly:

```
{"version":"0.9.3","builtAt":"<this image>",
 "migrations":28,"latest":"28","latestDescription":"orphan frozen copies", ...}
```

The console has its own probe, because the API and the console are separate containers:
`GET /build.json` returns `{"builtAt":"...","commit":"..."}` for the web bundle actually being served.
If `/api/version` is fresh but `/build.json` is old, only the api service rebuilt.

If `builtAt` is fresh but the console still looks like the previous release, that is the *web*
container, not the API: `index.html` is now served with `Cache-Control: no-store`, because a cached
copy keeps naming the previous bundle. Hard-refresh once after this deploy; it should not recur.

`builtAt` is baked into the jar at build time and is the field to trust: **the migration count only
moves when the schema moves**, so a release that ships only UI or permission changes leaves `latest`
unchanged. If `builtAt` is older than your last deploy, the old image is still running — redeploy and
watch that timestamp change.

**Stuck without an admin?** `BIZSIM_BOOTSTRAP_PROMOTE=true` sets the role of an account that
**already exists** — role only, never the password, so it cannot lock anyone out. Use it when a
deployment's only accounts are facilitators or trainees and nobody can create staff:

```
BIZSIM_BOOTSTRAP_TENANT=demo
BIZSIM_BOOTSTRAP_EMAIL=admin@demo.test
BIZSIM_BOOTSTRAP_PROMOTE=true      # ADMIN by default; add BIZSIM_BOOTSTRAP_ROLE to choose another
```

Restart once, sign in, then remove the variables.

**Keep the first account an `ADMIN`.** Only an admin may create a `BUILDER` or a `FACILITATOR`
(D24), and the whole setup workbench — templates, events, companies, packages — is behind `BUILDER`.
If the first account is a facilitator, nobody can reach the setup screens until you either promote it
or bootstrap a second account with a new email address.

### Init data — a runnable class on an empty database (D72)

An empty database is already complete on the **simulation** side: the migrations create the schema
and seed the shipped `standard-v1` template, its three products, three channels, five R&D projects
(plus the Chinese catalogue), the eight-round environment, the four-event deck and one global company
profile. What migrations cannot create is **people and a class** — that is tenant data.

Init data creates it, through the same service calls the console makes (nothing is written with raw
SQL), and then plays the session to a **FINISHED** result with real statements, standings and a working
PDF/Excel debrief:

- tenant + accounts: `admin@<tenant>.test` (ADMIN), `builder@`, `trainer@` (FACILITATOR), `player1@…playerN@` (PLAYER)
- a tenant-owned **DRAFT** template copy (the shipped template is read-only, so this is what the editor shows)
- one company profile per company, a published package with the class list seated, and a session started from it
- N months played by the shipped opponent policy, submitted through the same validator a trainee's form uses

**At boot** — the variables are inert unless a tenant is named:

| Key | Value |
|---|---|
| `BIZSIM_INIT_TENANT` | tenant code to create, e.g. `demo`. **Unset = off** |
| `BIZSIM_INIT_NAME` | tenant display name (default: the code) |
| `BIZSIM_INIT_COMPANIES` | companies in the class, 1–6 (default **4**) |
| `BIZSIM_INIT_TRAINEES` | trainees seated, 1–companies (default **4**) |
| `BIZSIM_INIT_ROUNDS` | months played to a result (default **8**; `0` = create but do not play) |
| `BIZSIM_INIT_CYCLES` | cycles for the session (default: the template's, shaped to the round count) |
| `BIZSIM_INIT_LOCALE` | class language, `en` or `zh` (default `en`) |
| `BIZSIM_INIT_PASSWORD` | credential for every seeded account (default: the documented demo one) |
| `BIZSIM_INIT_PACKAGE` / `BIZSIM_INIT_RUN` | package and session names |

A failure is **logged and the app continues** — a seeder must never crash-loop a deployment. Remove
the variables after the first boot; they are a seed, not configuration.

**On an existing deployment** (no restart, no environment change) an admin can ask for it once:

```
POST /api/admin/init-data      Authorization: Bearer <admin token>
{"companies":4,"trainees":4,"rounds":8,"locale":"en","password":"…","packageName":"Autumn cohort"}
```

It runs **once per tenant**: a second call answers `409 init.already_present` as soon as a package
exists, there is **no force or reset flag**, and existing accounts are reused, never rewritten, so it
cannot reset anyone's password. Testing it is one boot against a throwaway database; the suite covers
the same ground in `InitDataApiTest` (empty DB → accounts → package → computed rounds → login).

### Frontend → backend

| Key | Value | Notes |
|---|---|---|
| `VITE_API_BASE_URL` | empty | **leave empty.** The console calls `/api` on its own origin and nginx forwards it to `api:8080` — no CORS, no extra config |
| `BIZSIM_CORS_ORIGINS` | empty | only needed if you set the line above |

`VITE_API_BASE_URL` is a **build arg**, not a runtime variable: Vite inlines it into the JavaScript at
build time. It is passed through the `web` service's `build.args`, so changing it requires a rebuild,
not just a restart — that is the one sharp edge of a split deployment.

**When would you set it?** Only if the console is hosted somewhere other than this compose stack. In
that case set `VITE_API_BASE_URL=https://api.your-host` on `web` **and** `BIZSIM_CORS_ORIGINS=https://console.your-host`
on `api`. Anything else and you get a browser console full of blocked requests.

## 3. Domain

Dokploy → **Domains** → add your hostname → service **`web`** → container port **`80`** → HTTPS on
(Let's Encrypt). Do not point the domain at `api`: the API is reached through nginx, and exposing it
directly would skip the proxy and its buffering settings.

## 4. First boot — what the logs must show

In the **api** container:

```
db: database 'bizsim' exists; Flyway will apply any pending migrations
Successfully applied 6 migrations to schema "public", now at version v6
Started BizsimApplication in ~5 seconds
```

In the **web** container there is nothing to look for beyond nginx starting; if the page loads but the
API column of the network tab is 502, nginx started before the API — restart `web`, or check that the
`api` healthcheck is passing.

## 5. Verify

```bash
curl -fsS https://<your-domain>/api/ping     # ok
curl -fsS https://<your-domain>/             # the console HTML
```

Open `https://<your-domain>/` and sign in with the seeded facilitator account (tenant `demo`; see
`DevSeeder` for the addresses and the shared pilot password). Change that password before sharing the
URL — it is public knowledge by design.

The live scoreboard is the thing most likely to break in a new deployment: it is a long-lived
server-sent event stream. If the scoreboard only updates after a manual refresh, `proxy_buffering` has
been left on somewhere between Dokploy and nginx. `web/nginx.conf` already turns it off for
`/api/runs/<id>/events`; if you add another proxy in front, do the same there.

## 6. Backups

```bash
docker compose exec db pg_dump -U bizsim bizsim | gzip > bizsim-$(date +%F).sql.gz
```

Migrations replay from empty, so a dump plus this repository is a complete restore.

## 7. Known limits

| Limit | Why | What it would take |
|---|---|---|
| One `api` replica | the event stream keeps subscribers in memory, so two replicas would each hold half the screens (D20) | a broker (Redis pub/sub or similar) behind `RunEventService` |
| No self-service registration | trainees never sign themselves up; the builder creates their account (console → **People**) and hands over the initial credential | real email delivery + password reset |
| One facilitator per simulation | D21: a second concurrent simulation is refused | create another facilitator account (rerun the bootstrap with a different email, or SQL) |
| Images not built on the development machine | no Docker daemon there; every build step was run by hand instead | the first Dokploy build is the first real build — expect one iteration |
