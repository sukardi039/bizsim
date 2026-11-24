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
| D47 | **An auto worker is an identity, a brain and a seat — three separate things** (ruling 5 Nov 2026). A class of four is a thin market; a reusable opponent fixes that without inventing a second simulation engine. **Identity**: `app_user` gains the role `AUTO_WORKER` — it signs decisions, it can be seated, and it can never log in (the login refuses the role before it even checks a password, so the role cannot be discovered from the form). **Brain**: `bot_profile` is a builder-owned library row — level (`LOW`/`MID`/`HIGH`) × personality (`CAUTIOUS`/`BALANCED`/`AGGRESSIVE`) plus numeric knobs in a document — **versioned**, because a decision records the version that produced it and editing a brain next term must not rewrite last term. **Seat**: `player.bot_profile_id` is what makes a company auto-played, so moving a company from a policy to a person is a data change. The name carries the level as a suffix (`Market 1_high`) because that is what a trainer reads on a scoreboard, but the suffix is **generated**: the stored level is the truth, so renaming cannot swap the brain. Nine combinations ship; a profile row overrides any number without a release. |
| D48 | **The opponent plays by the same rules, and can only know what a trainee knows** (ruling 5 Nov 2026). A bot submits through the same `RoundService.submit` path as a person: the same clock, the same validator, the same affordability check, the same `decision` row — the only difference is who signed it. It reads its own statements, the published environment indices and the template's public catalogue; it never reads another company's decision, the event deck, or a project's hidden cost (it plans against the same **hint band** a trainee is shown). It pays for the market review like anyone. Levels differ in judgement — forecast blend, noise, price posture, whether it buys the review or plans R&D — never in information. The mechanic is a **per-template switch** (`autoWorkers`, absent = off), so every existing template, package and copy seats exactly the companies it does today. Creating a brain is preparation, so only a builder or an admin may do it (D24); a facilitator cannot add an opponent to a class in progress. |
| D49 | **Bots are seated in the package, and are visible but not viewable** (ruling 5 Nov 2026). Market share is a function of who competes, so seating an opponent once the class has started would change everyone's economics retroactively; like the companies themselves, opponents are chosen when the package is prepared (D24/D27) and frozen with it. Trainees see that a company is auto-played — it appears as a company like any other, labelled with its suffix — but they see nothing of it: no statement, no submission, no rationale, no share. The opponent's reasoning is staff material, like every other company's detail (D21). Facilitator powers apply to an auto-played company unchanged: pause, extend, cash adjustment and void work on it exactly as on a person's company, with the same audit. |
| D79 | **Stock is charged at what it cost, not at what it would cost today** (fix 11 Nov 2026). The engine charged COGS at the **current** month's unit cost while the cash for those units had left the bank at an **earlier** month's cost. Whenever the cost index moved, stock carried across a round was expensed at a price the company never paid, so a term with no stock at the start and none at the end still showed cash and profit drifting apart. Measured on a live class: 172 units carried across a cost rise from 590,000 to 680,860 produced a **15,627,920** gap between cash movement and cumulative profit — and because the drift had the same sign as cost inflation, a company that stocked up *before* prices rose was punished in profit for a gain it had actually earned. That is why a loss-making term could end with more cash than it started with, and no amount of labelling could explain it away. **Fix**: weighted-average costing. `company_product.stock_value` carries the money actually spent on the stock (`V29`), producing adds `units × this month's cost`, selling removes `units × average cost of what is available`, and COGS charges that average. The invariant is then exact — `cash movement = profit + stock change + machines + refunds` — and a term that begins and ends with an empty shelf has cash movement **equal to** cumulative profit, to the rupiah. **Switch**: `costBasis: REPLACEMENT | WEIGHTED` in the template definition (a builder setting, like every other mechanic). A template that states nothing keeps the old behaviour when loaded, and `freeze` — the last moment before the economics are frozen for a class — stamps `WEIGHTED` on any package that does not name a basis, so **new packages default to the fixed basis and every already-published package keeps its own**. `V29` sets the shipped `standard-v1` to `WEIGHTED` so new tenant copies inherit it, and back-fills `stock_value = stock × unit_cost` for companies mid-flight (the only basis stored anywhere; `REPLACEMENT` templates ignore the column, so no published class's numbers move). **Reporting**: the statement's detail now records `stockValueChange`, `capacityBought` and `refund`, the trainee's statement shows them under 库存与产能变动 as 其中 lines, and the development report (D76) prints the bridge — "cash and profit differ by X: Y is stock still on the shelf, Z was spent on machines, R is research money returned" — with a replacement-costing caveat when the three do not reconcile. Tests: a unit test proves the basis charges 30 for units bought at 30 while replacement charges today's 40, an API test drives build-then-sell to an empty shelf and asserts cash movement equals cumulative profit; 181 green. |
| D80 | **The computations are audited, not asserted** (11 Nov 2026). A detailed accounting report is only worth something if each figure can be reproduced from stated rules by someone who does not trust the code, so the engine now has a **second implementation** to agree with: `scripts/audit_financials.py` replays every class from its decisions, the catalogue and the run's economy — environment, planning, market allocation, settlement, research, brand, statement, score map, cumulative totals and the chains between rounds — and compares the result with what the engine stored, field by field. It shares no code with the engine and never calls it. `scripts/audit_play.py` builds the data it reads: hoarding, liquidation, quality buying, machines, over-funded research, partial-credit research, insolvency, refusal to decide, absurd prices, a facilitator cash adjustment and a voided-and-replayed round. Last full run: **4,854 checks over 88 company-rounds** across weighted and replacement costing, all matching. `docs/AUDIT.md` carries the inventory of every computed quantity, the eleven invariants, the coverage and the two tolerances. The audit found two defects, both fixed here. **(1) A price had no ceiling.** A price is the only submitted number cash does not bound: any positive value was accepted, and one above 10¹² overflowed `company_product.unit_price numeric(14,2)` *inside* the compute transaction — the round rolled back, stayed OPEN, and every retry failed the same way, leaving the class stuck with submissions accepted and no result. Now `price.too_high` at submit, capped at 100× the catalogue price: generous for any elasticity experiment and safely inside the column. **(2) A statement did not record its costing basis.** The basis lived only on the template, so editing a template changed the meaning of statements already issued under the old one (the audit caught a run switching from replacement to weighted-average between rounds). `round_statement.cost_basis` (`V30`) is written at compute time and back-filled, so every historical report is self-describing and an audit never has to guess. |
| D77 | **`capacityPurchase` is money, not a machine count, and the console was sending a count** (fix 11 Nov 2026). The engine has always read the field as an amount: it subtracts it from cash as `capacityCost` and derives the capacity added as `capacityCost ÷ machineCostPerWorker`. The trainee's form asks "how many machines" and sent that number unchanged, so buying three machines charged **3 currency units** and added **0.000000075** workers of capacity — the purchase did nothing, while the form's own *Committed cash this month* line promised 120,000,000. Measured on a live class before the fix: submitted 3 → `capacity_cost` 3.00, `machine_workers` 0.0, `capacity_available` 30→30, cash 1,766,000,000→1,765,999,997. After: 120,000,000 charged, `machine_workers` 3, capacity usable from the **next** round (the engine plans with the machines the company already owns — consistent with the workforce hint on the form, which also counts only owned machines). Fix: the contract stays money (the engine, the API and every stored decision keep their meaning), and the client converts — the field shows `saved.capacityPurchase ÷ machineCostPerWorker`, sends `machines × machineCostPerWorker`, and the debrief's "machines" column divides back. Historic decisions are unaffected: a stored `3` was already worth 3 currency units and is still worth 3. New test (`RoundFlowApiTest`) buys three machines and asserts the charge, the instalment, the next-round capacity and that profit is untouched — which is also the clearest demonstration of why this line appears in the statement as **库存与产能变动** rather than as a cost. |
| D76 | **A trainee gets a development report for the term, in the class language, and so does the facilitator** (ruling 11 Nov 2026). The round-by-round debrief answers "what happened"; it does not answer "how did I run it". The report is one PDF per company: six dimensions, each with a band (strong / steady / needs attention), one sentence of evidence quoting the numbers, and the three most-needed pieces of advice. Dimensions: **liquidity** (cash after the last round, its movement, months of running costs held, the lowest point); **profitability** (cumulative profit, margin, profitable rounds); **market management** (final share and its movement, marketing spend and the revenue it returned per unit, marketing as a share of revenue, unserved demand); **operations** (capacity utilisation, units sold, lost demand, closing stock); **pricing** (gross margin over goods sold); **investment** (R&D, quality and capacity spend, projects completed). Bands are constants in `EvaluationReportService` (5% margin, 3/2 months of cash, 5× return on marketing, 80/60% utilisation, 40/20% gross margin) — **rules over the data, never generated prose**, so the same term always produces the same report and a trainer can defend every sentence. Every figure is read from `round_statement`/`round_result`, the rows the console already shows, so the report cannot disagree with the screen; nothing is re-estimated and no schema was needed. **Language**: the class's language, from `run.lang` (the package pins it, D66) — the server never depends on the client for it, though a caller may override. **Access**: a trainee may generate only their own company (asking for another is `403 team.not_yours`, not a silent redirect); staff may generate any company in the session; an unknown company is `404`, and a session with no computed round is `409 report.no_rounds`. It is **not** a comparison: share is the trainee's own and its movement, never a named competitor's figures (D21) — the class table (D75) remains the only place a trainee sees the others, and that is a rank. |
| D75 | **The class table ranks by cash after the round, then by that round's profit** (ruling 11 Nov 2026). Cash after a round is `opening cash + revenue − cash costs − money tied up in stock − machines bought` — note that the last two are **not** costs: they are cash out that profit never sees. That is exactly why cash is the stricter measure of a month's plan: a company that builds inventory or buys capacity pays for it here and not in profit, so a plan that spends only where it pays back ranks above one that looks profitable on paper while draining the bank. Liquidity first, because a business that cannot pay its bills is out of the game whatever its accrual result. Order: `cash desc, that round's profit desc, company name, team id` — profit is the tie-break (equal cash, better month wins) and the name makes the order total, so two identical months never swap places between reads. The table shows **cash** as well as profit: a rank a trainee cannot account for is worse than no rank, which widens D51 on purpose — competitors' cash is now visible to the class, because it is the figure being ranked, while market share, cost lines and statements stay staff-only. The debrief table, the scoreboard and the PDF/Excel exports keep their own order (cumulative profit): they answer "how has this class done", not "how did this month rank". |
| D74 | **A frozen copy is never offered as a template** (fix 10 Nov 2026). Publishing freezes one private scenario row per part (D62) and hides it from the library through `scenario.frozen_for_package`. Tidy up cleared that back-reference and then deleted only the package's **own** copy — part 1 — so a programme with more than one part left a copy behind per extra part, and with the reference gone it read as an ordinary template: `"<package> · Part B (v6)"` in the template library and, worse, in the package template picker, where a builder could publish from a class that no longer exists. Three changes: tidy up now reads the copies **before** clearing the reference and deletes all of them; the library refuses the reserved frozen key shape (`pkg-<tenant>-<8 hex>`, `PackageService.frozenKey`) even when no reference survives, so no other path can reintroduce the leak; and `V28__orphan_frozen_copies.sql` deletes the rows the old path already left, guarded exactly like V22 — nothing a package, part, derived template, simulation or run still names is touched. Verified on a real database: a two-part package retires and tidies to zero copies, an inserted orphan is hidden from the picker and removed by the migration, and a live package's own copy survives both. |
| D73 | **Every numeric field groups thousands and never steps** (instruction 10 Nov 2026). In the trainee's decision form a figure is read at a glance: `1000000` is a different object from `1,000,000` when the whole game is played in millions. `NumberInput` (`web/src/NumberInput.tsx`) is therefore a **text** field with `inputMode` (`numeric`/`decimal`): it groups on every keystroke with the caret put back by digit count, keeps 2 decimals on money fields and 0 on units/people/machines (typing `1234567.89` reads `1,234,567.89`), drops everything that is not a digit or one decimal mark, and ignores `ArrowUp`/`ArrowDown` — there is no spinner and no fine adjustment, because one stray click on a spinner turns 1,000,000 into 1,000,001 in a form where that is a real business error. Outputs already render through `i18n.money()` / `i18n.number()`, so tables, statements, standings and the debrief carry the same grouping; a stray unlabelled figure in the market-index line was fixed in the same pass. The field only decides how the number is **read and written** — the server still validates every value, so a client that skips this component cannot send anything different. |
| D72 | **Init data is a first-class way to create a runnable tenant, and it goes through the services** (implemented 10 Nov 2026; defaults chosen during implementation, open to a ruling). An empty database is already complete on the simulation side — the 27 migrations seed the shipped `standard-v1` template, three products, three channels, five R&D projects plus the Chinese catalogue, the eight-round environment, the four-event deck and one global company profile. What migrations cannot create is **people and a class**. `InitDataService` creates exactly that through the same calls the console makes: tenant, `ADMIN`/`BUILDER`/`FACILITATOR` and N `PLAYER` accounts (bcrypt), a tenant-owned **DRAFT** template copy (the shipped one is read-only, so a demo without a copy shows an empty library), one company profile per company, a **published package** (freezing the template, recording the part and the seats, pinning the class language), a session started from it, and — when rounds > 0 — every month played by the shipped `AutoWorker` policy submitted through `RoundService.submit`, so the same validator, clock and affordability preview apply as to a trainee; `ScoringEngine` then computes them, leaving a **FINISHED** session with real statements, standings and a working PDF/Excel debrief. Three triggers share one service: `BIZSIM_INIT_TENANT` at boot (`InitDataRunner`, fail-soft — a seed failure is logged and never crash-loops a deployment), `POST /api/admin/init-data` (ADMIN only, for a host whose environment cannot be changed) and the tests. Rules: it runs **once per tenant** (`409 init.already_present` once a package exists) with **no force/reset flag**; existing accounts are reused, never rewritten, so it cannot reset a password; the whole thing is one transaction, so a failure leaves an empty tenant rather than a half-published class. Session length is set on the tenant's own template copy (`rounds`, `cycles`, `cycleLength`) because a package session takes its length from the template, never from the request (D62); the default is the shipped 8 rounds. Deliberately **not** raw SQL: the economy freeze (D18), the part rows (D62), the seat rows (D27) and the frozen-template link (D33) are rules, and a seeder that bypassed them would write rows the console cannot render. |
| D71 | **A part of a programme is started once, so it is not offered again** (ruling 6 Nov 2026). The start card listed every part of a package, so a facilitator could begin part 1 a second time — and on a single-part package, where no part selector is shown at all, pressing *Start session* twice did it without asking. Two sessions of one part are indistinguishable in the run list and both hold the same class list, so the second can only be a mistake. `GET /packages` now reports each package's **`startedParts`**, the start card offers **only the parts that have not begun** (naming the started ones underneath, so a missing option is explained rather than mysterious) and disables *Start session* when nothing is left, and `POST /packages/{id}/sessions` **refuses a second run of a started part** (`409 part.already_started`, naming the part and the status it is in) so the rule holds for any client. Deleting the session frees the part again; to replay a class, use Reset (D58) or publish a new package (D33). |
| D70 | **Sessions returns to the session list** (ruling 6 Nov 2026). With a run selected, the Sessions button set the view but kept the run open, so pressing it did nothing visible and the only way back to the package (and part) picker was a page reload. It now clears the selection and shows the session list with the start card — the run you left stays in the sidebar, one click away. |
| D69 | **The debrief selector offers only rounds that have been computed** (ruling 6 Nov 2026). It was built from the template's round count, so a facilitator could pick round 6 of a class that had played three — and the backend answered with an error. The options now come from the statements themselves (the record of computed rounds), the selection follows the last computed round when that set changes (including after a reset, which removes rounds), and a run with nothing computed shows *no round computed yet* with the selector disabled. The statements are read for the whole run rather than for the selected round, so the selector's policing no longer depends on the round it is policing. |
| D68 | **A confirmation belongs to the round it describes** (ruling 6 Nov 2026). The trainee's green banner said *"You have submitted a decision for this round"* — hardcoded English, and silent about *which* round, so once the facilitator advanced it sat above a header naming a later round and a form nothing had been sent for. It now reads **"Your round N decision is in…"**, localised, from the same context as the header. Both consoles also clear their notices when the round changes or closes: the facilitator's green note (clock, cash, void, reset) and the trainee's submission confirmation describe a moment, not a state, and outliving their round is worse than disappearing. |
| D67 | **A submission opens beneath the company it belongs to** (ruling 6 Nov 2026). *Submissions → show the month* used to unfold inside the table cell, so the summary appeared squeezed into one column and read as detached from the company. It is now an expansion row inserted directly after the selected company's line, spanning the table, and only one is open at a time; the button reads *Hide* while open. The detail therefore starts at the company you clicked, never above the list. |
| D66 | **A package must name its class language** (ruling 6 Nov 2026). "Follow each trainee's own choice" cannot express what the language now controls: it picks the interface **and** which R&D catalogue the class competes on (D64). Left blank, the run fell back to the *facilitator's* language — verified: a Chinese trainee in an English-run class saw the English project set. Publishing now requires `en` or `zh` (`400 package.locale_required`), the package builder offers only those two and starts on the builder's own language, and the publish button says *choose the class language* until one is chosen. Existing packages with no language keep working — their runs fall back to the facilitator's language as before — but nothing new can be ambiguous. |
| D65 | **A row is removed by the row, not by position** (5 Nov 2026). The catalogue tabs offered *Remove the last row*, which is not a statement about what the builder is looking at: to delete the third of five projects you had to delete the last two and retype them. Each row now carries its own **Remove this row** control, so the action names its object and there is no question of *current* or *focused* — the row you pressed it on is the row that goes. *Remove the last round* stays, because rounds are an ordered sequence and the tail is the only meaningful end. |
| D64 | **The Chinese R&D catalogue is a set of its own, not a label on the English one** (correction, 5 Nov 2026 — supersedes D63). D63 added a `name_zh` column to each project and put a *Name (Chinese)* column into the original table; that is not a second set, and it left the English and Chinese versions fused in one list. Now `rnd_project.lang` splits the catalogue: the five shipped projects stay the English set, and a duplicate Chinese set exists beside them (codes `…-ZH`, same target product, effect, cost, duration), so a Chinese class chooses between five projects in Chinese **and an English class never sees them**. The set a session reads is fixed on the **run** (`run.lang`) when it starts — the package's class language, else the facilitator's — because two people in one class must not be choosing from different catalogues. `name_zh` and its editor column are gone. |
| D63 | **The R&D catalogue carries a Chinese label, not a second catalogue** (ruling 5 Nov 2026). A Chinese class was reading English project names. Duplicating the five projects into a parallel Chinese set would put two of everything in front of every trainee and split the thing the class compares, so instead `rnd_project.name_zh` sits beside `name`: one project, same code, same cost, same effect, two labels. The interface picks by the reader's language and falls back to English when a translation is missing. The five shipped projects are translated (基础款流程效率 / 标准款流程效率 / 高端款质量计划 / 工厂自动化 / 基础款市场开发) **for every template carrying those codes, frozen copies included** — a label is not a mechanic, and a class should not meet English names because its package was frozen before the translation existed. Builders edit the Chinese name in the template's R&D tab; `saveProjects` keeps the stored translation when a client sends none, so editing the English name cannot silently erase it. |
| D62 | **A package can be a programme of parts** (ruling 5 Nov 2026). A course is several simulations of the same class, so a package now holds **parts**: each part its own template (it may be the same template again) and its own frozen copy, with the **class list — companies and seats — fixed at package level**. Ruled details: **no state carries between parts** (every part opens from the same starting condition, which is what makes the parts comparable), **rounds come strictly from the part's template**, and a part starts only when the previous part is **FINISHED** (`409 part.previous_unfinished`). A single-template package is a programme of one part — the package's own `template_key` still holds part 1, so every existing reader keeps working. `GET /packages/{id}/comparison` compares the parts: per company the profit in each part, the **move versus the previous part**, and the **move versus the class average**, which is what a facilitator projects between parts. `POST /packages/{id}/reset` puts every part back to month 0; the run screen's Reset still resets one part. The course report as a PDF is deliberately not built yet — the comparison table is the first version, to be tuned after a class or two. |
| D61 | **The opening rounds can arrive with a suggested plan, as a draft and never a submission** (ruling 5 Nov 2026). A trainee facing a blank monthly form has to learn the model and the interface at once. A template setting **`guidedRounds`** (default **0** = off) makes the first *N* rounds load with a plan prepared by a **fixed MID/BALANCED** brain, run on that company's own state — same blindness as a bot, no extra information — and trimmed by the same affordability pass, so it always passes the validator as it stands. The ruled details: it is a **draft only** (never auto-submitted; the seat stays human and nothing is written until they submit), the **reasoning is hidden** for now, the prefill happens **only when the form is untouched and the company has nothing stored** for that round (a saved or edited draft is the trainee's own work), it is **invisible to the facilitator** (no trace row — the suggestion is a deterministic function of run, round and company, so it can be reproduced without storing it), and a **seated facilitator gets the guide too**. Past round *N* the form is blank again. |
| D60 | **A table that depends on the round must refetch when a round closes, not when its number changes** (ruling 5 Nov 2026). The facilitator's *Class standings* card looked one round behind: it refetched on `round.id` and `round.currentRound`, and computing the round you already opened changes neither — the number stays, only its status moves from open to computed. The API was never wrong (after computing round 3 it returns round 3). The card now also depends on `roundOpen` and on the run stream, so a compute, an auto-close or a reset repaints it without a reload. |
| D59 | **One company's refusal is never the class's problem, and a bold plan becomes a smaller one** (ruling 5 Nov 2026). Computing round 3 of a ten-company session returned *Internal Server Error*. Two defects behind one opaque 500. **(1) Transaction poisoning:** the bot pass before a compute caught each seat's failure but ran in one transaction, so the first refusal marked it rollback-only and the commit failed with `UnexpectedRollbackException` — an error that named nothing. The pass is now transaction-per-seat (each `submit` is its own), so a refusal is logged and the round still computes. **(2) Plans that cannot be paid for:** the policy sized its month against expected revenue rather than cash, so after a bad month it planned more than it could afford, the validator refused, and the opponent skipped the month. `BotRoundService.affordable()` now trims the plan against the engine's own `preview` — marketing first, then R&D, then capacity, then output — and falls back to an idle plan (a row per product at 0, workers within both payroll and plant, prices held so stock still sells). Replay applies the same trimming, or a trimmed month would replay as a mismatch. |
| D58 | **The facilitator can reset a session to its pre-start condition** (ruling 5 Nov 2026). A class that has gone wrong in the first two months — wrong companies seated, a template discovered mid-session, a demonstration that must be repeated — had no way back except deleting the run and re-registering everyone. `POST /runs/{id}/reset` restores every company from **round 1's snapshot** (written when the first round was computed, which is exactly the state the books opened with), deletes every round with its decisions, results, statements and bot traces, and sets the run back to month 0, open, standings hidden. **Teams and seats survive**: this is *start the month again*, not *new session*. The reset is audited as `RUN_RESET`, so "we started over" is answerable afterwards. Rounds computed before snapshots existed refuse rather than half-restore; a run with no computed round simply loses its decisions. |
| D57 | **Opening the next round never computes the previous one** (ruling 5 Nov 2026). D38 let `POST /runs/{id}/rounds/next` compute whatever was open first — a convenience for older clients, and the reason the action could be described as "open next round, which computes the previous one". With one button on the console that convenience buys nothing, and it makes a *compute* happen on a path nobody reads as compute: results are published for a round the facilitator never pressed Compute on. `rounds/next` now **refuses while a round is open** (`409 round.still_open`, naming the round) and writes no result. The **Compute** button and the **clock** are the only two ways a round is computed — which is also why the opponent's at-close decision (D56) lives on exactly those two paths. |
| D56 | **An opponent plays when the round is computed early** (ruling 5 Nov 2026). The seeded moment is 20–70% into a round; a facilitator who computes immediately — the normal workshop move — closed the round before that moment, so the opponent submitted nothing and the month was computed with no plan. That is not "a company like any other": a person at the keyboard can submit the instant the round opens, and a policy that is *scheduled* to act later could not. Now every path that computes a round — the Compute button, the auto-close clock, and *Open next round* — asks the opponent to decide first, recorded as **`AT_CLOSE`**, so the trace still distinguishes scheduled, by hand, and at-close play. An opponent whose moment *did* pass and still produced nothing keeps its absence visible, so a genuinely broken worker is not hidden by this. The rationale also stopped printing scientific notation (`6E+2`), because it is read aloud in class. |
| D55 | **A seat holds a person or an opponent** (ruling 5 Nov 2026). Seating an auto worker in a package answered *"No such trainee in this tenant"*: both seat validators — the package's class list and the session start — filtered on `role = 'PLAYER'`, and an opponent's identity carries `AUTO_WORKER`. The rule is now explicit in one place: a seat accepts `PLAYER` **or** `AUTO_WORKER`, and refuses staff, because staff run the class rather than compete in it. Publishing additionally refuses an opponent seat when the template has not switched `autoWorkers` on (`409 bot.switch_off`), checked **before** the freeze. Verified with the shape that failed: nine opponents and one player across ten companies publish, start a session, and all nine decide in round 1. |
| D54 | **Publishing validates before it writes, and is one unit of work** (ruling 5 Nov 2026). A publish that was refused — a seat list of the wrong length, the same person in two companies, a bad class language — still left a **frozen template** in the library, published, attached to no package. Cause: `publish` froze the template first (its own transaction, so it committed) and validated the class list afterwards, and the controller itself was not transactional, so the refusal rolled nothing back. The copy carries `frozen_for_package = null`, which is exactly what the library filters on — so it looked like a template. Now every check runs before anything is created and the whole publish is one transaction; **V22** deletes the copies the old path left behind (`pkg-…`, unattached to any package, session or simulation). The rule generalises: a frozen copy is created last, and only when nothing can refuse. |
| D53 | **The round clock is on both screens, and the opponent needs nobody to start it** (ruling 5 Nov 2026). Two gaps closed. **Countdown**: the facilitator's clock ticked but the trainee's screen showed only "waiting" — a class cannot pace itself against a deadline it cannot see. A shared countdown renders `clockRemainingSeconds` from the last refresh and ticks locally between them, so both consoles show the same number: *12:34 left in this round*, red under a minute, **Paused at 12:34** while the facilitator has stopped time, and nothing at all when no round is open. **The opponent runs itself**: `BotRoundService` sweeps every five seconds and submits for any auto-played company that is due — the facilitator's button is an override (`MANUAL`), never a prerequisite. The seeded moment is 20–70% **of the template's round length**; it was a hardcoded 20 minutes, which would have made a bot submit after a five-minute round had already closed, so a short-round template would have had opponents that never played. A test proves the sweep alone submits, without any button. |
| D52 | **The ranking is a class moment, not a reward for computing a round** (ruling 5 Nov 2026). D51 made the standings readable the instant a round computed; a facilitator needs to decide *when* the class sees it — after reading the debrief, not before. `run.standings_visible` (hidden by default) gates the trainee's read: hidden answers an **empty table, not an error**, so the console simply has nothing to show, and a cohort that never reveals it never sees a ranking. The facilitator's screen carries the **same three columns** the class reads, so what is read out in class is literally what is on the board, plus one switch — **Show the class / Hide from the class** — recorded in `facilitator_action` as `STANDINGS_SHOW` / `STANDINGS_HIDE`, because "when did they see the ranking?" is a question a class asks afterwards. Staff reads are never gated: the switch is about the class, not about the facilitator. |
| D51 | **A trainee sees the class table — rank, company, profit — and nothing else** (ruling 5 Nov 2026). D21 kept every competitor figure on the facilitator's screen, which left a trainee unable to answer the first question a class asks: *where am I?* `GET /runs/{id}/standings` returns exactly three fields per company — rank, company name, this round's profit — for the latest computed round (or a named one), with the caller's own row flagged. It is a **separate query, not a projection of the scoreboard**, so widening it later is a decision rather than an accident: no market share, no cash, no stock, no statement, no opponent's reasoning. Access is *seated in this session or staff*: a trainee of the tenant who is not seated answers 403, because a table is still other people's business. Rank is by this round's profit, ties broken by company order so the table is stable. |
| D50 | **A level has to mean something measurable, so self-play is a test** (ruling 5 Nov 2026). Six companies — two per level, nobody at the keyboard — play the shipped template to the end of the run, and the harness asserts the product's promise: `HIGH` earns more than `MID`, `MID` more than `LOW`, no level is insolvent, and replaying the same seed produces the identical history decision for decision. The same harness prints the calibration table (profit per round by level), so a change to the template or the policy is judged by the numbers rather than by a pass/fail alone. It is also the economy's missing calibration tool: an inversion points at the template as readily as at the policy. |
| D46 | **The report is organised by who reads it** (ruling 5 Nov 2026). One document could not serve both readers. **A trainee's report is one page per round**: that month's summary (revenue, cost, profit, cumulative, cash, brand, stock, lost sales, capacity), then what they sent, then the cost lines — a term that can be read a month at a time. **The facilitator's report opens with two summaries**, which are the two ways a class reviews a term: *Summary by trainee* — one page per company, its rounds in sequence with a Total line — and *Comparison by month* — one page per round with every company side by side — followed by the detail behind that month (submission and cost lines). The workbook mirrors it: a **By trainee** sheet (one row per company over the whole term: rounds, total revenue/cost/profit, final cumulative and cash, average market share), the **Results** sheet (one row per company per round), **Submissions** and **Cost lines**. Sections never share a page with the next one, so a facilitator can hand out a trainee's page without the rest of the class. |
| D45 | **A PDF table's columns are sized by what they hold** (ruling 5 Nov 2026, recorded 6 Nov 2026). The *what the month produced* table used twelve equal columns on a landscape page, so a figure like `1,770,400,000` was wider than its cell: values wrapped, spilled into the neighbouring column, and the header no longer sat above its data — the submission and cost tables happened to fit, the results table did not. Every table in the debrief now sets **relative column widths sized to its content** (names take the room, figures take what a figure needs), **money cells are no-wrap** so a number can never be split across two lines, and **header rows repeat** when a table breaks across pages. Verified by rendering the generated PDF to images in both languages. Recorded late: the ruling was cited in `DebriefExportService` but never written into this table, so the numbering jumped D44 → D46; this row restores it, and the code comment now points at a ruling that exists. |
| D44 | **The downloaded document speaks the reader's language** (ruling 5 Nov 2026). The PDF and the workbook were hard-coded English, so a Chinese cohort received its own debrief in a language it did not play in. Both now take the reader's locale (`?locale=zh|en`, sent by the console from its current language) and render every label — blocks, column headers, cost lines, sheet names — in it; anything unrecognised falls back to English rather than failing a download. **Authored content is never translated**: company, product and trainee names are the class's own words (the same rule the interface already follows). The trainee's report follows the language the trainee is using, which for a package-declared cohort (D29) is the package's. |
| D43 | **The download is a report of every round, and a trainee gets their own copy** (ruling 5 Nov 2026). The export was one round's KPI table: too little for a class that wants to review the term, and unavailable to the trainee who most needs their own history. The PDF and the workbook now carry **every computed round**, each with three blocks — **what each company sent** (per-product units, price and quality spend, marketing, R&D, staff, machines, review), **what the month produced** (revenue, cost, profit, cumulative, cash, brand, stock, lost sales, capacity), and **the cost lines**. The workbook keeps that as three sheets (Results, Submissions, Cost lines) with a Round column, so one sheet sorts and pivots across the whole term. `GET /runs/{id}/debrief/export` is now open to `PLAYER` as well: a trainee's request is scoped by **their seat**, not by a parameter, and the report drops market share and every competitor row (D21) — the file is named `bizsim-results-…` to make that difference visible. A trainee who is not seated answers 403; a staff report is unchanged and still named `bizsim-debrief-…`. |
| D42 | **A company's detail opens with the month it sent, then what the month produced** (ruling 5 Nov 2026). The debrief could show what the engine charged but not what the company asked for, so a class could read "cost 651m" without seeing that 800 units at 1.0m had been planned. The per-company **Detail** now begins with a **Submission · round N** section — units, price and quality spend per product, marketing per channel, R&D per project, production staff and machines, and whether the market review was bought — read from the same stored `decision` rows the submissions endpoint serves, then Revenue · Cost · Result and Per product. A company that sent nothing says so: an empty month is a business event, not a missing screen. The trainee's own results keep the same layout minus the section, because a trainee already knows what they sent (D21 keeps the facilitator's copy of it staff-only). |
| D41 | **The debrief leaves the screen: a classroom view, a PDF and a workbook** (ruling 5 Nov 2026). The facilitator could read the debrief but could not project it or hand it out, which is what a class day needs (Q-H's "six on the slide, two on page 2"). Three additions: **Present** is a full-screen view of one round — page 1 the six KPIs per company (profit, cumulative profit, market share, cash, brand, stock), page 2 the explanation (lost sales, capacity used, R&D completed, status), arrows move page and round, Esc closes, and the print stylesheet keeps only the cards so Ctrl+P is a usable deck; **Download PDF** and **Download Excel** (`GET /api/runs/{id}/debrief/export?round=&format=pdf|xlsx`, facilitator only) build the same numbers from `round_result` and `round_statement`, never a recomputation. The PDF embeds WenQuanYi Micro Hei, because class content is authored and may be Chinese — a PDF that renders names as boxes is worse than none. Verified by reading the generated PDF's text back (`阿尔法制造` survives) and by parsing the workbook. |
| D40 | **Quality upgrade is a per-template mechanic, priced per point** (ruling 5 Nov 2026, delivering D15). `company_product.quality_level` existed and `elasticity.quality` was already in the demand formula, but no decision could move it, so the mechanic was unreachable. The monthly decision gains `quality: [{product, invest}]`; money buys points at `qualityCostPerPoint` (shipped default 60,000,000 per point, ~2× the R&D project's rate, so buying quality directly is the expensive route and R&D stays the efficient one). The purchase is **immediate** — it raises this round's attractiveness, like buying machines rather than like an R&D programme — it **stops at 100** and the money beyond the room is not charged, and it is an **expense**: it appears as its own `round_statement.quality_cost` line, so profit and cash fall by the same amount and the "stock and capacity movement" gap does not grow. The mechanic is a **switch on the template** (`qualityUpgrade`, absent = off, so every existing template and copy behaves exactly as before; the shipped `standard-v1` turns it on and names the price). Off, the trainee form has no card and the validator refuses money with `quality.disabled` rather than silently donating it. Both numbers are editable in Setup → Templates → Settings (D26). |
| D39 | **The facilitator's missing powers are built, and a void is a real undo** (ruling 5 Nov 2026, delivering Q-G). Q-G ruled six powers and only open/close existed. Now: **pause / resume / extend the clock** (`round.paused_at` freezes the deadline, the sweep skips paused rounds, resume gives back exactly the stopped time, extend also gives an untimed round a deadline, and a paused round still accepts submissions); **view every company's submission** while the round is open (`GET /runs/{id}/decisions`, staff only — the numbers are competitive information); **adjust a company's cash** with a mandatory reason, applied immediately and written to `facilitator_action`; and **void the last computed round**. The void restores state from `round_snapshot`, written for every company in the same transaction that computes the round — brand, staff, machine workers and the R&D slate are state the statement does not carry, so a reversal would have to guess. It then deletes that round's results and statements, reopens the round, and un-finishes the run if it had ended; decisions survive so the class corrects a month instead of retyping it. Rounds computed before V19 carry no snapshot and are refused (`round.no_snapshot`) rather than half-restored. Every one of these actions is in the audit trail with actor, round, amount and reason, so "why did our cash jump?" has an answer after the class. |
| D38 | **One round button on the run screen, not two** (ruling 4 Nov 2026). *Open next round* and *Compute round* sat side by side but only one of them is ever the right move: with a round open the facilitator computes it, with nothing open they open the next one, and pressing the other was a no-op. The header now renders a single primary button driven by `roundOpen`, which `GET /api/runs/{id}` exposes (the run's counter alone cannot say whether a round is open). Labels: *Open round N* — *Compute round* — *Session finished* (disabled). The round is now two clicks with a deliberate pause: compute, read the debrief, open the next. `POST /runs/{id}/rounds/next` still computes the previous round, so no capability is lost. |
| D37 | **The trainee's results screen carries the debrief's summary and detail** (ruling 4 Nov 2026). A trainee could open one round's Revenue · Cost · Result but had no view across rounds and no per-product lines, so the numbers a facilitator reads out in class could not be checked afterwards. *Your results* now lists one row per computed round (revenue, cost, profit, cash after, lost sales, capacity used), a row click selects the round, and the chosen round renders exactly the shared `StatementDetail` — Revenue · Cost · Result plus the Per product table — from the same `round_statement` rows the debrief uses. Scope guard (D21): no market share and no competitor figures reach the trainee; the per-product footer reads **Total**, not *Class*, because it is one company's round. |
| D36 | **The facilitator's run page names the trainee behind each company** (ruling 4 Nov 2026). D33 removed the seating card, and with it the only place on the run screen where the person-to-company mapping was visible: a facilitator could see that Alpha Industries lost 74 pieces but not that Ana ran it. The scoreboard and the debrief now label every company with its seated trainee(s), read from `run.players` on the run the console already has — no new endpoint and no seating controls, so D33 still holds. |
| D35 | **A finished R&D project takes no money, but its empty row must not block the round** (ruling 4 Nov 2026). The trainee's form sends a row for every project every round, and the validator refused any row whose project was not `ACTIVE` — including a row of 0. Once one project completed, every later submission failed with `rnd.not_active` ("RND-MKT-P1 is already COMPLETED"), so the class could not submit at all. The rule is now about money, not about rows: `rnd.not_active` fires only when `invest > 0`. The screen closes the input on a finished project and sends 0 for it, so the trainee sees a closed field instead of an error. Investment already committed in an earlier round is not refunded. |
| D34 | **Demand is allocated in whole pieces, and every statement carries its per-product lines** (ruling 4 Nov 2026). A company's share of a product's pool used to be rounded to two decimals, so a class could read "0.94 in stock" next to "82 pieces lost" and have no way to tell which product did what — or whether the two numbers even belonged together (they never do: a stock-out forces closing stock to 0). The market for a product is now rounded to whole pieces once (`wholePool`), and the cohort's share — `(1 - baselineShare) × pool` — is split by largest remainder, so the pieces allocated across teams add up to the served market exactly and no unit is invented or lost by rounding. `round_statement.detail.products` (opening, produced, demand, sold, closing, lost, price, unit cost, revenue, COGS per product) is returned by `GET /api/runs/{id}/teams/{teamId}/statements` as an object instead of a JSON string, and is rendered as a **Per product** table under Revenue · Cost · Result in both the debrief and the trainee's own results. Limits: production cut by the bank can still be fractional (`2.5` units when 100 of cash buys 2.5), so closing stock can still show a fraction in that edge case; demand and lost sales are always whole. |
| D33 | **The facilitator's run screen has no seating block** (ruling 4 Nov 2026). The Trainees card was a leftover from before D27, when the facilitator assembled the class; after D27 the class list travels in the package and after D30 the builder owns people, so the card only offered the facilitator an unrelated administration job on every session. The block is removed from the console. A seat left open in a package therefore stays open: that company plays with nobody, and the remedy is a new package, not a click during the class. `POST /api/runs/{id}/players` stays on the server (the prepared-session path and the API tests use it) but nothing in the console calls it. |
| D32 | **The trainee's screen follows the open round, not the run's round counter** (ruling 4 Nov 2026). Before a facilitator opens round 1 the run sits at `current_round = 0`; the form read that number, so a trainee who signed in early got the full dashboard and a *Submit round 0* button, and the server answered `round.not_open` (409) when they pressed it. The form context now reports `roundOpen`, and the trainee screen renders the waiting card instead of the dashboard whenever no round is open — before round 1, between rounds, and after the session has finished (where it says so and still shows the results). The rule: a decision screen exists only while a decision can be submitted. |
| D31 | **The People list hides trainees who already have a company, by default** (ruling 4 Nov 2026). The roster exists to assign people, so a trainee who already holds a seat is noise in it. `GET /api/users` therefore takes `includeAssigned` (default **false**), which leaves out `PLAYER` rows that appear in `simulation_package_seat` (a package class list, D27) or in `player` (a session). Staff are never filtered — they hold no seat. The People screen carries the switch, labelled with what it does; it defaults off. `GET /api/users/trainees` is **not** filtered, because a person may be seated in a new package as well, so the Packages class list must still offer everybody. |
| D30 | **The builder builds the people too; the facilitator only runs** (ruling 4 Nov 2026). Preparing and running are separate jobs, and a class is one artefact: the builder makes the template, the companies, the trainee accounts and the package, and the facilitator starts a session from it and drives the rounds. Consequences: `GET /api/users` and the account writes are open to every staff role (`requireStaff`), not facilitators alone, so the builder's Packages form can load the class list; `GET /api/users/trainees` returns active trainees only, which is all a package needs; a builder may create **trainees** only (staff roles stay an administrator's call) and may reset a trainee's password, not a staff one; the People section is hidden from `FACILITATOR`, whose console is the run screen. |
| D29 | **A package may declare the class language** (ruling 4 Nov 2026). `simulation_package.locale` (`en`, `zh`, or null) is chosen when the package is published; a trainee seated in a session started from it reads the interface in that language. Resolution order becomes: **package locale > person's own choice > tenant default > English**. Null keeps today's behaviour (each person's own choice), so existing packages are unaffected. The override is scoped to the session: it never writes `app_user.locale` or the browser's stored preference — a trainee attending one Chinese cohort must not be left in Chinese for the next English one. Staff (facilitator, builder, admin) keep their own language, because they work across classes. |
| D28 | **The statement records the cash a round opened with** (ruling 4 Nov 2026). The debrief showed closing cash only, so a class could not follow the balance across rounds. `round_statement.opening_cash` (V16) is written by the engine from the team's balance before the round, not derived: round 1 has no previous statement, and cash does not move by profit alone — building stock and buying capacity are cash out but not P&L, which is why round 3 of the demo showed a 322m gap between profit and the cash movement. The debrief shows that gap explicitly as **stock and capacity movement** rather than letting opening + profit silently fail to equal closing. Rounds computed before V16 are backfilled by V17 from `detail->>'cashChange'` (`opening = closing − movement`), derived from each statement's own row rather than recomputed. |
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
7. Accounts are created, never self-registered. The **builder** mints the `PLAYER` accounts for the
   cohort it is preparing (`POST /api/users`, tenant from the token, unique email inside the tenant,
   initial credential at least 8 characters); facilitators may still create trainees, because they
   did before D30 and no session should break over it. Only an `ADMIN` may mint a
   `FACILITATOR`, `BUILDER` or `ADMIN`, because D21 makes a second facilitator the way to run a
   second cohort and that is not a builder's call either. There is no email delivery: whoever
   created the account hands over the credential.
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

## Domain rulings — 2026-11-11 (the market environment is an economic model)

The market used to move through one demand index per round and one income elasticity per product.
That is a dial, not an economy: there are no customers in it, so "a recession hurts premium more"
was a number in a row rather than a consequence of who the buyers are. The trainer's requirement:
the market environment must be the **result** of economic events acting on **classes of potential
customer**, each product sells into one class's purchasing power plus a share of the other classes,
and the model must arrive as initial data that can be copied and edited.

**D81 — The customer class is the unit of economic reaction; the product is the unit of
competition.** Three classes (`customer_segment`, seeded as mass / middle / affluent) exist per
template. A product names the class it is made for (`product.target_segment`) and the share of the
other classes' market it also serves (`product.cross_segment_pct`). `income_elasticity` and
`trend_affinity` move from the product to the class, because they describe a customer, not a SKU. A
product with no target class keeps the pre-D81 pool (`base_demand` × demand index), so a template
written before this ruling plays exactly as it did.

**D82 — An economic event acts on classes, never on products.** `environment_event_effect` gives one
row per (event, class) with a **size** multiplier (how many buyers there are) and a **purchasing
power** multiplier (what each of them can spend). The card also carries a `category` (supply shock,
financial crisis, inflation, technology, demand shock, policy) so the workbench can say *inflation*
rather than "demand ×0.9". The old market-wide `demand_multiplier` and `target_product` stay for
backward compatibility only; the shipped deck sets the multiplier to 1.000 and drives everything
through class effects.

**D83 — The environment description is stored once per language, beside the mechanic.** An economic
environment a player cannot read is not an environment. `environment_text` holds the name and a
short description for a class, an event and a cycle position, in `en` and `zh`; a run reads its
class's language (`run.lang`), and the workbench shows the round narrative followed by the
descriptions of the events that have already started. This deliberately does **not** duplicate the
weather as one set per language the way D64 does for the R&D catalogue: a project is a *choice*
offered to the class, so the set itself must exist in its language; an event is a fact the class
cannot choose, and two weather systems that can drift apart would be a defect, not a translation.

**D84 — A run's weather is frozen, including what each card does.** The draw already fixed which
cards fire and when; `run_environment_event` now also stores their multipliers and their class
effects, and `run_round_economy` stores the round's narrative in both languages. Before this,
editing an event's numbers on a template silently changed a class that had already played, which
contradicts D18's promise that a run keeps its own economy — the words are part of that weather
because a player reads them. Customer classes are the opposite: they are read live every round, so
the classes of a template that already has sessions are refused for edit (`409 template.in_use`)
exactly like its product catalogue — copy the template to change them.

**D85 — The market of a product is its class's purchasing power plus a share of the others'.**

```
class size (units)      = base_size × seasonality × cohort scale
                          × demand_index(r) ^ class.income_elasticity
                          × (1 + trend_strength(r) × class.trend_affinity)
                          × product of active events' size multipliers
class purchasing power  = base_purchasing_power × product of active events' pp multipliers
market of product j     = size_s × pp_s + cross_segment_pct_j × Σ_{k≠s} (size_k × pp_k)
```

Share within that market is unchanged (price, quality, brand, reach, D34 whole-piece allocation):
the change is what the denominator *is*. Money is still not modelled directly — purchasing power is
an index on the class's unit pool, and price elasticity is what makes an over-priced product lose
share inside it. Competitive positions therefore add up to the class's affordable demand, not to a
number a trainer typed.

**D86 — A language owns its environment: cards and cycle, like the R&D catalogue (revising D83).**
D83 stored the mechanic once and put only the words in `environment_text`, arguing that two weather
systems could drift apart. That is right for the customer classes and wrong for the deck: adding a
third language then means editing rows in two tables, and a translator cannot author a card as a
card. The product owner's ruling: do what D64 did for R&D.

- `environment_event.lang` and `scenario_round.lang`; a duplicated set carries `<CODE>-<LANG>` codes
  (R&D uses `-ZH`), its own name, description, narrative — and, for a card, its own effect rows.
- The run's language, fixed when the session starts (D64), chooses the set. Both the timeline and
  the draw read only that set; nothing is translated at read time.
- The draw seeds on the **canonical code** (`EV-INFLATION-ZH` → `EV-INFLATION`), so the same run gets
  the same weather whichever language its class reads.
- **Customer classes stay one mechanic with per-language words** (D83 kept here). A class's size and
  elasticity are the economy; duplicating them per language would let two classes drift apart.
  `environment_text` now holds only `SEGMENT` rows.
- `scenario_round.description` replaces the text rows; `run_round_economy.description` is the frozen
  narrative in the run's language (the old `description_zh`/`label_zh` columns are gone).
- Language codes widen from `('en','zh')` to any two lowercase letters, so a new language is data: the
  builder copies a set (editor → Copy set) and translates it in place. No migration, no code.
- **The package's class-language list holds the languages the product ships** — English and Chinese —
  as a plain dropdown, as before D86. A template may carry further sets (a builder can author them),
  but a package is not the place to introduce one: the interface has no words for it.

**D87 — A product and a customer class keep one mechanic and take their words per language.** D86
duplicated cards and cycles because nothing keys on a card's identity. A product is the opposite: its
code is a foreign key in the economy — decisions name it, `company_product` holds stock against it, a
company profile lists it, `rnd_project.target_product` points at it. Duplicating products into
`P1-ZH` would fork that identity, and every decision would have to know which language's code it
means.

So products and classes follow the customer-class pattern D83 already used:

- `product_text(scenario_key, product_code, lang, name, description)` holds a product's words per
  language; `product` keeps the single row — code, price, cost, quality, demand, units per worker,
  target class, cross-segment share. `environment_text` holds the same for a customer class.
- A missing language falls back to the name the author wrote, so a new class is never blank and a
  builder translates only what they want to. This keeps the earlier ruling: an authored name is not
  machine-translated.
- The workbench reads the run's language: the product table and the class table are shown in it, while
  the decision still names the **code**, so nothing in the engine changes.
- The editor edits a small per-row "Translations" list (language + name (+ description)) rather than a
  duplicated mechanical row, which is what stops the numbers drifting between languages.

**D88 — The review page carries the three reports, built from stored statements (ruling 17 Nov 2026).**
The debrief and the trainee's results showed one breakdown of revenue, cost and cash. They now show
the three documents a manager is judged on, for the selected round:

- **Profit and loss** — revenue − cost of goods sold = **gross profit**; then payroll, marketing, R&D,
  quality, fixed cost, stock holding, hiring/firing and the market review as operating expenses;
  **operating profit** is the stored `profit`, to the cent.
- **Cash flow statement** — indirect: profit, then the change in inventories, gives **net cash from
  operations**; machines bought and R&D refunds received give **investing**; **financing is zero**
  because no borrowing is modelled. The net movement must equal closing cash − opening cash.
- **Assets and liabilities** — cash + inventories + machines at cost = **total assets**;
  **liabilities are zero** and shown as a line; **paid-in capital** (what the round-1 opening cash
  was) + **retained earnings** (cumulative profit) + **R&D refunds received** = **total equity**.
  The report carries a **difference** line, shown only when it is not zero.

Ruled with it:

- **Machines are capitalised, not expensed, and not depreciated.** That is what the engine does:
  `capacity_cost` leaves the bank and never touches profit. A balance sheet that ignored it would
  fail to balance, and a depreciation charge would move profit, prices and every frozen round. The
  reports state the rule on the page instead of leaving it implied.
- **R&D is expensed**, so no intangible asset is carried; a project's benefit shows up as lower cost
  or higher quality, not as a balance-sheet item.
- **R&D refunds sit in equity, with their own line.** The cash returns while `profit` keeps charging
  the full request, so folding it into profit would change the scoreboard and every stored round.
  The reports disclose it rather than reconciling it away.
- **A revaluation line appears only when it must.** On a weighted-average statement the inventory
  change is exactly what cash paid and profit charged, so no line appears. A pre-D79 replacement-cost
  statement, or a stored closing stock that did not carry into the next round, leaves a gap; it is
  shown as **inventory revaluation** (and in equity as a reserve) so the reports still tie, and the
  **difference** line exposes anything that does not.
- **One implementation, both roles.** The arithmetic lives in `FinancialStatementsService` and is read
  at `GET /api/runs/{runId}/teams/{teamId}/financials`, with the same access rule as the statements: a
  trainee reads their own company, staff read any company of their run. Rows are i18n keys, so the
  server carries no words and each language is a full set.
- **Every figure is read, not re-derived** from the template, so a report agrees with the round even
  after the template is edited, and historical rounds keep their own numbers.

**D89 — A decision may only name the products the company carries (ruling 17 Nov 2026).**
`company_product` is the company's line, and a company profile may seat a company with a subset of
the template's catalogue. Until this ruling the decision form offered the whole catalogue and the
validator checked names against the catalogue, so a company seated without a product could still send
units for it: the engine produced and sold it, and the closing stock was **lost**, because there was
no row to hold it. The statement then reported stock the next round never saw — the gap D88's reports
had to show as an inventory-revaluation line.

Ruled:

- **The form lists the company's own line.** `GET /runs/{id}/teams/{id}/context` returns the carried
  products only, so a trainee is never offered one they cannot decide.
- **The validator requires exactly the carried products.** Any other name is refused with
  `production.not_carried` (and `price.not_carried` / `quality.not_carried`); a name the template
  does not have at all stays `unknown_product`. Both are translated, so a Chinese class reads them in
  Chinese.
- **The engine plans, produces, settles and reports only the carried products.** A company that does
  not carry a product does not compete for its demand: it is left out of the attractiveness sum, so
  the market is shared among the companies that can actually serve it. The pool itself is unchanged —
  D-a..D-d on pool scaling stay open.
- **Bots decide for the carried line**, never for the catalogue: a bot that named a missing product
  would be refused at submit and the company would never play.
- **A company with no product rows at all keeps the whole catalogue**, so the rule cannot silently
  zero a company whose rows were never written (legacy data, or a hand-repaired database).
- **Historical statements are not rewritten.** They keep the numbers they were computed with; the fix
  applies to rounds computed from now on.

**D90 — The three reports travel in the exports, and the sheets list the company's own line (ruling
17 Nov 2026).** The review page carried the statements (D88) while the downloads still showed only
revenue, cost and profit, so the paper and the screen disagreed. Ruled:

- **The workbook gains a `Statements` sheet** — every line of every statement for every company and
  round, with Round, Company, Statement, Section, Line, Level and Amount, so it filters and pivots
  like the other sheets. Both scopes get it.
- **A trainee's PDF prints the three statements** on each round page, after the cost lines, each under
  its own name, with the same sections and rulings as the screen.
- **The class PDF does not.** It is the projection document for the room; the statements belong to one
  company at a time and are read on screen (a company's Detail) or in the workbook. Printing ten
  companies × eight rounds of statements would bury the debrief it exists to support.
- **`Submissions` and the PDF's "What you sent" list the products the company carries**, not the
  catalogue (D89 applied to the exports): no more always-zero `P3` columns for a two-product company.
- **Every figure and every word comes from the same services as the screen** — the statements from
  `FinancialStatementsService`, the labels from a full English and Chinese set inside the export, so a
  class reading Chinese prints Chinese.

**D91 — A trainee reads the three reports in a pop-up, from buttons beside "My results" (ruling
17 Nov 2026).** Stacking three long statements under a trainee's round made their results page scroll
past everything else. Ruled:

- **Three buttons** — Profit and loss, Cash flow statement, Assets and liabilities — sit beside
  *My results (PDF/Excel)* and the development report.
- Each **opens that report in a pop-up** for the round selected in the results table, with the report
  name, the round badge and the same sections and rulings the facilitator sees inline.
- The pop-up closes with **Esc**, the **Close** button, or a click on the backdrop, and traps nothing:
  a trainee is reading, not filling a form.
- **The facilitator keeps the inline panels** under a company's Detail — in a debrief the class is
  looking at one round on one screen, and three extra clicks per company would cost the session.
- **One card, two homes.** The pop-up and the inline block are the same component over the same
  endpoint, so they cannot drift; only the placement differs.

**D92 — A report is read as three rounds, and the trainee's PDF carries the whole term (ruling
17 Nov 2026).** A statement for one round alone invites the question it cannot answer: better or worse
than last month? Ruled:

- **Every report shows the current round and the two before it**, one column per round, the current
  round in the **right-most** column (D94), named above its figures. Round 1 shows one column, round 2
  shows two; the columns appear as the rounds exist, and never as empty placeholders.
- **Applies wherever the reports are read**: the trainee's pop-up, the facilitator's inline panels
  under a company's Detail, and the PDF. Same component, same columns.
- **A line a round did not carry prints as 0** in that column, so lines stay aligned across rounds;
  the "difference" line is still only printed when a balance sheet does not balance.
- **The trainee's PDF prints the three statements once, at the end, for every round of the term** —
  a column per round — instead of repeating the same statement on each month's page. The month pages
  keep what is genuinely per-month: summary, submission, cost lines.
- **The class PDF is unchanged**, as in D90: the projection document does not carry per-company
  statements.
- **The workbook already satisfies the rule**: one row per line per round, filterable by round.

**D93 — The reports have a contract, and it is checked in the build (ruling 17 Nov 2026).** The
question "are these numbers verified?" had two halves; only one was answered. Ruled:

- **The stored figures are audited independently** (D80): `scripts/audit_financials.py` re-derives
  them; last full run 2,650 checks over 48 company-rounds, PASS.
- **The reports are audited against their own contract**: a subtotal is the sum of the lines above
  it, the cash-flow sections add up to the movement, a balance sheet balances, the total line repeats
  the two sides, and the report agrees with the stored statement — `scripts/audit_reports.py` over the
  live API, last local run **688 checks over 16 company-rounds**, PASS.
- **The format is a checked contract too**: every row key exists in both language catalogues, sections
  come from a known set and stay in the ruled order, levels are 0/1/2, keys are unique, and keys carry
  their `pnl.` / `cash.` / `balance.` prefix.
- **The print layout is measured, not eyeballed**: `DebriefExportApiTest` asserts every text run of
  the generated PDF sits inside its page and that the statements page carries one column heading per
  round. Whether a page *looks* good stays a human call.
- **What is still not proven**: a file already downloaded is not tamper-evident — nothing signs it.
  The audit proves the file generated now matches the stored data.

**D94 — The current round sits in the right-most column (ruling 17 Nov 2026).** Accounting convention
for a comparative statement: the newest period is the last column, read left to right as time moving
forward. D92 had put the current round leftmost; corrected:

- The columns run **oldest → newest**, so the round the class is debriefing is the **right-most**.
- Same order in the trainee's pop-up, the facilitator's inline panels and the printed PDF — the PDF's
  all-rounds tables already ran Round 1 → last, so this brings the screen into line with the paper.
- The card's own heading names the current round; the figures the table is built from are the
  current round's, whichever column it lands in.

**D95 — The balance sheet's two sides stack (ruling 17 Nov 2026).** Side by side, each side of the
balance sheet had roughly half the card for a label plus one column per round — with three rounds that
is four columns in ~280px, and the figures crowded the labels. Ruled:

- **Assets on top, Liabilities and equity below**, each at full width, each keeping its own headings
  and its own total. The two totals can still be compared directly — they are one above the other.
- Applies in the pop-up, the facilitator's inline panels and the PDF alike (the PDF's all-rounds
  balance table was already a single stacked column of sections).
- The profit and loss and the cash flow are unaffected: one column per round, unchanged.

**D96 — A computed round opens itself on the trainee's screen (ruling 17 Nov 2026).** The results card
showed the newest round already, but only because nothing else was selected: a trainee who had clicked
an earlier round stayed there when the next one was computed, so the round they had just played sat
behind a click. Ruled:

- **The newest computed round takes the screen by itself**, including while the page is open — the
  ten-second poll notices the new statement and switches the detail to it.
- **Reading history still works**: clicking an earlier row selects that round and it stays selected
  until the next result arrives.
- **Nothing is scrolled or popped**: the detail is already on the card, under the summary; a class
  mid-decision is not interrupted by a jump.
- The three report buttons (D91) follow the same selected round, so they open on the round now shown.

**D98 — The class is judged by profit and capital, and positioned on price and quality (ruling
17 Nov 2026).** The table ranked by cash, which rewards hoarding and pure liquidation: a company can
top it by stopping investing. Porter's test of a strategy is the profit a position earns on the capital
it ties up. Ruled:

- **The trainee table ranks by cumulative profit**, the round's profit breaking a tie; **cash stays on
  the row as information**, because a facilitator still needs to see liquidity.
- **ROCE travels with the row**: cumulative profit ÷ (paid-in capital + machines at cost). Paid-in
  capital is the opening cash of the first computed round; machines are what the company has spent on
  capacity, since they are capitalised and not depreciated.
- **A position map sits above the debrief table**: price index against quality index, both relative to
  the class average (1.00), with the bubble area carrying market share — so "cheap supplier", "premium
  supplier" and "stuck between" are visible in one glance instead of inferred from a table.
- Weights come from the units a company actually sold, not its price list: a product it cannot sell
  must not place it.
- Both are read from stored statements; no figure is recomputed, and no migration was needed.

**D97 — Each product carries its own market share (ruling 17 Nov 2026).** The company share in the
scoreboard is `units sold × 100 / pool`, but the per-product table showed no share at all, so a company
winning one line and losing another could not see it. Ruled:

- **The engine writes `share` on every product line** when it settles a round: `sold × 100 / pool` for
  that product, the same denominator and rounding (two decimals) as the company share — so the product
  shares and the scoreboard share tell the same story.
- **Stored with the statement** (`round_statement.detail.products[].share`), so it is read, never
  recomputed; rounds computed before this field simply carry no share and the table prints **—**.
- **Shown in the per-product table** for both roles, and verified by the audit: `audit_financials.py`
  re-derives each product's pool and compares the stored share, and the report contract test refuses a
  share outside 0–100.
- The workbook and the PDF are unchanged: the statements sheet has no per-product rows, and the
  product lines there are the same stored lines.






**D99 — Shared inertia and the three Porter presets (ruled 17 Nov 2026; inertia implemented in
v0.18.0, presets next).** The
gap left by D98 is that an advantage is only worth *one month*: the allocator re-contests the whole
market every round, so a rival's price cut bites immediately and loyalty costs nothing to break. Ruled,
with the defaults fixed so the work is unambiguous:

- **Inertia**: `share_t = λ · share_{t−1} + (1 − λ) · contest_t`, then whole units by largest remainder
  as today. λ is a **template** value (`definition.inertia`), default **0.0** — `standard-v1` plays
  exactly as it does now, and a template that never sets it is unaffected.
- **Per product, not per company**: a company may hold a loyal line and a failing one, which is the
  more useful teaching case. The previous share comes from `round_statement.detail.products[].share`
  (D97), so **no schema change**.
- **λ = 0.5** in any preset that enables it.
- **Audit first**: `scripts/audit_financials.py` implements the same blend in its own allocator in the
  same slice, or every share check fails — that is the work, not an afterthought.
- **Presets** (cost-led / differentiated / focused) are template **data** cloned from `standard-v1`
  with overridden elasticities, fixed costs and segment reach, and each ships **only with its
  calibration table** (two plans, 6 rounds, seeded, winner by cumulative profit) in
  `docs/SCENARIO_CALIBRATION.md`. A preset that does not invert the winner is a bug, not a preset.
- **No historical statement changes**: both apply to rounds computed after their release.

Implemented (v0.18.0), with the two points the ruling left open settled this way:

- **The carried share is converted, not compared raw.** A statement stores the product's share of the
  whole pool (D97, a percentage); the blend works on the market the class serves, so the held share is
  divided by `(1 − baselineShare)`. Without that, turning inertia on in a template with a 15% baseline
  competitor would have handed every company 15% less than its history.
- **The blend is renormalised.** `held` is what a company *sold*, and a stockout makes it less than
  what it was allocated, so the weights can sum to slightly under 1. Normalising keeps the pieces
  adding up to the served market instead of leaving the shortfall unallocated. When λ = 0 — and when
  there is no previous round — the arithmetic is the pre-D99 code path untouched, so `standard-v1`
  remains bit-for-bit identical (proved by test, not by inspection).
- **Reachable where the builder works**: `inertia` is an editable template setting (0..1) in the
  editor, validated by both the settings API and the loader, so a template that carries a bad weight
  fails when it is saved rather than at round close in a classroom.
- **Audit evidence**: `scripts/audit_play.py --inertia` builds a copy of `standard-v1` at λ = 0.5 and
  plays six rounds on it; `audit_financials.py` re-derives every allocation with the same blend.
  Local run: **PASS — 6,014 checks over 104 company-rounds**, three classes (λ = 0 weighted, λ = 0
  replacement, λ = 0.5), every store tied.

**D100 — The three Porter presets are tuned economies, not new mechanics (ruled 17 Nov 2026).**
D99 fixed the loyalty mechanism; this fixes what each preset's *economy* must reward, so the class
can be taught one competitive position at a time. A preset is template **data** — the shipped
catalogue, classes, channels and timeline, with three numbers changed — cloned from `standard-v1`:

| Preset | price el. | quality el. | brand el. | marketing el. | fixed cost | reach | must reward |
|---|---|---|---|---|---|---|---|
| `preset-cost-v1` | 2.10 | 0.50 | 0.45 | 0.50 | 40 M | all classes | **COST** — cheap, full capacity, thin marketing |
| `preset-diff-v1` | 1.10 | 1.40 | 0.90 | 0.90 | 80 M | all classes | **DIFF** — premium price, quality spend, heavy marketing |
| `preset-focus-v1` | 1.30 | 1.00 | 0.50 | 0.60 | 35 M | own class only (`cross_segment_pct = 0`) | **FOCUS** — the premium line alone |

- **λ = 0.5 in all three**: a position has to be defended for more than one month, which is the whole
  point of D99. `standard-v1` keeps λ = 0 and is untouched.
- **Shipping is data only**: one migration seeding three scenarios and their catalogue rows by copying
  `standard-v1` (no Java path changes, no new engine branch), so a preset is a row a builder can copy
  or edit like any other template.
- **Acceptance is a measurement, in the build**: `PresetCalibrationTest` plays two plain plans for six
  rounds each, no events, and asserts the named plan wins on cumulative profit. The plans differ only
  in price posture, quality spend, marketing and (for FOCUS) which products the company carries —
  never in cleverness of policy — so what separates them is the preset's economy.
- **A preset that does not invert the winner is not shipped.** If the numbers have to move to make the
  named plan win, the numbers move, and the table above is updated to match what was measured.
- **The tables ship with the presets** in `docs/SCENARIO_CALIBRATION.md`: two plans, six rounds, the
  profit of each, for each preset.

**Amendment (ruled 17 Nov 2026, same day): the cost preset also owns its cost base and its market.**
Measured first, and the first attempt failed hard: with identical unit costs in one template, the
discount plan lost **946 M** while the premium plan earned **2,022 M**, and no value of the three knobs
above fixed it — a premium price multiplies a margin the discount plan does not have, and the workforce
caps the volume it can win. Both sides of `margin × units` had to move, so D100 now allows two further
data levers in a preset:

- **the mass product's cost base** — `base_unit_cost` 590 k → **430 k** and `units_per_worker` 40 → **48**
  in `preset-cost-v1`: a cost advantage *and* throughput, which is what cost leadership actually is;
- **the market the preset serves** — segment `base_size` ×3 in `preset-cost-v1` and `preset-focus-v1`,
  so a volume plan can reach its capacity and a niche plan can fill a factory.

**Shipped in v0.19.0** (migration `V35`), with the acceptance measured on the shipped rows —
`PresetCalibrationTest` loads each preset from the database and plays two plans against each other for
the whole term, no events:

| preset | plan | cumulative profit | revenue |
|---|---|---|---|
| `preset-cost-v1` | **COST** (P1 only, 0.92×, full capacity, 12 M marketing) | **+1,968 M** | 7,578 M |
| | DIFF (all three, 1.25×, quality 35 M, marketing 30 M) | +1,764 M | 7,076 M |
| `preset-diff-v1` | COST | −1,272 M | 2,312 M |
| | **DIFF** | **+882 M** | 6,717 M |
| `preset-focus-v1` | **FOCUS** (P3 only, 1.40×, quality 45 M) | **+1,720 M** | 6,734 M |
| | DIFF (all three) | +1,242 M | 6,717 M |

Each preset hands the win to the position it is named for, and the tables ship in
`docs/SCENARIO_CALIBRATION.md`. The presets are data: a builder can copy one and edit it like any other
template.

**D101 — The position map indexes like with like, and says when it has nothing to say (ruled and
built 18 Nov 2026).** D98 put price against quality on the debrief screen. Seen on a live class it was
wrong: four companies on one horizontal line, all left of average, because a company was placed by the
unweighted mean of its products' prices — so a mixed line looked cheap and a single cheap product
looked cheapest. That draws product-line breadth and calls it price posture, and it contradicts the
table directly beneath it.

Ruled, all six points shipped as v0.19.1 (frontend only — no engine, no migration):

- **Index per product, weight by revenue.** Each product is indexed against its own class average, then
  weighted by revenue booked (units sold when there is none, the plain mean when a company sold
  nothing). 1.25 now means "a quarter dearer than the field for the same product".
- **Name the four quadrants** — cheap / premium / stuck in the middle / priced above what they offer.
- **Number the axes** (0.70…1.30) and mark the average with its own dashed cross.
- **Bubbles at their true position, labels moved**: displacing a dot would lie about where a company
  sits, so only the label is nudged when two would collide.
- **Trails.** Every earlier computed round is placed the same way and drawn as a dashed path into the
  selected round; the current round is the trail's last point.
- **Admit a flat axis.** If every quality index is within 0.02, the vertical axis becomes brand (indexed
  the same way), the quadrant words follow it, and the caption says why. The live class that triggered
  the defect was exactly this case: nobody had bought quality.

Record with the measurements: `docs/POSITION_MAP.md`.

**D101a — A quadrant has to be a quadrant (18 Nov 2026, same day).** D101's first cut anchored the four
corner labels at 16 px inside the frame's top and bottom edges, so the lower pair ("stuck in the
middle", "priced above what they offer") sat **above the horizontal average line** whenever the bubbles
drew near the bottom of the scale — the words were pointing at the wrong half of the map. The labels are
now anchored around their own quadrant's centre (a quarter and three-quarters along each axis, the top
quadrants pulled inward so they clear the frame), so any bubble drawn below the average line is
unambiguously in a lower quadrant. Seen on the live v0.19.1 map, where all four companies sat low and
the label read as a contradiction.

**D104 — Units are whole pieces (ruled 19 Nov 2026, from a live class screenshot).** A trainee's production
screen showed stock of **48.67** and **73.85**. Nothing in the model asks for a fraction of a product: the
engine scales planned output whenever the workforce or the cash cannot carry the plan, and that scaling
rounded the result to two decimals. The fractional piece was an artefact of a rounding decision.

Ruled: **stock, production, demand, sold and lost are whole pieces, always.** Where output is curtailed the
engine **floors**, so a cut can only ever cost output — it never invents a unit for a company that is short
of capacity or money. Applied in both places a fraction could appear: the workforce cap and the cash cap.

Floored, not halved-up, is the deliberate part. Rounding 2.5 units to 3 would hand a company a unit it could
not pay for, and the accounting would no longer balance: materials were costed for 2.5. Floored to 2, the
materials are costed for 2 and the money kept is consistent with the stock held.

Two consequences worth recording:

- **Capacity figures stay fractional.** `capacityUsed` is worker-equivalents, not a physical count — a
  company can use half a worker. Only counts of things are whole.
- **An old assertion changed with the ruling.** `ScoringEngineTest` asserted that 100 cash buys "2.5 units,
  which sell for 250". Under D104 it buys 2 units, sells for 200 and keeps the 20 it did not spend. The test
  now says so and names D104, rather than being quietly adjusted.

Evidence: `WholeUnitsTest` drives the engine directly — a workforce-cut month, a cash-cut month and a month
that fits — and fails on the old code (it reported **580.65** units produced). The independent auditor
(`audit_financials.py`) re-derives the same figures and passes: **2,794 checks over 48 company-rounds**, with
**0 of 144 statement lines** carrying a fractional produced or closing stock.

**D105 — Historical fractional stock is left as it was played (ruled 19 Nov 2026).** A class played before
D104 holds stock of 48.67 and 73.85, written by the old rounding. The engine no longer produces such rows,
and the trainee's screen prints unit counts with zero decimals, so on that one class the screen now shows
**49** where **48.67** is stored.

Ruled: **the rows stay as they were played.** A finished class's statements are a record of what the class
experienced; rewriting them to match a later ruling would make the debrief disagree with what the trainees
were actually shown at the time. The alternative — a migration flooring `company_product.stock` and the
statement lines — was offered and rejected for that reason.

What this means, stated so nobody is surprised later:

- for a class played before D104, the displayed count and the stored count differ by up to a unit;
- the **stored** value is the truth; the screen rounds for reading;
- the independent auditor reads stored values, so it stays exact;
- no migration is added, and nothing may silently "repair" those rows.

**D106 — The market is a whole number of items, in the schema and at the editor (ruled and built
20 Nov 2026).** D104 made *stock* whole; the market it is drawn from was still `numeric(14,2)`, so the
schema said a market could be 4,000.5 items and the template editor would accept it. Nothing shipped
used a fraction — which is exactly why the rule was worth making real: it held only because nobody had
broken it.

- **Columns are integers.** `product.base_demand` and `customer_segment.base_size` become `integer`
  (migration `V38`).
- **Existing values are floored, not rounded.** A value of 4000.5 that was never meant becomes 4000,
  not 4001: the safe direction is fewer items, and `::integer` alone would round half away from zero.
- **The editor and the API refuse a fractional market** rather than flooring it silently, with a message
  naming the field and the value (`a market is a whole number of items, not 4000.5 — enter a count of
  items`). A trainer can still fix what they typed; a silent floor would hide it.
- **The derived market is whole and adds up.** Every path in `wholePool` ends in a whole number, and the
  pieces handed to the class equal the market they were drawn from.

**A second defect fell out of writing the test.** `saveProducts` passed an explicit `null` for
`income_elasticity`, defeating the column's default and failing NOT NULL — so a client that omitted the
field got `409 db.conflict, duplicate or conflicting value`, which tells nobody anything. It now defaults
to 1 (and `trendAffinity` to 0), and a request that leaves the field out is refused as a bad request
naming the field.

Evidence, on a full eight-round six-company session played by the audit strategies:

| Check | Result |
|---|---|
| Column types | `product.base_demand integer`, `customer_segment.base_size integer` |
| Independent auditor | **PASS — 2,794 checks over 48 company-rounds** |
| Fractional market values | 0 of 8 products, 0 of 12 segments |
| Fractional `produced` / `closingStock` / `demand` | 0 of 144 statement lines |
| Fractional company stock | 0 of 18 rows |
| Suite | 225 tests, 0 failures (`IntegerMarketTest` covers the type, the two refusals and the sum) |

**D107 — Channel labels say what the model does, and segment-specific channels are a design question
(20 Nov 2026).**

*Part 1, ruled and built:* the trainee's marketing card now states, in both languages, that **channels are
not market specific** — spend in any channel lifts share in every product and every class alike, and what
differs is reach (Trade reaches furthest), brand (Digital builds most, one round later) and diminishing
returns (~20 M in one channel is the neutral point). The guide gains §3.1c with the same table. This
matters because a trainer reading "Field sales" as the trade audience would be teaching something the
engine does not do.

*Part 2, proposed and NOT built:* segment-weighted channel investment. The owner asked for it; the design
is written out in **`docs/SEGMENT_CHANNELS.md`** with five numbered decisions (D-A where segment demand
lives, D-B the weight matrix's shape, D-C what happens to market share and the scoreboard, D-D whether
brand becomes per-segment, D-E re-calibrating the D100 presets) and a size estimate.

The structural point, which is why this is a design question rather than an edit: making reach vary by
segment moves the unit of competition from *(product)* to *(product, segment)*, so "your market share"
stops being one number — and the three presets' winners were measured on the current carving. The release
cannot ship until all three invert again under the new allocation, by the same acceptance test.

**D108 — Channel reach is weighted per customer class (ruled and built 21 Nov 2026).** D107 part 2, decided
on the five recommendations in `docs/SEGMENT_CHANNELS.md`: a full `reach_weight(channel × class)` matrix,
segment demand still governed by D81 (`target_segment` + `cross_segment_pct`), the company's market share
still one number with a per-class breakdown to follow, brand still company-wide, and all three D100
presets re-proved.

- **The switch is data.** `channel_reach` (migration `V39`) holds a weight per (channel, class). **No row
  means "use the channel's own weight"**, so every existing template — and any template a trainer never
  touches — produces *exactly* the pre-D108 arithmetic. That is what makes the change safe on a running
  class, and it is proved by the whole suite passing unchanged (223 tests before the new ones).
- **The allocation unit moved** from *(product)* to *(product, class)*: the served market is carved by
  class first (`segmentShares`, whole pieces by largest remainder), then each class is contested on its own
  attractiveness. A company can now be strong in the mass market and weak in the premium one on the same
  product — `SegmentChannelTest` shows two companies with identical spend trading places between P1 and P3.
- **The studio can set it.** The template editor's Channels tab gains one column per customer class;
  blank = the channel's own weight, **0 = the channel cannot reach that class at all** (a real strategy
  lever). Negative weights and unknown classes are refused with the field and value named.

