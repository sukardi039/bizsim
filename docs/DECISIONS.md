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

## Domain rulings — 2026-11-02 (answers to the open sheet)

Product owner answered `docs/OPEN_QUESTIONS.md` on 2026-11-02. These are rulings.

| # | Ruling |
|---|---|
| D8 | **Selling price per product is a monthly decision** (Q-A). |
| D9 | **Fixed catalogue of 3 products**, the same for every company in a run (Q-B). |
| D10 | **R&D is deterministic and time-boxed** (Q-C): every project has a **fixed duration**; success is evaluated **at the end of that duration**, not the moment money crosses the threshold. Outcome by cumulative investment ÷ hidden expected cost: **< 80% → failed, money lost; ≥ 80% → benefit scales linearly from 0 at 80% to full at 100%**. No random number generator. |
| D11 | **Insolvency is survivable** (Q-D): at cash < 0 the company produces nothing, is marked `INSOLVENT` and ranks last, but **stays in the run** — it keeps selling stock, and if inventory sales bring cash back to ≥ 0 it returns to `ACTIVE`. |
| D12 | **The round clock closes the round automatically** at the end of the allotted time (Q-G). Round count, round length and total session time are **configuration per simulation**, not constants. Scenario events are **defined when the simulation is set up**; the facilitator may only trigger events from that pre-defined catalogue, not author new ones mid-run. |
| D13 | **KPI set is the eight in the answer sheet**, six on the slide, lost sales and R&D/tech on page 2 (Q-H). |
| D14 | **Price elasticity is tuning data, not a ruling yet** (Q-I): the default model stands (cheapest does not automatically win), and the exponent stays in scenario data so it can be changed after the pilot without code. |
| D15 | **Every listed company aspect is wanted eventually** (spec §10), including hiring/firing, capacity, quality upgrade, paid market research, working capital, and new-product launch from R&D. Dividends are rejected. Sequence is fixed by the delivery plan, not by this line. |
| D16 | **No legacy scenario exists** (Q-E): scenario #1 is invented and **provisional**; all constants are calibration targets to be reviewed at the S7 rehearsal. |
| D17 | **R&D partial credit and over-funding are per-project definition**, not engine constants: a `partial_benefit` on/off switch; a curve of `LINEAR`, `POWER` (exponent; 2.0 makes the last dollars buy the most) or `CHANCE` (hidden threshold, so more investment means a higher chance of full success); and `overfunding` either `REFUND` or `LOST`. `CHANCE` draws its threshold from a **seeded** digest of company + round + project, so a recomputed round returns the same outcome — the uncertainty belongs to the trainee, not to the server. |
| D18 | **The economy is a scenario timeline, not a constant.** Each round carries demand, cost and wage indices; each product carries an **income elasticity**, so one downturn hits the premium line harder than the basic line. An **event deck** (conflict, fuel spike, recession, segment boom) is defined at setup and drawn **once per run** from a seed: unpredictable to trainees, frozen and replayable for the facilitator. A **paid market review** reports current conditions and a banded forecast, and never reveals the deck. |
| D24 | **Building and running are two jobs, and a package is the boundary** (ruling 3 Nov 2026). A **BUILDER** prepares: templates (environment, catalogue, settings), company profiles, and a **package** — the immutable bundle of one template version plus the companies that will compete. A **FACILITATOR** executes: starts sessions from a published package and seats trainees, and cannot change the template, the companies or their product lines. An **ADMIN** holds both jobs; a **PLAYER** holds neither. This is enforced in the API, not only the screen: template and company writes answer `403 auth.builder_only`, package publishing likewise, session start answers `403 auth.facilitator_only`, and a builder cannot even list sessions. Publishing **freezes** the template: the builder's draft is cloned into a private row (`pkg-<tenant>-<hex>`, `scenario.frozen_for_package`), the package points at that row, and sessions load it — so editing the draft afterwards cannot reach a class, and re-publishing makes a **new** package on a new frozen copy while the old one retires. The frozen row is invisible in the template library and refuses edits (`404`). A package is retired rather than deleted, so the sessions it ran keep a name to point at, and `run.package_id` + `run.scenario_key` record exactly which copy a cohort played. **Tidy up** (`GET`/`POST /api/packages/tidy-up`, builder only) removes retired packages that **no session ever ran from**, together with their frozen copies; the guard is "no run points at it", never "it looks old", so a package that taught a class is never offered. **`POST /api/runs` — creating a run without a package — is a builder's tool** (`403 auth.builder_only`; ADMIN included, since an admin holds both jobs), because building is preparing. It **requires `facilitatorId`**: a run must belong to the person who will drive it (D21) and a builder never drives, so preparing a run for nobody would create a session no one can operate. A facilitator's only way into a session is `POST /api/packages/{id}/sessions`. |
| D28 | **The statement records the cash a round opened with** (ruling 4 Nov 2026). The debrief showed closing cash only, so a class could not follow the balance across rounds. `round_statement.opening_cash` (V16) is written by the engine from the team's balance before the round, not derived: round 1 has no previous statement, and cash does not move by profit alone — building stock and buying capacity are cash out but not P&L, which is why round 3 of the demo showed a 322m gap between profit and the cash movement. The debrief shows that gap explicitly as **stock and capacity movement** rather than letting opening + profit silently fail to equal closing. |
| D27 | **The class list belongs to the package** (ruling 3 Nov 2026). "People need to be associated with the company, one person per company, while setting up the package." A package therefore carries a seat per company (`simulation_package_seat`, `position` = index in `company_profile_ids`), chosen by the builder at publish time. Starting a session seats that list automatically; the facilitator neither ticks nor assigns anyone on the day, and only *changes* a seat afterwards (an absent trainee, a walk-in). Guards: one person cannot occupy two companies of one package (`package.trainee_twice`), a company may be left open for a walk-in, and only an active `PLAYER` of the tenant can be seated (`user.not_found`). An explicit `traineeIds` on the start request still overrides, so a late arrival does not force a republish. |
| D26 | **Every editable parameter is reachable from the builder's console** (3 Nov 2026). The API's editable surface and the form's are the same set: the 22 definition keys in `EDITABLE_SETTINGS` (including the per-function `salaries` and `startingStaff` maps, `minRndBenefitRatio` and the cycle drifts), the environment timeline, the event deck, the product/channel/R&D catalogues, and company profiles. An invisible knob is a bug, not a simplification — a builder who cannot see a parameter will not know which economy they published. What stays hidden is the engine **calibration** (elasticities, baseline competitor), which is deliberately not a builder decision. The **event deck** (`PUT /api/templates/{key}/events`) stays editable after a session exists, because each run draws once and freezes its draw (`run_environment_event`, D18), so an edit cannot reach back into a class that played. |
| D25 | **The debrief reads the round's own statements** (3 Nov 2026). The facilitator's debrief shows one row per company for a chosen round — revenue, cost, profit, cash, share, lost sales and capacity used/available — with the cost broken down into its charged lines, and a Class footer summing revenue and profit. Every number comes from `round_statement` for that round, so the debrief can never disagree with what the engine charged; nothing is recomputed client-side. Read-only: a debrief explains a round, it does not change one. |
| D23 | **The interface speaks English and Chinese; the content speaks for itself** (ruling 3 Nov 2026). Language is a property of the person (`app_user.locale`), defaulting to the tenant's (`tenant.locale`), then to the browser. Only **chrome** is translated — labels, buttons, table headers, and (next) server messages as codes, because the client owns the wording. **Template content is never translated**: product names, R&D project names, event text and trend labels are authored, stored and shown exactly as written; a class that needs different wording gets a tenant copy of the template and edits it (D22). Supported languages today: `en`, `zh`. |
| D22 | **Templates are extended by copying, never by editing the shipped one** (ruling 3 Nov 2026). `standard-v1` stays global and read-only; a tenant duplicates it into its own draft, keyed by tenant, recording `based_on`, and edits the copy. A copy is complete — the definition document plus every catalogue row — so the engine reads it exactly like the base. **Company profiles** are global and reusable in the same way: one base profile, extended by copying, reusable across simulations. A **trainee's account is personal**: it outlives a single session and is re-seated, so deleting one session never deletes a person who is still in another. |
| D21 | **One facilitator, one simulation at a time.** A facilitator sees and drives only the simulations they own; starting a second while one is unfinished is refused with 409, so two parallel cohorts need two facilitator accounts. Admins see the whole tenant; players see the run their company belongs to. The scoreboard is **one row per company for one round** — the latest computed round by default, `?round=N` for the debrief — never a running history. |
| D20 | **The live channel is server-sent events, not STOMP.** All push in this product is one-way and facilitator-driven (`round.opened`, `round.closed`, `decision.submitted`), and the browser can consume SSE with no client library. Because `EventSource` cannot send an `Authorization` header, the client first asks for a **single-use ticket** bound to one run and tenant, valid for 60 seconds, on an authenticated POST; the session token never appears in a URL. Events publish only after the transaction commits, so a rolled-back round cannot appear closed on a screen. |
| D19 | **Cycles are chosen when the simulation is set up.** A scenario defines one **cycle template** (length, shape, per-cycle drift); a run chooses how many cycles to play and may stretch the round count if the cohort needs more time. The expanded economy is frozen per run. The forecast also carries a **consumer trend** — one signed strength per round plus one affinity per product — so a shift in taste tilts demand between segments without anyone forecasting per product. |

