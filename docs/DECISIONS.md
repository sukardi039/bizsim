# Decisions — locked 2026-11-01

Authority: this file. The brief (`~/.../.hermes/plans/2026-11-01_management-simulation-brief.md`)
holds the reasoning; this file holds the rulings the code must obey.

Each line is a ruling, not a preference. Changing one is a deliberate, documented act — update this
file in the same commit as the code that depends on it.

## The ten product decisions

| # | Decision | Ruling |
|---|---|---|
| 1 | Delivery | One hosted multi-tenant service |
| 2 | Play mode | Simultaneous rounds, facilitator-driven |
| 3 | State ownership | Server computes; client submits decisions, never canonical state |
| 4 | Scoring | Deterministic formulas (`ScoringStrategy`); LLM feedback text later, never the number |
| 5 | Concurrency | 10 players per simulation, 5 concurrent simulations **per tenant** |
| 6 | Stack | Spring Boot 3.3 · Java 21 · PostgreSQL · React 19 + TypeScript + Vite |
| 7 | Auth | Local accounts now; OIDC-ready claims so SSO is additive |
| 8 | Reporting | In-app scoreboard + PDF/Excel debrief export |
| 9 | Content | Built-in standard scenario (data, not code); admin-editable in v1 via DB, UI later |
| 10 | Deadline | Go-live 31 Dec 2026 |

## Resolutions of the five conflicts (brief §4b)

- **A — state ownership (recorded 2026-11-01).** The server computes every round result. Clients
  submit decisions; `round_result` is written only by the round-closing transaction. Rationale: a
  leaderboard and a debrief need one authoritative computation. This deliberately does **not**
  copy wlwl's client-owned snapshot model — that existed for PHP parity, which does not apply here.
- **B — concurrency (recorded 2026-11-01).** "5 simulations at once" means 5 concurrent runs per
  tenant, not service-wide. Sizing target for v1: 10 tenants × 5 runs × 10 players = 500 concurrent
  players.
- **C — push channel (recorded 2026-11-01).** STOMP over WebSocket, topic per run, from sprint S3.
  Facilitator round boundaries are push events, not polling.
- **D — frontend (recorded 2026-11-01).** "React3" read as **React 19 + TypeScript + Vite**; React has
  no 3.x release line. If a different stack was meant, this line is the thing to change.
- **E — v1 scope cut (recorded 2026-11-01).** Deferred beyond 31 Dec 2026: billing/payments,
  native mobile apps, scenario-authoring UI, full Indonesian content translation (UI chrome only),
  cross-run historical analytics.

## Domain rulings — 2026-11-02 (simulation model)

Stated by the product owner in session 2026-11-02. These are rulings, not proposals. They
**supersede** the role-split assumption in worksheet Q1 and the phrase "teams of managers" in
brief §1.

| # | Ruling |
|---|---|
| D1 | **One trainee = one company.** A run holds up to **10 individual players**, each owning one company and deciding **all** aspects of it. No role split, no shared company. |
| D2 | All companies in a run **compete in the same market**. |
| D3 | Each company sells **multiple products**. |
| D4 | **One round = one month.** A session has about **8 cycles**, definable (scenario/run parameter, not a constant). |
| D5 | Every month a company decides: **production volume per product**, **marketing channel + spend per channel**, **manpower deployed to production**, and **R&D investment per proposed project**. |
| D6 | **R&D:** the system proposes projects. Each has a hidden expected cost and a different type/level of production advantage. Completion is decided by **dollars invested vs that hidden expected cost**. Hints about the cost may be shown; the exact figure is not. |
| D7 | **Cash is the hard constraint.** All production and investment spending must fit inside the bank balance. **No fundraising** in v1. |

### Consequences of D1–D7 (binding, not new rulings)

1. `decision` is keyed **(round_id, team_id)** — one submission per company per month. The
   `(round_id, team_id, role_key)` unique key from V1 is wrong and must change in the S2 migration.
   `player.role_key` becomes informational; every player is `'OWNER'`.
2. A company's **product line is state**, not an input: it needs its own table (price, quality/tech
   level, unit cost, stock per product). Production volume is a decision; stock is carried state.
3. Scoring needs **per-product demand pools** and a market of up to 10 sellers, replacing the
   single-demand-pool sketch in worksheet Q2.
4. Worksheet Q1 (four role lists) is **void**. One form per company per month covers all functions.
5. The S2 estimate in the delivery plan was sized for "4 roles × 4 inputs". It must be re-based —
   see `docs/SIMULATION_MODEL.md` §11.

## Consequences that are now binding

1. Every business table carries `tenant_id`; no endpoint accepts a tenant parameter.
2. `app_user` is unique on `(tenant_id, email)` — the same address may exist in two tenants.
3. Login is tenant-qualified (`tenantCode` + email + password) and returns one generic 401 for every
   failure mode, so logins cannot enumerate tenants or accounts.
4. `decision.payload` is input, `round_result.score` is output; nothing client-supplied is ever
   re-served as state.
5. Round status transitions (`OPEN → CLOSED → COMPUTED`) happen in one server-side method, called
   only by the facilitator endpoint.