**A defect found by writing the test, not by reading the code.** Publishing froze the template but did
**not** copy `channel_reach`, so a class played from a package silently fell back to segment-blind
marketing — the first "weighted" audit was really a second unweighted one, and passed for the wrong
reason. The copy is now part of `cloneInto`, placed **after** `customer_segment` (the foreign key needs
both sides to exist — the first attempt put it too early and the constraint rejected it). The test that
caught it asserts a frozen copy keeps its weights.

Evidence, two classes played six/eight rounds through the real API and re-derived independently:

| Check | Result |
|---|---|
| Suite | **227 tests, 0 failures** |
| Segment-blind templates | unchanged: 223 pre-existing tests pass untouched |
| Independent auditor, unweighted class | PASS — 2,106 checks over 36 company-rounds |
| Independent auditor, **weighted** class | PASS — within 4,900 checks over 84 company-rounds |
| Frozen copy | carries all 6 weights (verified in the database) |
| Refusals | negative weight → 400; unknown class → 400 naming it |

**D109 — A channel record carries its own market-specific parameters (ruled and built 21 Nov 2026).**
D108 stored the per-class reach weights in a side table (`channel_reach`), which made a channel a row in
two places: the record said what the channel was, and a second table said what it reached. The owner's
ruling: **the market-specific parameter belongs to the channel record**.