### Consequences of D8–D16

6. The scenario definition carries **elasticities, salaries, holding cost, brand decay and the
   baseline competitor share** — all tuning data, never Java constants.
7. R&D needs `duration_months` and an evaluation-at-deadline step; a project funded late cannot
   "catch up" inside a round.
8. `round.closes_at` exists and a scheduler closes due rounds through the same transactional method
   the facilitator endpoint uses; two closers must be idempotent, not race.
9. Working capital and new-product launch are **not built in v1** but must stay additive: revenue
   timing is isolated in the statement (§4 step 6 of the model spec) and a new product is a
   `company_product` row, not a schema change. Any code that assumes "3 products exist" is a bug.
10. `CHANCE` is deterministic in form (D17): the threshold is derived from an immutable seed, so
    "same inputs, same number" still holds and a voided round recomputes identically. If a trainer
    ever wants genuinely irreproducible outcomes, that is a documented exception to decision 4 and
    must be written down before the code changes.
11. Market size is a function of the round (D18). Anything that divides by "the market" — market
    share, lost sales, the scoreboard — uses the environment-adjusted pool, not the base demand.
12. The event draw is part of the run, not of the scenario (D18): two runs of the same scenario may
    have different events, and the debrief reads `run_environment_event`, never the live draw.
13. `run.rounds` is the length of a session (D19); `scenario.rounds` is only the length of the cycle
    template. Anything that asks "how many rounds" reads the run, and a run may be longer than the
    template by any whole number of positions.