- **Three columns on `channel`** — `reach_mass`, `reach_mid`, `reach_premium` (migration `V40`) — instead
  of rows in a side table. A column per class rather than a JSON blob because the classes are a small
  authored set, the editor shows one column each, and a typo in a class code stays a key the code must
  handle rather than a silently ignored blob entry.
- **Null means "not class-specific here"**, falling back to `reach_weight` — the same meaning the side
  table's "no row" had, so existing templates behave identically.
- **Columns are mapped by class position**, not by hard-coded code: the loader and the migration both key
  them to the template's own catalogue order (`sort_order, code`). A tenant template that renamed its
  classes keeps its weights; the migration joins on position for exactly that reason.
- **A frozen package copy carries the weights by construction.** The D108 bug — `cloneInto` copying the
  side table before the classes existed, rejected by the foreign key — cannot recur, because there is no
  second table and no ordering to get wrong. `SegmentChannelTest.a_frozen_copy_carries_the_per_class_weights`
  now passes against the new shape without modification.

Evidence:

| Check | Result |
|---|---|
| Suite | **227 tests, 0 failures** (including the frozen-copy test) |
| Migration on a planted template | side-table weights 1.8 / 1.0 / 0.3 → `reach_mass` / `reach_mid` / `reach_premium` 1.800 / 1.000 / 0.300; the two non-class-specific channels stayed NULL; `channel_reach` dropped |
| Frozen package copy | channels carry their weights on the record (verified in the database) |
| Independent auditor, weighted class | **PASS — 4,900 checks over 84 company-rounds** |
| Migration guard | the `drop table` carries `-- migration:allow-destructive` with the reason: the contents were copied in the same transaction |

The side table is dropped in the same migration that fills the columns, so no deploy leaves the two shapes
coexisting.

**D111 — The shipped template's channels state their market-specific reach (ruled and built 21 Nov 2026).**
D109 put the market-specific columns on the channel record and D110 stopped the editor inventing values,
which left the shipped template with all nine blank: the feature existed and did nothing on the economy
every class is actually played on. The owner's ruling: **populate the shipped templates with a real,
defensible model; leave tenant drafts blank.**

**The numbers are derived, not tuned.** One rule, applied identically to all three channels:

1. A channel's market weight is its own `reach_weight` × a fit multiplier of **1.6 / 1.0 / 0.4**
   (best / neutral / poor), the same three multipliers for every channel so none is favoured by the
   encoding.
2. Fit follows the class's own economics, which the template already states: SEG-BASIC is the
   price-sensitive, largest class (income elasticity 0.40, pool 4,000); SEG-MID is neutral by
   construction (1.00); SEG-PREMIUM is the least price-sensitive, smallest class (1.80, pool 600).
3. Channel nature: **TRADE** (wholesale counters) reaches mass best and affluent worst →
   1.6 / 1.0 / 0.4. **FIELD** (personal selling) reaches affluent and middle best, price-driven mass
   worst → 0.4 / 1.0 / 1.6. **DIGITAL** reaches the largest, most reachable audience and the middle well,
   affluent least → 1.6 / 1.6 / 0.4.