14. A consumer trend is not a forecast per product (D19): it is one number per round, and the
    per-product effect comes from the product's affinity. No screen may show a per-product forecast
    as though the system predicted it.
15. The scoreboard is a snapshot of one round, not a history (D21). A list that grows one row per
    company per round is a debrief table, not a scoreboard.
16. A run leaves `OPEN` when its **last** round is *computed*, not when that round is opened (D12,
    D19). The finish is stamped in the same transaction that writes the final results, so the debrief
    and the D21 "one simulation in flight" guard agree on when a session has ended.

## Consequences that are now binding

1. Every business table carries `tenant_id`; no endpoint accepts a tenant parameter.
2. `app_user` is unique on `(tenant_id, email)` — the same address may exist in two tenants.
3. Login is tenant-qualified (`tenantCode` + email + password) and returns one generic 401 for every
   failure mode, so logins cannot enumerate tenants or accounts.
4. `decision.payload` is input, `round_result.score` is output; nothing client-supplied is ever
   re-served as state.
5. Round status transitions (`OPEN → CLOSED → COMPUTED`) happen in one server-side method, called
   only by the facilitator endpoint.
6. A trainee reads only their own company (D21). Cash, stock, the stored decision and the statement
   are the competitive information the simulation is about, so `context`, `statements` and
   `market-report` answer 403 for another company's id when the caller is a `PLAYER`; facilitators
   and admins read any company in their tenant. The check lives in one place
   (`RoundService.requireTeamReadable`) so a new read endpoint cannot quietly skip it.
7. Accounts are created, never self-registered. A facilitator mints `PLAYER` accounts for their own
   cohort (`POST /api/users`, tenant from the token, unique email inside the tenant, initial
   credential at least 8 characters); only an `ADMIN` may mint another `FACILITATOR`, because D21
   makes a second facilitator the way to run a second cohort and that is not a facilitator's call.
   There is no email delivery: the facilitator hands over the credential.
8. Deleting a simulation is a facilitator power, not an admin chore: its owner may delete it, an
   admin may delete any in the tenant, and nobody else may delete anything (`DELETE /api/runs/{id}`,
   204). It removes the run and its play data — rounds, decisions, results, statements, teams, seats —
   so a rehearsal can be cleared before a real class. It **never** removes an account (D22: a trainee
   is a person). The simulation row goes once it has no runs, and the D21 "one in flight" guard
   releases with it.
9. Trainee accounts are managed in Setup → People, never implicitly. A trainee who has played cannot
   be deleted (`DELETE /api/users/{id}` answers 409) because `decision.submitted_by` references the
   account and rewriting history is not allowed; the tool for "they left" is
   `POST /api/users/{id}/deactivate`, which stops the login and keeps the results readable.
   Deactivation is reversible (`/activate`); hard deletion is reserved for an account that never
   played. Only `PLAYER` accounts are removable this way, and nobody deletes themselves.
10. Editing a template is a **merge, not a replacement**. `PUT /api/templates/{key}/settings` writes
    only the fields the editor shows (`rounds`, `roundMinutes`, `startingCash`, salaries, cycles…)
    into the definition document and ignores everything else, because that document also carries
    engine calibration — elasticities, baseline competitor — that no form should be able to erase.
    The environment timeline is the opposite: `scenario_round` rows are replaced whole, since a
    partial edit of a sequence has no meaning. Every save bumps `scenario.version`.
11. The **catalogue** (products, channels, R&D projects) is frozen once a template has a simulation
    (`409 template.in_use`). A run's companies are created from those product codes and every stored
    decision names them, so rewriting the catalogue under a live class would strand its next round.
    Settings and the environment stay editable because a run freezes its own economy (D18) — the
    catalogue is the one thing a run borrows by reference rather than by value. The remedy is the one
    this product already uses everywhere: copy the template.
12. A company is a **profile**, and a session is assembled from profiles. `company_profile` holds the
    reusable definition — display name plus which of the template's products the company carries
    (empty = the full line) — with one shipped global row that any tenant may copy, exactly like a
    template. Starting a session (`POST /api/runs/prepared`) validates the template, the profiles and
    the trainees *before* writing anything, then creates one company per profile and seats the
    trainees in order; a profile's product line becomes the company's `company_product` rows.
    Deliberately **no roster-status column**: "not yet in a session" is derivable from having no
    seat, and a second stored flag would eventually disagree with the seats it describes.