| channel | own weight | mass | middle | premium |
|---|---|---|---|---|
| DIGITAL | 1.000 | 1.600 | 1.000 | 0.400 |
| TRADE | 1.200 | 1.920 | 1.200 | 0.480 |
| FIELD | 0.800 | 0.320 | 0.800 | 1.280 |

The middle column equals the channel's own weight for every channel **by rule**, which is what makes the
encoding auditable rather than arbitrary.

**What this changes, stated as a decision.** The channels used to differ only in *total* reach
(Trade 1.2 > Digital 1.0 > Field 0.8); they now differ in **who** they reach. The consequence is real and
visible: two companies with identical plans can win different numbers of pieces in different customer
classes. One existing test had assumed the opposite ("at most one whole piece on the largest-remainder
tie-break") and measured a 3.37 M profit gap; the assertion now says what it meant — the *purchase*
never touches profit, and any gap is bounded by the revenue difference the allocation produced.

**Scope.** `standard-v1` and the three presets cloned from it. Tenant drafts are left blank: seeding
somebody's own economy with numbers they did not choose would be unattributable, and the editor shows a
blank cell as "not specific to this market", which is the truth for a draft.

Evidence:

| Check | Result |
|---|---|
| Suite | **227 tests, 0 failures** |
| Presets still invert (D100's claim) | COST +1.97 bn vs +1.76; DIFF +0.88 bn vs −1.27; FOCUS +1.72 bn vs +1.24 |
| Segment-blind guarantee | proved on the shipped template **stripped of its weights**, so the guarantee does not depend on what we happen to ship |
| Independent auditor, shipped template in play | **PASS — 2,106 checks over 36 company-rounds** |
| Tenant drafts | 0 populated (verified against a live-shaped database) |

**D112 — Per-product market share, as pies the facilitator shows and shares (built 21 Nov 2026).**
The owner asked for a pie chart of market share, **one per product**, for every company including auto
players, in the facilitator's view, shareable to the class under the facilitator's control.

**What a slice measures.** The statement's own share (D97) is `sold / pool` — the *whole* market,
including the `baselineShare` (18 %) no company in the class can reach. A pie on that denominator tops out
around 82 % and every stockout vanishes from it. The pie is therefore drawn on the **served market**
(`pool × (1 − baselineShare)`), and whatever the class failed to supply is drawn as **its own slice**.
Slices then add to the whole by construction, and a stockout is visible as the gap it is — the single most
useful thing a facilitator can point at. Measured on a played class: slices + unserved = 100.0 % for each
product.

**Auto players.** A bot seat is a `player` row with `bot_profile_id` set, so it owns a company and holds
share like any other. It is labelled by **company name only** — never "(auto)", never the persona or
profile (owner's ruling). The colour still differs, so the facilitator can see a seat was machine-played
without the projection saying so.

**Two consumers, one definition.** `MarketSharePie` builds the slices; `MapShareService.piesOf` serves both
the shared page (`MarketShareDonut`) and the console (`GET /api/runs/{id}/market-pies`). The console panel
and a shared link therefore cannot disagree about a company's share, because they are handed the same
objects.

**Defects found while building:**

1. The shared page rendered the unserved slice and the coverage figure with **no label at all** —
   `debrief.coverage` was never added to the template's string list, and a stale template in the jar meant
   even the ones that existed came through empty. An unnamed slice is worse than no slice: the reader
   cannot tell a demand gap from a drawing error.
2. The first cut marked the auto seat `(auto)` in the legend, which is exactly what the ruling forbids. The
   test that asserted the marker was rewritten to assert its **absence**, and to assert no persona leaks.

Evidence: 232 tests, 0 failures (`MarketSharePieTest` covers sums-to-100, the stockout slice, the
company-only label, an idle company being absent rather than a zero wedge, and a sole holder drawing a full
ring); live run with a real bot seat renders 71 arcs across 4 rounds × 3 products, 12 unserved labels,
24 coverage labels, zero auto markers, zero personas.

**D113 — The donut's gap is drawn, and the legend states one number (reported 21 Nov 2026).** Two faults from
a facilitator's screenshot of the D112 pies:

1. **Two numbers after each company name.** The legend had *share of the served market* and *share of what
   was actually sold*. They are the same figure renormalised, and identical whenever nothing is unserved —
   which is why P3 read `55.8 / 55.8` and `44.2 / 44.2`. Ruled: **one number**, the served-market share, the
   one the ring is drawn from.
2. **The gap was missing from the circle.** The ring was swept on *units sold*, which always sums to 100 %,
   so a full circle sat beside a legend reading "24.9 % unserved". The separate grey coverage band at a
   different radius made it worse: it read as the unserved slice and was not in the pie at all. Ruled:
   **the ring is swept on the served market**, so demand the class could not supply is simply not drawn —
   the gap *is* the coverage — and the second grey arc is gone.

Proof on the reported numbers (Benny 32.2, Charlie 42.9, unserved 24.9): the ring now sweeps **270.4°**
with an **89.6° gap**, where it previously closed at 360°. `MarketSharePieTest` asserts the sweep, and the
assertion was checked against the old code — it fails there (360° vs 216°), so it is a real guard rather
than a test that agrees with whatever the code does.

The console pie (`MarketSharePies.tsx`) had both faults copied from the same design and is fixed the same
way, including a `data-sweep` attribute so the drawing stays measurable from outside.


**D114 — A channel's name is per language, its code is not (ruled and built 21 Nov 2026).** D87 gave products
a name per language beside a single mechanic. Channels were left out, and a facilitator running a Chinese
class reported the result: the customer classes read 大众市场 / 中端市场 / 高端客户 and the products 基础款 /
标准款 / 高端款, while the three marketing channels next to them still read "Trade", "Digital" and
"Field sales" — the one card that explains where the money goes, mixing two languages.

- **The product pattern, not the card pattern.** A channel's `code` is a foreign key in the economy: a
  submitted decision's marketing lines name it, and the market-specific reach columns key on it. Duplicating
  channels into `TRADE-ZH` would fork that identity, exactly as duplicating products would have (D64/D86/D87).
  So one `channel` row keeps the mechanic, and `channel_text` (migration `V42`) holds a name per language
  beside it.
- **A missing translation falls back to the authored name**, never to a blank label or the raw code. A class
  whose language the trainer has not filled in still reads something a person wrote.
- **Scope of the seed is the shipped templates** — `standard-v1` and the three presets — the same rule D111
  used for the market weights. Naming a tenant's own channel 数字营销 when they call it something else would
  be a translation nobody chose; the editor shows their English name in both languages until they author one.
- **The editor authors it.** Templates → Channels gains a translations cell per row (the same `TextsCell` the
  products tab uses), so the words are data and a new language needs no code.
- **A frozen package copy carries the names by construction**, because `cloneInto` copies `channel_text`
  after the channel rows it references — the ordering hazard D108 hit is not repeated.

The names follow what each channel *is*, as V41 and the guide's §3.1c table already describe them:
`DIGITAL` 数字营销 (paid digital media), `TRADE` 经销商渠道 (the wholesale/dealer counter), `FIELD` 直销团队
(people selling face to face).

**Amendment, same day (V43) — the defect the first release left on live.** The scope above excluded package
copies, and that is exactly where it mattered: checked on the live site after v0.27.0 deployed, the demo
tenant's finished Chinese class read the classes and products in Chinese and the three channels in English —
because its session plays a package frozen **before** V42, whose copy has no `channel_text` rows. The review of
a running class is the one place the defect is visible, so leaving it there was not a scope choice but a bug.

- **The seed now walks the lineage**: any template descended from a shipped one (package from `standard-v1`,
  package from a tenant draft that was itself copied from it, recursively) gets the words.
- **A renamed channel is still left alone.** The row is inserted only when the channel's authored name is
  still the shipped one (`'Digital'`, `'Trade'`, `'Field sales'`); a trainer who named it "Our dealer network"
  keeps their words in every language, and the fallback in `ChannelSpec.nameIn` does the rest.
- `ChannelNameTest.the_v43_seed_translates_a_package_frozen_before_it` executes V43's own SQL against a
  package stripped of its `channel_text` rows and renamed on one channel, and asserts both halves: the two
  untouched channels translate, the renamed one does not.

Evidence:

| Check | Result |
|---|---|
| Suite | **240 tests, 0 failures** (`ChannelNameTest` covers the shipped words, the fallback, the frozen copy and the trainee's context) |
| Live context, `lang='zh'` | `DIGITAL` 数字营销 · `FIELD` 直销团队 · `TRADE` 经销商渠道, read from the player's own context endpoint |
| Migrations | 42 |

**D115 — The debrief shows the award by customer class (D-C's option (i), built 21 Nov 2026).** Since D108 the
engine carves a product's served market by class and contests each class on its own attractiveness, so a
company can be strong in the mass market and weak in the affluent one on the same product. The debrief said
nothing about it: the scoreboard showed one share per company, and the per-class breakdown D-C recommended
"for this release" was never built.

- **The breakdown is a by-product of the allocation, not a re-derivation.** `allocateDemand` records, per
  (company, product, class), the pieces the class awarded and the class's own served market. The statement
  detail carries them as `classes`, so a facilitator disputing a cell is disputing the allocation that
  produced the round.
- **The number is demand won, and it is labelled that way.** Stock is a company-level pool: a company that
  stocked out still won its share of a class and sold fewer units, and that shortfall is already on its
  product line as lost sales. Apportioning *sold* units across classes pro-rata would have produced a second
  number nobody measured — the pie's stockout slice (D112/D113) exists precisely to avoid that kind of
  invention. So the table states **award ÷ class's served market**, and the caption says it is not sales.
- **Only a class-weighted template has a breakdown.** On a template that states no class reach the market is
  one pool; the engine records nothing rather than splitting a market nobody split. The panel simply does not
  render, and the auditor fails a round that carries class rows it should not.
- **Names are resolved server-side, in the run's language** (D87/D114): the console asks
  `GET /api/runs/{id}/class-shares?round=N` and receives product and class names already in the class's
  words — one shape for the browser, so no second translation table can drift.
- **Staff only.** What a rival won in a class is competition data; the endpoint refuses a trainee (`403`),
  the same rule the scoreboard follows.
- **Deliberately out of scope:** the PDF/Excel debrief keeps its company table. D-C(i) ruled "one new view",
  and the exports are the formal record; adding a class table to them is a separate decision, not a silent
  extension of this one.

Evidence, on an eight-round six-company session played by the audit strategies through the real API on a
**published package copy** of the shipped template (so the frozen-copy path is exercised too):

| Check | Result |
|---|---|
| Suite | **240 tests, 0 failures** (`ClassShareTest`, `ClassShareApiTest`) |
| Independent auditor | **PASS — 4,474 checks over 48 company-rounds**, including a new per-class check set |
| Reports auditor | **PASS — 2,064 checks over 48 company-rounds** |
| The new checks bite | one class award tampered by **+5** → **FAIL** with exactly two disagreements (`P1/SEG-BASIC class award: stored 67 computed 62`; `P1 class awards sum to the line demand: stored 68 computed 63`), restored → PASS |
| Endpoint | 9 rows (3 products × 3 classes); awards fill each class's market; a seated trainee gets `403` |
| Unweighted template | request returns `[]`; the panel does not render |

The sums are the point: a company's class awards equal the demand on its product line, and the companies in
one class equal that class's served market. Both are re-derived by the auditor from the decisions, so a
breakdown that merely looks plausible fails the audit.

**Amendment, same day — a round that predates the record says so.** Every round computed before 0.27.0 has no
`classes` in its detail, and no way to get one (the allocation was stored per product only). On the live demo
tenant that is every round: the first look at the deployed feature showed an empty table, which a facilitator
would read as a broken feature. The endpoint therefore answers `{weighted, rows}` rather than a bare list —
`weighted: false` means a class-blind template (nothing to explain, the panel stays away), `weighted: true`
with no rows means the round predates the record, and the debrief says exactly that instead of drawing
nothing. Verified on live: run 24's frozen package was published before D111, so its channels carry no class
weights at all — the engine allocated one pool for that whole class, and the endpoint correctly answers
`weighted: false` for it. A class-blind template therefore shows one muted line saying there is nothing to
split (D101's rule, that a chart with nothing to say must say so) rather than staying silent. `ClassShareApiTest.a_round_computed_before_the_breakdown_says_so_rather_than_looking_class_blind`
strips `classes` from a stored statement and asserts the distinction.


**D116 — Every word a person reads comes from a dictionary (reported 21 Nov 2026, built same day).** A
trainee in a Chinese class read the console with an English **"sign out"** in its header while the body
around it was Chinese. The suspicion was a language-resolution defect; it was not. `applySession` fires with
the run's package locale and the priority order (session language over the person's own, never persisted)
works exactly as D84 ruled — the strings were **literals that never went through `t()`**.

Found, and fixed as whole sentences rather than glued fragments:

| Where | What a Chinese class was reading |
|---|---|
| `PlayerConsole` header | `sign out`; `trainee` when no company name |
| `PlayerConsole` empty states | "No simulation yet" + body; "Not assigned to a company" + body |
| `PlayerConsole` R&D card | `CAPACITY UP 0.2 · 4 months · published estimate …`; `active`; `over-funding refunded` |
| `PlayerConsole` forecast | `Round 3 · STEADY · demand 1.02 · costs 1.00` |
| `PlayerConsole` commit bar | "of 178,242,280 · payroll, materials and holding cost are charged…" |
| `App` shell | "Run 1", `FINISHED · round 8 of 8 · 2 cycles of 4`, "Live", "waiting for events…", "Sign in again", "Signing in…" |
| `PeoplePanel` | `Admin`; "Initial password (8+ characters)"; placeholder "hand this to the trainee" |
| `TemplatesPanel` | the whole introduction paragraph |
| `DebriefSlides` | `Esc` |

- **Server codes are translated too, not just sentences.** `OPEN/FINISHED`, `EXPANDING/CONTRACTING/STEADY`,
  `UNIT_COST_DOWN/QUALITY_UP/CAPACITY_UP/ATTRACTIVENESS_UP` and the R&D status `ACTIVE/COMPLETED/FAILED` are
  sent as codes and were printed raw. `enumLabel(t, group, value)` in `i18n.tsx` reads the dictionary and
  **falls back to the code** when an entry is missing — an untranslated word is honest, `status.OPEN` on
  screen is not.
- **Fragments became sentences.** `{n} companies`, `· round {r} of {n}`, `cycles of {n}` were three text nodes
  with an expression between them; a translator could not reorder them. Each is now one key with
  placeholders (`app.runSubtitle`), so Chinese can read 第 4/8 轮 · 2 个周期，每周期 4 轮 without the English
  word order surviving in the middle.
- **The gate is a build step, not a habit.** `web/scripts/check-i18n.mjs` parses every `.tsx` with
  TypeScript's own parser (so multi-line text and nested expressions cannot hide) and **fails `npm run build`**
  on any JSX text node containing a word that is not allowlisted (brand and punctuation only). Proved to bite:
  a probe file with `<h2>This text bypassed the dictionary</h2>` exits **1** and is named by file and line;
  removing it exits **0**.
- **Coverage stated plainly:** the gate inspects JSX *text nodes*. Values glued into an expression
  (`t("…", { status: project.status })`) are not visible to it — that is how `active` and
  `over-funding refunded` survived the first pass. They were found by dumping the **rendered** screen and
  looking for Latin words, which is the check to repeat after adding a card.

Verified in a real browser (local Chromium, zh class, six companies):

| Surface | Before | After |
|---|---|---|
| Trainee header | `sign out` | 退出登录 |
| Trainee marketing card | — (channel names from D114) | 数字营销 / 直销团队 / 经销商渠道 |
| Trainee R&D card | `产能提升 0.2 · 4 months …`, `active` | 产能提升 0.2 · 4 个月 · 公布区间 …；进行中 · 超额投入将退回 |
| Facilitator run list | `1 · 6 companies` / `FINISHED · round 8/8` | 1 · 6 家公司 / 进行中 · 第 4/8 轮 |
| Facilitator run head | `Run 1`, `FINISHED · round 8 of 8 · 2 cycles of 4` | 第 1 场模拟；进行中 · 第 4/8 轮 · 2 个周期，每周期 4 轮 |
| Facilitator section | `Live` | 实时 |

Evidence: `evidence/d116-run-head-zh.png`, plus the DOM dumps taken while checking each string. `npm run build`
passes the new gate; `tsc -b` clean; nothing on the backend changed (no Java file touched since v0.27.2's
242-green suite).


**D117 — A show is pushed to the class's own screens (ruled and built 21 Nov 2026).** The owner's correction:
sharing the market-share pies was "not really sharing — it is displayed on the facilitator's projection with
a URL". The two existing paths prove him right. The frozen link (D103) is a page somebody has to open, and the
projector is the facilitator's own screen; neither puts anything on a trainee's console. The one feature that
does — the class-table reveal (D52) — is a per-run flag the trainee console reads.

So this is D52's sibling, for the pies:

- **The show is state of the run**, in `run_show` (V44): kind, round, who opened it, when. One row while a show
  is up; closing deletes it. A trainee who reloads, or signs in on another device, still finds it — that is the
  difference between a push and a pop-up that existed once on one laptop.
- **It arrives over the run's own event stream**, not on the next poll: `show.opened` / `show.closed` are
  published after commit, and the trainee console subscribes to those two names. Ten seconds of a facilitator
  talking over a chart nobody can see yet is a worse defect than it sounds.
- **It is a pop-up, and it can be dismissed and re-opened.** A trainee reading their own results should not be
  trapped behind a chart; closing it must not lose it while the facilitator still has it up, so a bar
  ("the facilitator is showing round N · show the chart again") stays until the show comes down. The pop-up
  rides on **every** screen the console can show — including the finished/waiting screen, which is exactly the
  state a class is in while the facilitator debriefs. (The first cut wired it only into the decision form: it
  worked on the facilitator's screen and nowhere else, which the browser check caught immediately.)
- **The same component draws it.** `ClassShowPopup` renders `MarketSharePies` — the debrief's own component,
  fed by the debrief's own endpoint — so the class and the projector cannot show different charts.
- **The pies are refused to a trainee while nothing is shown.** Until this ruling `GET /api/runs/{id}/market-pies`
  answered any seated player at any time; no screen called it, so the leak was invisible. A push that leaves
  that door open is presentation, not a push. The gate is the show itself: `403 show.not_open` otherwise, and
  the facilitator keeps their own view throughout.
- **The link stays.** `/share/map` is the record for phones and absentees; the push is for the room. They draw
  the same figures and neither replaces the other.

Evidence, driven in a real browser (local Chromium, zh class, six companies):

| Check | Result |
|---|---|
| Suite | **243 tests, 0 failures** (`ClassShowApiTest`: open reaches both trainees, survives a reload, pies 403 before and after, facilitator still reads them, non-facilitator refused, audit rows `SHOW_OPEN`/`SHOW_CLOSE`) |
| Push without reload | facilitator POST → trainee pop-up appeared via SSE: 第 3 轮各产品市场份额, 3 pies, arcs 51° / 60° / 73° |
| Reload mid-show | pop-up still up (state, not a socket) |
| Dismiss → re-open | pop-up closed, bar read 教员正在投放第 3 轮 · 再次查看图表, clicking it restored the pop-up |
| Close | pop-up and bar gone with no reload; trainee `market-pies` → **403 show.not_open**; facilitator → 200 |
| Audit | `facilitator_action` carries SHOW_OPEN / SHOW_CLOSE (the check constraint in V19/V21/V24 had to be extended — found by the test, not by a facilitator's 409) |

**A defect found while writing the migration's test:** `facilitator_action.action` is a check constraint
enumerating every session control. Recording a new action without extending it fails as a 409 on the button,
not a compile error — the first test run returned exactly that. V44 extends the constraint in the same
migration that adds the show.


**D118 — The class size is the package's number, not a constant (ruled and built 21 Nov 2026).** The owner's
question: *"we set an arbitrary number to the seats limit as 10 — can this be a default in a package instead
of hard setting it, so more players can participate in the same simulation?"* He was right that it was
arbitrary, and it was worse than one place:

| Where | What it was |
|---|---|
| **Decision 5** (1 Nov) | *"10 players per simulation, 5 concurrent simulations per tenant"* — a v1 sizing target (500 concurrent users), never a limit of the model |
| `RunService.createRun` | `teams.size() > 10` → `400 run.too_many_teams`, message citing decision 5 |
| `PackagesPanel` | `const MAX_COMPANIES = 10` — the builder's picker stopped accepting clicks |
| `MarketSharePies` | ten hues, assigned `PALETTE[i % 10]` — an eleventh company shared a colour in silence |
| `map-template.mjs` | **six** hand-written slots, commented "six being the largest class the console seats" |

The last one was a live inconsistency nobody had hit: a **7–10 company class could be created and played but
its shared position map could not be drawn** (`MapShareService` refused above the renderer's six). The engine
itself has always iterated over `teams.size()`, so nothing in the economics assumed ten.

**Ruling.** A class size is a teaching decision — the same economy prepared for twelve companies or for thirty
is the same economy — and the builder is the person who knows the room. So it moves onto the package:

- **`simulation_package.seat_limit`** (V45), `not null default 10`, `check (between 2 and 30)`. Every package
  published before the column keeps behaving exactly as it did; 2 is the floor because a class of one has no
  market to compete in.
- **The ceiling is thirty** (the owner's number), and it is the *console's* ceiling, not the package's choice:
  thirty palette hues and a position map that still reads. Both were extended to it —
  `PALETTE` now carries thirty distinct colours, and the map template is **generated** to thirty slots
  (six price tiers × five vertical steps) instead of six hand-written ones, which also closes the 7–10 gap.
- **Refused at publish, not at "start"**: a package whose company list is longer than its own limit answers
  `400 package.too_many_companies` while the builder is still preparing; a limit outside 2–30 answers
  `400 package.seat_limit_invalid`. A facilitator pressing start is the worst moment to learn a package is
  oversized.
- **The limit travels into the run**: `startFromPackage` passes the package's number to `startPrepared` →
  `createRun`, so `run.too_many_teams` quotes *this package's* limit. A run created without a package (a
  builder's tool) is bounded by the thirty-company ceiling directly.
- **Walk-ins cannot exceed the frozen class size** — the market was priced for that many companies, and adding
  one later re-prices everyone, which is the same reason bots are chosen at publish (D49).
- **Decision 5's sizing note is superseded, not deleted**: the concurrency target becomes 5 simulations × 20
  typical seats rather than × 10; the original ruling stands as the v1 target it was.

Verified: `SeatLimitTest` (twelve-seat package published and read back, a thirteen-company list against a
twelve limit refused, thirty-one refused at the ceiling, a twelve-company session seats twelve, a package-less
run of thirty created and of thirty-one refused with `max=30`). The map template reports **30 bubbles, 30
figures, 30 name slots**, and the full suite is green. The builder's Packages screen carries the field
(default 10) and the picker stops at it.


**D119 — One authority for the limits, projected to every layer (ruled and built 21 Nov 2026).** The
owner's demand, after D118: *"I want this to be controlled universally."* He is right that D118 fixed the
number without fixing the pattern — the ceiling `30` immediately existed in five places again
(`RunService.MAX_TEAMS`, `PackageService.MAX_SEAT_LIMIT`, `PackagesPanel` twice, the palette length, the map
template's derived slot count).

**Why it kept happening.** Each layer enforces the rule and cannot share a symbol with the others: a SQL
check constraint must enumerate values, Java re-checks because it does not trust the client, TypeScript
cannot call Java, and the map template is generated before any class exists. So the value is copied at every
boundary. Nothing failed when a copy drifted — each copy passed its own tests — and the two failures that
followed were both found the hard way: a 7–10 company class that could play but not share its map (10 vs
**6** slots), and `SHOW_OPEN` answering **409** on a facilitator's button because the audit constraint kept
its own list of valid actions in a third migration.

**The ruling.** A limit is a *datum with one owner*, not a constant repeated where it is needed:

- **`backend/src/main/resources/limits.json`** is the authority — `classSize {min, max, default}`,
  `maxRounds`, `maxProducts`. `Limits` (Java) loads it, **refuses to start** if it contradicts itself
  (a minimum above a maximum would make every class impossible, and that must fail at boot, not on a
  button), and every server use reads it: `RunService` (run ceiling and the package's cap), `PackageService`
  (publish bounds and default), `TemplateController` (rounds, cycle length, products), `InitDataService`
  (the seeder's clamp, which used to be a local `1..6`).
- **`GET /api/limits` answers the file itself** — the console reads its bounds instead of stating them.
  `PackagesPanel` no longer carries `max={30}`, `min={2}` or `default 10`; `TemplateEditor` no longer caps
  rounds at a literal `48`. With the limits unavailable the publish button is blocked with a reason rather
  than guessing.
- **The build reads the same file.** `map-template.mjs` takes the slot count from `classSize.max` (the
  `6 × 5 = 30` derivation was the last second statement of the ceiling), and a new gate,
  **`web/scripts/check-limits.mjs`, fails `npm run build`** when the copies disagree: template slots ≠
  ceiling, palette shorter than the ceiling, or the console not reading `/api/limits`. Proved to bite —
  setting `max: 29` in the file while the template still had 30 slots exits **1** and names both numbers;
  restoring it exits **0**.
- **The database is checked, not trusted.** `LimitsTest` reads `pg_get_constraintdef` for
  `simulation_package.seat_limit` and asserts the constraint's bounds equal the file's — a backstop that
  disagrees with the server is worse than no backstop, because the API then accepts a size the insert
  refuses.

This is the same shape as D116's i18n gate: a rule with one statement, a projection to every layer that
needs it, and a build step that fails when a second statement appears.

**Verified.** `LimitsTest` (file valid, DB constraint agrees, `/api/limits` answers the file, a
self-contradicting file refuses to load) and `SeatLimitTest` — 6 tests green; full suite **249**; `tsc` and
both gates clean; live `/api/limits` after deploy.


**D120 — Nothing on a screen is English-only, including the answers to a mistake (ruled and built 21 Nov
2026).** A round played by hand in the browser (the walk-through the owner asked for) exposed three places
where an identifier or an English sentence reached a Chinese class:

1. **The trainee header printed the outlook code beside its own label** — `第 1 轮，共 8 轮 · STEADY · 平稳`.
   `PlayerConsole` rendered `context.market.outlook` and then `context.market.trendLabel`. The label is the
   word; the code is an identifier and belongs nowhere on screen (the same rule as D116).
2. **A validation failure arrived in English.** A blank password answered `request.field` with Spring's
   `getDefaultMessage()` (`"password NotBlank"`), for which the console has no key, so the card read
   **"password is not valid."** while every label around it was Chinese.
3. **A bad sign-in answered with a sentence.** `AuthController` threw `401 "invalid credentials"`, which is
   prose, not a code — untranslatable by construction.

**Ruling.** An error is a code plus parameters, never a sentence; the console owns the wording, and a field
name is itself a word that has to be translated.

- `request.field` now carries `field` and `violation` (the constraint's kind: `NotBlank`, and so on) and no
  prose. The console renders `请填写密码。` for an empty box and `密码 不合法。` otherwise, translating the
  field name through its own catalogue (`field.password → 密码`).
- Sign-in failures carry **`auth.invalid_credentials`**. The answer stays identical for every cause —
  unknown tenant, unknown email, wrong password, deactivated account, an auto worker — so the form still
  cannot enumerate tenants or accounts (D47). Only the wording moved.
- `ApiExceptionHandler` treats a `ResponseStatusException` reason as a code **only when it looks like one**
  (dotted, lowercase). A raw sentence keeps the HTTP-shaped `error.http.NNN` answer, so prose can never
  masquerade as a translatable code.
- The project's own rule, restated: a message visible to a user in a class language must be data. If a
  screen can show it, the catalogue must be able to say it.

**Verified in the browser, in Chinese** — blank sign-in: `请填写机构代码。 · 请填写邮箱。 · 请填写密码。`;
wrong password: `邮箱或密码不正确。请检查后重试。`; an open round's header: `第 1 轮，共 8 轮 · 平稳`, no
code. `FieldProblemApiTest` pins both API shapes (a field problem carries no `message`; a bad sign-in carries
`auth.invalid_credentials`); suite **250** green.


**D121 — The market-share charts are pies, not donuts (owner's review, 23 Nov 2026).** The review verdict:
*"donut chart is not as acceptable as full pie chart by user — can we change the donut chart in system to pie
chart (without the hole at the middle of the chart)?"*

**Ruling.** Every market-share chart in the system is a **solid pie**: slices run from the centre, with no
inner radius and no hole. This covers all three places the chart appears, because they are drawn by two
renderers and both had to change:

| Where | Renderer | Change |
|---|---|---|
| Facilitator's debrief, trainee pop-up (D117) | `MarketSharePies.tsx` (React) | inner radius 54 → **0**; each slice is a wedge from the centre |
| Shared link `/share/map/{token}` (D103/D112) | `MarketShareDonut` (Java) | stroked ring → **filled wedge**; a lone 100 % slice is a full circle |

**What did not change — the arithmetic.** Slices still sweep on the **served market**, so demand the class
could not supply stays a visible gap in the circle (D113). A pie drawn on units sold would close the circle
and contradict its own legend; that defect was reported on 20 Nov and is not being reintroduced by a change
of geometry.

**The one thing the hole carried.** The coverage percentage was printed in the centre of the donut. A solid
pie has nowhere to put it, so it moved **under the chart** as a line of text (`市场份额: 90%`), which is also
where a reader looks for it. The claim that it is the same measurement is what the legend and that line
carry; nothing about the slice areas depends on it.

**Why the removal is safe, and why it needed a test.** The donut and the pie state the same legend and the
same percentages, so only a shape check tells them apart. `MarketSharePieTest` now asserts that each slice
path **starts at the centre** (`M110 110 L…`) and that no `stroke-width` ring is emitted — the assertion
fails on the old renderer and passes on this one.

**Verified in a browser, in Chinese, at all three surfaces**: the facilitator's debrief (3 pies × 6 wedges,
coverage 90 % / 72 % / 81 %), the trainee's pop-up for round 1 (same three), and the shared link (3 × 6
wedges, unserved 9.6 % / 28.0 % / 19.5 % visible as a gap). Suite **252** green.


**D123 — The pie has a base colour, and reading a bought report clears its box (owner's two items, 23 Nov 2026).**
Two separate reports in one message, both accepted as stated.

**1. "the pie chart should have a base colour."** Slices sweep on the served market (D113/D121), so a class
that under-serves leaves a wedge nobody drew. On the page that wedge was the *page background*: it read as a
rendering hole, not as demand the class failed to supply. Each pie is now drawn on a **base disc** — a full
circle under the slices, in the same colour as the legend's "unserved" swatch (`#2f3947`), so picture and key
agree by construction rather than by coincidence. Both renderers carry it (`MarketSharePies.tsx` and
`MarketShareDonut`), and `MarketSharePieTest` asserts the disc exists *and* is painted before the first wedge
— an SVG paints in document order, so a base drawn last would cover the pie it is meant to sit under.

**2. "when a report is purchased and plan submitted, the purchased market should be unchecked if the report
was read. this is a bug."** Correct, and it was a real double-charge waiting to happen: the purchase travels
*with the submitted plan*, so a box left ticked means "charge this month with the next submission". A trainee
who read the report and submitted again paid for it twice.

- Reading the report (`buyReport`) now **clears the box**. The reading itself is unaffected: the forecast
  stays on screen for the round.
- The disabled state of **查看已购买的报告** is unchanged in meaning (the report is bought with the plan), but the
  screen now **says why** it is dark — *请先提交本月计划——报告是随计划一起购买的* — instead of leaving a control that
  looks broken.
- What is deliberately *not* changed: the stored decision still records `marketReport: true` for that round.
  It is the record of what was charged; clearing it would erase the purchase from the ledger. Only the form's
  box is cleared, because the box is a statement about the *next* submission.

**Verified in the browser, Chinese, on a local class of four companies:** submit round 2 with the box ticked →
box stays ticked and the report button enables → press 查看已购买的报告 → five forecast rows appear and the box
goes unticked. Pie base measured in the DOM: `circle r=88 fill=#2f3947`, preceding the first slice path.


**D124 — A bought market review is sticky for its round (owner's ruling, 23 Nov 2026).** After D123 cleared the
box on reading, the owner asked how the report is priced and then: *"i want that closed"* — the hole being that
a trainee could read the forecast, edit the plan and re-submit with the box cleared, keeping the report and
withdrawing its cost.

**The pricing, stated once, because it was never written down:** the review is **paid per round**. One payment
buys that round's forecast — a forward horizon with a noise band — and it can be re-read as often as the
trainee likes until the round closes. It is not pay-per-view, and it is not a one-time unlock for the whole
simulation. The charge lands at settlement for each round whose plan carried the purchase
(`ScoringEngine`: `reportCost`).

**The ruling.** The purchase is a record of its own (`report_purchase`, unique per round and company), not a
flag inside the plan — the plan is replaced on every re-submission (`on conflict … do update set payload`),
which is precisely how the purchase got lost.

- Submitting with the box ticked **records** the purchase.
- A round already recorded **keeps it** on every later submission: the stored plan is rewritten with
  `marketReport: true` even if the trainee cleared the box (`DecisionPayload.withMarketReport`).
- The console is told (`context.reportPurchased`), so the box shows **ticked and disabled**, with the line
  *本轮已购买——费用已计入，重新提交仍保留；本轮关闭前可随时再次查看。* Nothing is left to infer from a control that
  silently refuses clicks.
- Unchanged: reading stays free after the purchase, and the round's own decision continues to record
  `marketReport: true` — it is the ledger line, not a preference.

**Verified.** `ReportPurchaseTest` walks the exact hole: submit without the review, submit with it, read it,
then re-submit without it — the purchase survives and the stored plan still bills it. The test was run against
the previous `RoundService` and **fails there**, so it pins the fix rather than describing it. In the browser,
Chinese, the bought section renders a ticked disabled box with the explanatory line. Suite **254** green.


**D125 — The box locks when the report is READ; the charge follows the final submission (owner's ruling, 23 Nov
2026).** The owner's wording, verbatim: *"it can be as it is but when a report is read, the purchase cannot be
unchecked. charging will be based on the final submission / timeout computation purchased status."*

That corrects D124, which made the purchase sticky at **submission**. D124 withdrew a choice before the trainee
had seen anything: a plan submitted with the box ticked could not be un-ticked, and the round was billed for a
report nobody had opened.

**The rule now.**

| Moment | Box | Billed at settlement? |
|---|---|---|
| Ticked, not submitted | changeable | no |
| Submitted with the tick, **not read** | changeable — untick and re-submit freely | only if the final plan still carries it |
| **Read** (查看已购买的报告) | **locked, ticked, disabled** | yes — every later submission of the round keeps it |
| Round closes / times out | — | the purchase status of the **final** plan decides |

- `report_purchase` is no longer "a plan was submitted with the tick"; it is **"the review was read for this
  round"** (V47 adds `read_at`). Reading is the only thing that writes it.
- Submit no longer records a purchase. It only *enforces* a read: a round that has been read is rewritten with
  `marketReport: true` even if the plan arrived without it, so the report cannot be kept while its cost is
  withdrawn.
- The console is told (`context.reportRead`) and renders the box ticked and disabled with the line
  *本轮报告已查看——费用已计入，下一轮可重新选择；本轮关闭前可随时再次查看。*
- `marketReport` is no longer `@Transactional(readOnly = true)`: the read is the act that locks the purchase, so
  it writes one row. Report and record are in one transaction — the report is not returned unless the lock was
  taken.

**Timeout is unchanged and needs nothing new:** a round that closes with auto-computed plans is settled from
whatever the last stored plan said, which is exactly "the final submission's purchased status". A trainee who
never read the report and let the round time out with a ticked box is charged, because their final plan carries
the tick — the same rule as a manual final submission.

**Verified.** `ReportPurchaseTest` (3 tests) pins all three behaviours: ticked-then-unticked before any read ends
unbilled; read-then-unticked stays billed; the report cannot be read before it is bought (`402
report.not_purchased`). Run against the previous `RoundService`, **all three fail** — the tests describe the rule
rather than the code. In the browser, Chinese: after reading, the box renders ticked, disabled, with the new
explanation. Suite **256** green.


**D126 — The server stores the plan it was sent; the lock on a read review lives in the form (owner's
objection, 23 Nov 2026).** On D125 the owner rejected one line of it outright: *"read → unticked → submitted →
still billed (stored plan rewritten to true); this is not acceptable and can be challenged by players if
implemented. when report is read (under the condition of purchased status), the purchase marker cannot be
unticked."*

He is right, and the objection is about evidence, not logic. D125 made the server rewrite a submitted plan to
`marketReport: true` when the trainee had sent `false`. A player can defend a plan they can see themselves
having submitted; they cannot defend a stored plan that contradicts it. The record must be exactly what was
sent.

**The rule now.**

- **The server stores the payload it received, unchanged.** No rewriting, no inferred purchase. `submit`
  simply persists the plan (the `withMarketReport` rewrite and its helper are gone).
- **The lock lives in the form**, which is the only route a trainee submits through. Once the review has been
  read *and* the round is bought, the box renders **ticked and disabled** and the draft itself carries the
  tick — so every payload that form can build includes the purchase. There is no state in which a trainee sees
  an unticked box and their submission bills them.
- **Before the read, the box is an ordinary choice**: tick, submit, untick, submit again, and the final plan
  decides the charge.
- Reading still requires a purchase (`402 report.not_purchased`) and still records the read (`report_purchase`),
  which is what the form uses to lock itself.

**What this gives up, and why it is acceptable:** an attacker who bypasses the console and posts a plan with
`marketReport: false` after reading is not billed. That is a trainee falsifying their own submission, not a
system charging for something unseen — and the console shows them, and their facilitator, the same ticked box
throughout the round.

**Verified.** `ReportPurchaseTest` now asserts storing-as-sent as the third behaviour (a plan arriving without
the tick after a read is stored without it), alongside the two unchanged ones: unticked-before-read ends
unbilled, and the review cannot be read before it is bought. In the browser, Chinese, the box renders ticked and
disabled, and pressing **更新决策** submits a plan whose stored `marketReport` is `true` — the form, not the
server, carried it. Suite **256** green.


**D127 — The paid review explains itself, and a channel says what its money buys (owner's enhancements, 23 Nov
2026).** Two requests: *"the purchased report, other than the indicators, should have more wording expanding the
impact of the indicators for clarity, it may be good to include some hint to the events that may occur in the
forecasted months"*, and *"for players to better bet on the channels' return, there should be a general
description of the activities that will occur within the scope of the channel. e.g. trade show, facebook
market, SEO, postals, etc"*.

**1. The forecast now reads.** Each month carries a sentence derived from its own numbers — demand up/down, cost
up/down, or steady — saying what it means for the decision in front of the trainee (plan more units, hold back,
raise price to protect margin, undercut to take share). The sentence is generated from the indices the report
shows, so the words cannot describe a movement the figures do not have; that is the defect being fixed, a bare
list of three numbers.

**The scheduled events are revealed for the near months.** The deck is drawn when the run is created, so its
schedule is knowable — and a forecast without it is a forecast of half the future. `GET …/market-report` now asks
for them (`forecast(..., revealEvents = true)`), showing name and description in the class language for the first
**two** months of the horizon (`EnvironmentService.EVENT_HORIZON`). Only what is *scheduled*: never the hidden
multipliers, and never on the public conditions endpoint, which still shows only events that have already begun.
A month with two events shows both — the honest answer, not a summary that hides one.

**2. A channel describes its activities.** `channel_text` gained a `description` (V48), alongside the name it
already carries per language (D114), and `ContextChannel` passes it to the console, which prints it under the
channel's spend box. The shipped three now say what the money actually buys:

| Channel | The activities |
|---|---|
| Digital | paid search and SEO, social and display, marketplace listings, email to the existing list — broad and fast, rented month to month |
| Trade | listing fees and shelf space, trade promotions and volume discounts, distributor incentives, point-of-sale display, the annual trade show — widest physical reach, slowest to move |
| Field | account visits and demonstrations, tender and procurement work, technical support, after-sales service, relationship building — narrowest reach, strongest effect on named customers |

An undescribed channel shows no paragraph at all: a template's own channels are somebody's own business, and
inventing activities for them would be a claim the template never made.

**One migration failure worth recording:** V42 seeded the **`zh`** channel_text rows only, so writing both
languages as inserts collides on `channel_text_pkey`. V48 updates the shipped rows in place and only where the
row still carries the shipped name — the same guard V43 used, so a trainer's own wording is never overwritten.

**Verified.** Local, Chinese, four-company class: the marketing section shows three channel paragraphs; the
review shows every month with its impact sentence, the scheduled events for rounds 2 and 3 with names and
descriptions, and the "what this purchase is worth" line. `Successfully applied 48 migrations`. Suite green.


**V49 — the descriptions reach packages frozen before them.** V48 wrote the three channel descriptions onto the
shipped templates, but a package published earlier is a *frozen copy* of its template (D33), so its channel rows
were copied before there was a description to copy. The live tenant demonstrated it exactly: `v0.30.0` deployed,
migrations 48, and the trainee's channels came back with `description = null`.

This is the same gap V43 closed for the channel *names* when V42 introduced those, so it uses the same walk — a
recursive `based_on` lineage from every shipped template, because one level misses the path the product actually
uses (draft → package → part). The guard is V43's as well: only rows still carrying the shipped name are
backfilled, so a trainer who renamed a channel keeps the trainer's words.

Verified on a **fresh stack with 49 migrations**: the seeded frozen package's three channels all carry their
Chinese description. (Also verified the hard way that a test on port 8099 must be checked for *which* jar is
answering — the first run reported success against the previous build, which was still holding the port.)


**D128 — The auto players play Porter's strategies, not tempers (owner's request, 23 Nov 2026).** The owner's
question — *"are we able to align their characteristics to competitive strategy of Michael Porter? currently we
have three levels of playing approach and three levels of aggressiveness within each approach"* — with the
correction he supplied himself: two of the three cells only *looked* like Porter, and the third had no lever
behind it at all.

**What was actually there.** Level × personality: level (LOW/MID/HIGH) is competence — forecast honesty, whether
the review is bought, whether a long game is affordable. Personality (CAUTIOUS/BALANCED/AGGRESSIVE) moved only
`pricePosture` and `targetShare`. So an AGGRESSIVE bot was a *discounter*, not a cost leader, and a MID-CAUTIOUS
bot sold at a premium while buying no quality — differentiation's price with none of its substance. And focus
had no mechanic: the policy split marketing pro rata to channel reach and produced whatever the company carried,
never touching `targetSegment`, which the scoring engine already honours.

**The ruling.** Two axes, and they are genuinely independent:

- **strategy** (new, `bot_profile.strategy`): **COST**, **DIFFERENTIATION**, **FOCUS** — what the company is
  trying to be.
- **level**: how well it executes that strategy. Level still gates what is *affordable* (a company without free
  cash does not start an R&D programme); it no longer decides what the company *wants*.

Each strategy moves the levers in different directions, not by different amounts:

| Strategy | Price | Quality / R&D | Capacity | Channel |
|---|---|---|---|---|
| COST | ×0.94 — undercut | appetite ×0.35, no quality buy | buys machines from its slack (bias 1.00) | reach-proportional |
| DIFFERENTIATION | ×1.06 — premium | quality ×1.60 and two points where affordable, R&D ×1.35 | barely (0.25) — grows through the crew | reach-proportional |
| FOCUS | ×1.02 | ×1.20 | 0.35 | **×1.80 toward the channel that reaches its class**, and it produces only the products aimed at that class |

The focus lever is not new machinery: `targetSegment` and `channel.reachFor(class)` already existed and the
engine already weights classes by them. The policy simply started reading them.

**Migration (V50).** `strategy` is added with a default and backfilled by the old personality mapping, so a class
running when the release lands keeps the opponents it had. Only rows still carrying the nine shipped key shapes
are spread across the three strategies.

**Builder UI.** A **Strategy** selector beside Level and Personality, in the creation form and as an editable
column, with a paragraph explaining what each one does — the panel is where a trainer meets the taxonomy, so it
is where the taxonomy has to be legible.

**Verified.** `AutoWorkerStrategyTest` holds level and personality fixed and varies only the strategy, asserting
they diverge where they are supposed to:

```
cost            price 987,000   quality 0          rnd 0   channels {DIGITAL 10.6m, FIELD 8.5m, TRADE 12.7m}
differentiation price 1,113,000 quality 120,000,000 rnd 0   channels {DIGITAL 11.9m, FIELD 9.5m, TRADE 14.2m}
focus           price 1,071,000 quality 0          rnd 0   channels {DIGITAL 9.9m,  FIELD 4.1m, TRADE 20.5m}
```

Focus takes 58 % of its budget through the channel that reaches its class; the neutral split takes 35 %.
`BotLibraryApiTest` pins the wire: a strategy is stored, editable, and a client that omits it keeps the old
mapping. Suite **258** green, 50 migrations on a fresh database, builder panel verified in Chinese.


**V51 — the builder-created library is re-spread across the three strategies.** V50 added `strategy` with a COST
default and re-spread only the nine *shipped* key shapes (`high_aggressive` and friends). The live tenant's
library was built through the builder, so its keys are `autowork-<level>-<letter>-<level>` — none of them matched,
every row kept the default, and the demo class went from a varied spread to **nine cost leaders**. The live check
after v0.31.0 deployed reported exactly that.

V51 re-spreads them at each level, matching strategy to the personality the row already carried:

| Key slot | Personality | Strategy |
|---|---|---|
| `-a-` | AGGRESSIVE | COST — it already pushed price and share hardest |
| `-b-` | BALANCED | FOCUS — now it narrows to one class |
| `-c-` | CAUTIOUS | DIFFERENTIATION — it already charged a premium |

Two guards, both deliberate: only rows **still COST** (a trainer's own edit is never overwritten), and only keys
of the builder shape matched against `^autowork-[a-z]+-[abc]-[a-z]+$`. The version is bumped on the rows it
changes, because history stays attached to the version that produced it.

**Proven before release, not after.** `.dev/v51_probe.py` rebuilds the live shape on a throwaway database — nine
builder keys all COST plus one trainer-named row — rewinds to the pre-V51 state, applies the migration's exact
statement, and asserts the outcome: **COST 3, FOCUS 3, DIFFERENTIATION 3**, `my-own-bot` still COST at v1 while
the nine moved to v2. The predicate was also checked against the real key strings first, including shapes it must
ignore (`high_aggressive`, `my-custom-bot`).


**D129 — The guided suggestion is neutral, not a strategy (owner's question, 23 Nov 2026).** The owner asked which
auto-worker style the opening-round suggestion for a *human* seat uses. The answer exposed a defect introduced by
D128: `BotRoundService.suggest()` called `profile("MID", "BALANCED")`, the two-argument overload, which resolves
through `strategyFor("BALANCED")` → **FOCUS**.

Before D128 that pair was the genuinely neutral cell. After D128 the same call means something specific: a company
that concentrates its marketing on one class's channel (channel bias 1.8) and plans **only that class's product**
(`lineLimit = 1`). So every trainee's guided suggestion narrowed the line and biased the budget — for a company the
guide knows nothing about, and regardless of what the class had been briefed to be. The seeded *opponents* used the
proper three-argument call, so bots and suggestions had diverged in kind.

**The ruling (owner: *"neutral"*).** A suggestion is a baseline, not a strategy. `AutoWorker.neutral()` sets every
strategy lever to its neutral value and is used by `suggest()`:

| Lever | Neutral value | Why |
|---|---|---|
| price posture | 1.00 | the template's price, with the policy's stock rule still applying |
| target share | 1.00 | the market's, not a push or a retreat |
| marketing | channel bias 1.0 | pro rata to reach — no concentration |
| line | `lineLimit = 0` | every product the company carries is planned |
| capacity | bias 0 | no machine race |
| quality / R&D / review | not bought | a suggestion does not commit money the trainee never chose |
| competence | MID | honest but unremarkable numbers |

`NEUTRAL` is a policy profile only and is never written to `bot_profile`, so V50's check constraint
(`COST`/`DIFFERENTIATION`/`FOCUS`) is unchanged.

**Verified.** `SuggestedPlanTest` asserts the whole line is planned, marketing holds each channel's reach share
(±3 pp), quality and R&D are empty, capacity and review are zero, and the price sits inside the stock-rule band
rather than at a cost-leader's −6% or a differentiator's +6%. A second test pins the defect itself: the old
`profile("MID","BALANCED")` **is** FOCUS and **is** focused, so the reason this ruling exists stays visible if
`strategyFor` ever changes.

**A wrong assertion, corrected rather than papered over.** The price check first failed at 1,050,000 against a
1,000,000 list — the policy's own rule, not a strategy: an empty shelf facing unmet demand prices up 5%. That is a
mechanic worth keeping in a suggestion, so the assertion allows the stock band and separately proves strategy did
not move the price.


**D130 — Personality is retired; the library is the nine strategy × level cells (owner's ruling, 23 Nov 2026).**
The owner asked whether 27 auto workers were needed to cover the permutations, and on review of that question
ruled: *"Reduce to 9 (strategy × level) and retire personality from the library."*

**Why.** Personality pushed price and share **on top of** the strategy, so several of the 27 cells contradicted
their own strategy: COST + AGGRESSIVE double-undercut, while DIFFERENTIATION + AGGRESSIVE resisted its own
premium. A matrix whose axes fight each other teaches noise, not strategy. The strategy now owns price and share
outright.

**What the nine are.** `cost-high`, `cost-mid`, `cost-low`, `differentiation-*`, `focus-*`. The name carries the
combination a trainer reads on a scoreboard: **"Cost leader (high)"**, **"Differentiator (mid)"**, **"Focus
player (low)"**. Each has the auto-worker identity that signs its decisions (D47).

**The column stays, and stays nullable.** `bot_profile.personality` is written on every historical
`bot_decision` trace — the "why did this company do that" record a class can be shown — so dropping it would
lose the record of which profile produced decisions already played. It is a legacy marker now: never written for
a new profile, never read by the policy. No row is deleted; profiles the pre-D130 library used are retired, not
removed, and only where no seat or person points at them.

**The API refuses a guess.** `strategy` is required and validated (`bot.strategy_invalid`); there is no
personality to fall back to, so a client that omits it is told, not defaulted.

**Two defects found by the suite, both real.**

1. **A migration cannot seed a library.** V52 first inserted the nine by cross-joining `tenant` — which matched
   nothing, because tenants are created by `InitDataService` *after* migrations run. It inserted zero rows and
   said nothing. The library is now seeded per tenant in `ensureBotLibrary`, and idempotent.
2. **A HIGH cost leader lost money while MID prospered.** Self-play measured **high −16.6m, mid +30.1m, low
   −31.6m** — the level ordering inverted. The cause was the R&D gate: `investsInRnd` was `high`, so the best
   cost leader bought an R&D programme while `qualityAppetite` (0.35) bought it no quality — it paid for the long
   game and shipped a product nobody preferred. R&D is the differentiator's game now:
   `strategy == DIFFERENTIATION`. Restored ordering: **high +90.4m, mid +30.4m, low −29.7m**, every level solvent.

The self-play harness itself was also wrong in a way worth naming: it varied level while leaving the strategy
implicit, so after D128 it was measuring two axes at once. It now holds the strategy constant (COST) and varies
only level, which is what a level test means.

**Verified.** Suite **261 green**. A fresh stack seeds **9 profiles** with the nine names. The builder shows a
Strategy selector and a Level selector, no personality; the decision trace shows strategy instead of personality.


**V52 follow-up — an existing tenant never re-enters the init path.** V52 retired the live tenant's
personality-era profiles (all nine, correctly: no seat pointed at them), but the nine new ones were only created
by `InitDataService`, which runs once, when a tenant is created. The deployed result was a tenant with **no active
auto-player library at all** — nothing to seat, nothing in the builder.

`BotLibraryRunner` closes it: on every startup, for each tenant whose active library is empty, an idempotent
`ensureBotLibrary` creates the nine. It is not a migration on purpose — tenants are created by application code
*after* migrations run, which is exactly why the SQL version silently inserted zero rows. It never touches a key
that already exists, so a trainer's own profiles are safe, and a seeding failure logs a warning rather than
failing the boot.

The lesson, recorded because it cost two attempts: **seed data that depends on rows the application creates
belongs in a runner, not a migration.**


**D131 — A tenant may remove a template it owns, softly (owner's request and rulings, 23 Nov 2026).** The owner
asked whether templates of the same tenant could be removed, noting that seeded ones cannot. The investigation
found the premise was half the story: **there was no delete at all**. `TemplateController` exposed `GET`,
`POST /copy` and seven `PUT`s, and the library offered only Copy and Edit — so a tenant that had copied the
shipped template three times kept all three for ever, and the seeded protection was one of *three* separate
mechanisms rather than the cause:

| Protection | Applies to |
|---|---|
| `tenant_id is null` ⇒ read-only | the shipped `standard-v1` and the three presets |
| `template.in_use` | any template a run has played |
| no endpoint at all | everything |

**The rulings: soft, and seeded templates stay undeletable.**

`DELETE /api/templates/{key}` — builder only — refuses `template.read_only` for a shipped template and
`template.in_use` (with counts of packages, parts and runs) for one still referenced, because
`simulation_package.template_key` and `run.scenario_key` refer to it by key. Otherwise it sets `scenario.deleted_at`:

- the template disappears from `listVisible` and so from the library and the package pickers;
- **the row and its whole catalogue stay** — products, segments, rounds, texts — because a run that played it
  still names it, and its statement detail reads from that key;
- **nothing is cascade-deleted.** The rule is enforced in the schema as well as the service: a check constraint
  refuses `deleted_at` on a row with `tenant_id is null`, so a shipped template cannot be soft-deleted even by a
  direct UPDATE (D119: one rule, enforced where it can be, agreed everywhere).

**A schema assumption caught by the tests, not by review.** The reference check first queried `package_part` and
inserted into `simulation_package` with a `created_by` column; the real objects are `simulation_package_part` and
`simulation_package.published_by`. Both tests failed loudly, which is the point of writing them against the real
schema rather than a remembered one.

**Verified.** `TemplateApiTest` (12 tests, 3 new): a tenant's copy leaves the library while its catalogue survives
in the row; `standard-v1` and `preset-cost-v1` both refuse with `template.read_only` and stay listed; a template a
package pins refuses with `template.in_use` and stays live. The builder shows **Remove** only on tenant-owned
rows, behind a confirmation that says simulations keep their own record. Suite **264 green**.


**D132 — Money has one owner, and the differentiator stops bleeding (owner's rulings, 23 Nov 2026).** Two items
from the open list: *"Cost: how can we improve?"* and *"Money Formatting: we should streamlined it."* Focus was
left as it is, and so were the persisted personality column and the thirty-company page.

**1. One money formatter.** There were three:

| Where | Was | Defect |
|---|---|---|
| `App.tsx` | `new Intl.NumberFormat("en-US", …)` | **hard-coded English** — a Chinese class saw English separators on the leaderboard while every other screen was localised |
| `SessionTools.tsx` | `value.toLocaleString(undefined, …)` | followed the **browser**, not the class, and showed **two decimals** where every other screen showed whole units |
| `i18n.tsx` | `money()` | correct — the class locale, whole units |

Both strays are gone; every amount now goes through `useI18n().money`, which is documented as *the* way to render
money. `grep` for `Intl.NumberFormat` or `toLocaleString` outside `i18n.tsx` now returns nothing.

**2. The cost strategy — the measurement inverted the question.** The owner asked how to improve COST. A new
calibration (`StrategyCalibrationTest`) plays two companies per strategy at the same level against each other for
a full run and prints what each commits in month one. The first measurement:

```
HIGH  COST    +7.3m/round   DIFFERENTIATION  -42.1m/round   FOCUS  +48.2m/round
MID   COST   +24.7m/round   DIFFERENTIATION  -23.9m/round   FOCUS  +69.0m/round
```

**COST was not the weak strategy — differentiation was, at every level**, spending **120m a month on quality**
(two points) against **2.5m of marketing** and a **6% premium**. That is 48× its marketing budget on a mechanic
whose payback is a price premium it never earns back. Two defects, both fixed:

- **The premium was bought on the wrong product.** The policy always upgraded `carries.get(0)` — usually P1, the
  cheap mass product — while the differentiator's price rise sat on the whole line. It now upgrades the product
  carrying the premium (highest base price).
- **The spend was never rationed against revenue.** Two points a month at 60m each is unaffordable at any level.
  A differentiator now buys **one** point a month: quality still compounds, but inside the margin the strategy can
  defend.

Result: **HIGH +16.5m/round, MID +35.3m/round** — profitable instead of loss-making, with quality still rising
every month.

**What this leaves, stated plainly.** FOCUS (≈+51m and +69m per round) still out-earns both, because it is the only
strategy that spends nothing on quality or capacity and simply sells to the class it targets. That is a real
finding, not a measurement error: **doing less is currently the most profitable play in this template.** The dial
is the quality cost per point (60m) or the price elasticity of quality in the scoring engine — both are economic
questions for the owner, not preferences for me to set. The owner asked about COST; the honest answer is that the
weakest strategy was a different one, and that the strongest is now "spend nothing", which is worth a decision.

**Verified.** Suite **265 green**. `StrategyCalibrationTest` asserts only that no strategy is hopeless (within a
factor of three of the best) at HIGH and MID — the ranking is reported, not gated, because it is information
rather than a contract. The money change is covered by `tsc` and the build; no test can assert a locale bug that
was only visible by rendering both.


**D133 — `quality-led-v1`: the preset where quality actually pays (owner's ruling, 24 Nov 2026).** After D132's
measurement showed FOCUS out-earning both other strategies by simply spending nothing, the owner asked for a
recommendation. The recommendation was **not to tune the bots** — they behave sensibly given the economics — but to
offer a variant economy, because `standard-v1` is what every historical run played and its numbers must not move.

**Why the shipped economy cannot support differentiation.** One quality point costs **60m a month** and lifts
attractiveness by `(51/50)^0.8 = 1.6 %`. A **6 % price cut** — which costs nothing to hold — lifts it by
`1.06^1.5 = 9 %`. Price is roughly thirty times more efficient per unit of money than quality, so the quality
strategy is playing a mechanic that cannot pay back at these rates.

**`quality-led-v1` — two knobs, nothing else:**

| Knob | `standard-v1` | `quality-led-v1` | Why |
|---|---|---|---|
| `elasticity.quality` | 0.80 | **1.30** | quality becomes a genuine differentiator |
| `qualityCostPerPoint` | 60m | **40m** | still ≈0.8 months of fixed cost per point — a real commitment |

It keeps the shipped market size, unit costs and **price** elasticity, so the only thing a class experiences
differently is what quality is worth. That is what makes it a usable A/B rather than another themed economy. The
copy is written with V35's generic jsonb clone, so a later column addition cannot silently drop data from it.

**Measured, both economies, HIGH level, two companies per strategy, 8 rounds:**

| Strategy | `standard-v1` | `quality-led-v1` |
|---|---|---|
| COST | +7.8m/round | +8.0m/round |
| **DIFFERENTIATION** | **+16.5m/round** | **+36.4m/round** |
| FOCUS | +51.5m/round | +50.7m/round |

Differentiation **more than doubles** in the new economy and closes most of the gap to FOCUS, while COST is
unaffected and FOCUS barely moves — exactly the shape a targeted economic change should have.

**The preset's own calibration passes.** `PresetCalibrationTest` extends to `quality-led-v1` with the same two
plans the shipped economy uses (COST vs DIFF) and asserts DIFF wins:

```
quality-led-v1 | COST | -944,326,974 |  quality-led-v1 | DIFF | +1,117,113,617
```

That is the strongest form of the claim: in the shipped economy those same two plans finish `+1,968m` and
`+1,764m` — the discount wins; in the quality-led economy the same two plans finish `−944m` and `+1,117m` — the
quality strategy wins by 2.1bn. The economics, not the bot, decide the winner.

**What is deliberately unchanged.** `standard-v1` and the three D100 presets keep their numbers. FOCUS keeps its
behaviour. No bot policy changed in this release at all — only a new template and the tests that prove it.

Suite **266 green** (TemplateApiTest now expects five shipped templates).


**D135 — Porter's grid is two by two: four positions, twelve profiles (owner's ruling, 24 Nov 2026).** After D130
retired personality and left three strategies, the owner asked the question that exposes the modelling error:
*"if Porter said focus has two dimensions, do we need two focus bots?"* Yes — and the single FOCUS position was not
focus at all.

**Why.** Porter defines the generic strategies on two axes — **scope** (broad/narrow) and **advantage** (cost/
differentiation) — giving four cells, not three:

| | cost advantage | differentiation advantage |
|---|---|---|
| **broad scope** | COST | DIFFERENTIATION |
| **narrow scope** | COST_FOCUS | DIFFERENTIATION_FOCUS |

The retired FOCUS had the *scope* and no *advantage*: it narrowed its line and then spent nothing, and D134's
four-cell measurement showed it winning **by restraint** (52m/round against 16m for the differentiator). A focused
firm in Porter's account earns a **margin in a narrow market by specialising** — it spends *more* per customer on
what that niche values, not less. Ours was a firm that found a gap in the economics.

**The four positions, and what each buys:**

| Position | Price | Quality/month | Capacity (month 1) | Line |
|---|---|---|---|---|
| COST | ×0.94 | 0 | 160m | full |
| DIFFERENTIATION | ×1.06 | 60m | 40m | full |
| COST_FOCUS | ×0.96 | 0 | 120m | one product, 1.8× channel bias |
| DIFFERENTIATION_FOCUS | ×1.08 | 60m | 0 | one product, 1.8× channel bias |

So the narrow positions keep their broad sibling's source of advantage and add the two things that make focus
work — the narrow line and the channel aimed at the served class.

**Measured, HIGH level, two companies per position, 8 rounds:**

```
COST +9.2m   DIFFERENTIATION +22.5m   COST_FOCUS +19.7m   DIFFERENTIATION_FOCUS +26.7m
```

Four profitable positions, each distinguishable, and the ranking is a story a class can read: differentiation
beats cost, focus beats broad at equal advantage, and the two focus positions sit on opposite sides of price and
quality. "Spend nothing and win" is no longer reachable — every position has an advantage to spend on.

**Consolidation first, then the positions (D135 part 2).** The strategy list was copied in six places across three
languages, and adding two positions would have meant six edits. It now lives in **`limits.json`** — the same single
authority D119 established — as `strategies.positions` (code + shape + advantage) and `levels`. The server
validates it at startup (refusing a repeated grid cell or an unknown shape/advantage), `Limits` exposes
`strategies()`, `levels()` and a derived `autoWorkerLibrarySize()`, `LimitsController` projects it, the builder's
picker renders whatever the list says, and the seeder generates the library from it. Adding a fifth position is now
one edit there plus the migration that widens the constraint.

**Personality is stated, not defaulted.** D130 stopped writing it, so rows carried whatever the column default
was — a value that looked meaningful and was not. It is now written as `'BALANCED'` on every insert and backfilled:
a marker that says exactly what is true, that the profile takes no stance on the retired axis. Nothing reads it —
`bot_decision` stores a `params` snapshot and no personality — so this is documentation, not behaviour.

**A schema limit the suite caught:** `bot_profile.strategy` was `varchar(20)`, which fits `DIFFERENTIATION` but not
`DIFFERENTIATION_FOCUS` (22). The insert failed with *"value too long"*, and nineteen tests went red. Widened to
`varchar(32)` before the constraint is restated.

**Existing data.** The retired `FOCUS` value maps to **COST_FOCUS** — it spent nothing on quality and bought no
capacity, which is nearer the cost variant. Recorded in the migration rather than left as a value the policy no
longer understands.

Suite **269 green**.


**D136 — A migration that moves rows must be proved against rows that need moving (incident, 24 Nov 2026).**
V55 took the production site down for fifty minutes. The cause was an ordering mistake, and the reason it reached
production was a verification habit that could never have caught it.

**What happened.** V55 shipped Porter's four positions. It added the new four-value check constraint *before* the
`update` that retires the three-value `FOCUS`, and it never dropped the old constraint at all, so even the correct
order would have been rejected. On the live database:

```
ERROR: check constraint "bot_profile_strategy_check" of relation "bot_profile" is violated by some row
```

Flyway recorded `success = false` for version 55 and the application refused to boot. The api container
restart-looped for fifty minutes; nginx answered 502 while the console stayed up on the new bundle.

**Why no test caught it.** Every test database is created by running the migrations from empty. An empty
`bot_profile` has no row on the retired value, so a constraint added too early passes. The migration could only
fail on a database whose *data* looked like production — which is the one database no test used. "273 tests green"
was true and irrelevant: none of them exercised the condition the migration existed to handle.

**The correction.** V55 now orders its work so the data is legal before any rule judges it:

1. widen `strategy` to `varchar(32)` (20 could not hold `DIFFERENTIATION_FOCUS`);
2. **drop** the old three-value constraint — while it stands it rejects the update that retires `FOCUS`;
3. map `FOCUS` → `COST_FOCUS`, state `personality = 'BALANCED'`, retire the old nine;
4. **only then** add the four-value constraint.

**The recovery, which no migration can perform.** Flyway runs `validate` *before* any migration, so a migration
file cannot repair a failed predecessor — the app never gets far enough to run it. The repair is one statement,
performed once against the live database:

```sql
delete from flyway_schema_history where version = '55' and success = false;
```

PostgreSQL rolled the failed V55 back completely (the schema was cleanly at V54), so removing the bookkeeping row
is the whole repair. A V56 was written to do this automatically and **deleted**: it cannot work, and a migration
that cannot work is worse than none, because it looks like a safety net.

**What now guards it.** `MigrationDataRulesTest` (4 tests, all **red** when the broken ordering is restored and green
on the fix) asserts the *data rules* rather than the schema: no profile plays a retired position, every profile
states the retired-personality marker, the constraint accepts the four positions and refuses a fifth, and the
column is wide enough for the longest position name. Restoring the old statement order fails all four.

**The lesson, stated as a rule.** A migration is not verified by a passing suite or a clean boot on a fresh
database. It is verified against a database holding **the rows the migration has to change**. Every earlier
migration in this project that only added a table passed that test by accident — there were no rows to move. V55
moved rows, and that is exactly where the habit failed.
