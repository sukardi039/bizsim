# Simulation model — v1 engine spec

Status: **working spec, 2026-11-02.** The seven rulings in `DECISIONS.md` §"Domain rulings —
2026-11-02" are locked. Everything marked **PROPOSED** is my recommendation, needs one line of
sign-off, and is cheap to change now / expensive after S2.

Open items that block S2 code are collected in §11. Nothing else in this file is a question.

---

## 1. Session shape

```
tenant
  └── run (one session, one class)            up to 10 companies, 8 cycles
        ├── company 1 .. company 10           one player each (D1)
        │     ├── product lines P1..Pn        state, not input (D3)
        │     ├── staff by function
        │     ├── R&D projects in progress
        │     └── cash, brand, stock
        └── round 1 .. 8                      one round = one month (D4)
```

- Companies: 1–10 per run, hard cap 10 (already enforced in `RunService.createRun`).
- Cycles: scenario defines `rounds` (default **8**, allowed 4–12). A facilitator may end a run
  early; a run never exceeds the scenario's count.
- Round length: still **20 minutes** as a facilitator default — no ruling yet, does not block code
  because it is a console control.

## 2. Company state (persisted between months)

| Field | Meaning | Initial value |
|---|---|---|
| `cash` | bank balance, the hard constraint (D7) | from scenario |
| `brand` | 0–100 index, decays every month | 50 |
| `product line` | per product: unit price, quality/tech level, unit cost, stock | from scenario |
| `staff` | headcount by function: production / sales / R&D / admin | from scenario |
| `r&d portfolio` | projects: invested so far, status, completion round | empty |
| `status` | ACTIVE / INSOLVENT | ACTIVE |
| `cumulative` | revenue, cost, profit, lost sales | 0 |

Everything here is written only by the round-closing transaction (decision A / 4b).

## 3. What a company submits each month

One submission per company, not per role. **PROPOSED** payload:

```jsonc
{
  "production": [ { "product": "P1", "units": 1200 },
                  { "product": "P2", "units": 400 } ],

  "price":      [ { "product": "P1", "unitPrice": 100000 },
                  { "product": "P2", "unitPrice": 250000 } ],   // see Q-A

  "marketing":  [ { "channel": "DIGITAL", "spend": 20000 },
                  { "channel": "TRADE",   "spend": 15000 },
                  { "channel": "FIELD",   "spend": 5000  } ],

  "manpower":   { "production": 20 },          // deployed to production this month
                                               // (total headcount and payroll come from `staff`)

  "rnd":        [ { "project": "RND-COST-P1", "invest": 15000 } ],

  "capacityPurchase": 0,                        // optional, see §10
  "marketReport": false                         // buys this month's forecast (D18)
}
```

Rules on the payload:
- Server validates the whole payload in one pass and returns **all** errors at once, not the first.
- Unknown product/channel/project codes are rejected.
- Only **one** row per product / channel / project.
- `price` is pending Q-A. If price is not a decision, that block is dropped and the price is
  scenario-fixed — but then market share ignores price and the game is "produce the most".

## 4. Monthly resolution pipeline (fixed order)

Order matters and is part of the contract; the debrief must explain the same order.

1. **Load state** — cash, brand, product lines, stock, staff, R&D portfolio.
2. **Validate affordability** — committed cash = materials + payroll + marketing + R&D + capacity
   purchase + fixed overhead + holding cost on opening stock. If committed > cash → submit is
   rejected with a per-line breakdown. Reject at submit, not at close (see Q-D for the alternative).
3. **Production** — per product:
   `produced = min(planned, capacityLeft, materialsAffordable / unitCost)`
   where `capacityLeft = productionWorkers × unitsPerWorker(product) × techMultiplier(product)`.
   Anything the company planned but could not build is reported in the debrief as "unbuilt".
4. **Costs accrue** — materials (on produced units), payroll (full month, all functions),
   marketing, R&D, fixed overhead, holding cost.
5. **Market** — per product, compute each company's attractiveness and take its share of that
   product's demand pool (§5). `demandUnits = share × pool`.
6. **Sales** — `sold = min(demandUnits, openingStock + produced)`. Unsold demand is **lost** and is
   not redistributed to rivals in v1. Revenue = `sold × price`. **COGS** is charged on the units sold
   at the cost basis the template names: `WEIGHTED` (default for new packages) charges the average
   price actually paid for the units in stock, `REPLACEMENT` charges this month's unit cost. The
   difference is not cosmetic: under `REPLACEMENT`, stock carried across a cost rise is expensed at a
   price the company never paid, so cash and profit stop agreeing even when the shelf starts and ends
   empty (D79).
7. **R&D resolution** — projects that reached their hidden expected cost complete; advantage applies
   from the next month (§6).
8. **Brand update** — gain from marketing, minus decay (§5.3).
9. **Cash settle** — cash = cash − costs + revenue. `cash < 0` → status INSOLVENT (Q-D).
10. **Carry stock, write KPIs, write `round_result`** — one transaction, deterministic.

## 5. Market and demand (PROPOSED — replaces worksheet Q2)

### 5.1 Demand pool per product

`pool_j(round) = base_demand_j × seasonality(round) × companies / 10`

- `base_demand_j` and `seasonality[1..8]` are scenario data.
- **Cohort scaling is implemented and deliberate**: the demand table is sized for 10 companies
  (decision 5). Without scaling, a 3-company run faces a market about three times its combined
  capacity, sells out every month, and market share measures only factory size. Scaling keeps the
  demand-to-capacity ratio constant from 2 players to 10. `ScoringEngine.REFERENCE_COMPANIES`.
- Every product has **its own pool**; products do not cannibalise each other in v1.
- A **fixed synthetic competitor** ("Market Average") always takes part with constant price,
  quality and brand, so a 2-company run still has a market and a 10-company run is not a pure
  zero-sum. Suggested target share: 15–20% of each pool.

### 5.2 Share

```
attractiveness A_ij = (price_ref_j / price_ij)^1.5          price elasticity
                    × (quality_ij / quality_ref_j)^0.8      tech/quality from R&D
                    × (brand_i / 50)^0.6                    brand has memory
                    × channelReach_ij^0.7                   marketing works, with diminishing returns
share_ij = A_ij / (Σ_k A_kj + A_baseline_j)
```

- All exponents are scenario data, so the trainer can tune "price matters more" without code.
- `channelReach_ij` = Σ over channels of `spend_c × reachWeight(product_j, channel_c)`,
  normalised against a reference spend. Diminishing returns come from using `sqrt(spend)` inside
  the reach term; doubling the budget must not double the reach.
- Share is computed **before** stock is checked, deliberately: a company with no stock still wins
  demand and then loses it. That is the stockout lesson and it must be visible in the debrief.

### 5.3 Brand

```
brand = brand × (1 − decay) + Σ_c α_c × sqrt(spend_c)      capped 0..100
```

- `decay` ≈ 0.10/month, `α_c` per channel, both scenario data.
- Marketing must be paid **every month** to hold brand — that is the intended lesson.

### 5.4 Environment — the economy moves market size (D18)

```
position p = ((r - 1) mod cycleLength) + 1        cycle k = (r - 1) / cycleLength
demand_index(r) = template[p].demand × (1 + cycleDemandDrift)^k
cost_index(r)   = template[p].cost   × (1 + cycleCostDrift)^k
wage_index(r)   = template[p].wage   × (1 + cycleCostDrift)^k

market size for j = base_j × seasonality × companies/10
                    × demand_index(r) ^ income_elasticity_j
                    × (1 + trend_strength(r) × trend_affinity_j)
                    × (demand multiplier of any event targeting j)
unit material cost = base cost × cost_index(r) × (event cost multipliers)
payroll            = salary × headcount × wage_index(r) × (event wage multipliers)
```

- The **cycle template** lives in `scenario_round`: one row per cycle *position*, missing positions
  are neutral (1.0). A run chooses `cycles` at creation (default 2) and may pass an explicit `rounds`
  to stretch the session; the template is expanded into `run_round_economy` and frozen, so two runs
  may cover different numbers of cycles without touching the scenario.
- `income_elasticity` and `trend_affinity` live on the product: 0.40 / 1.00 / 1.80 and
  −0.50 / 0.00 / +0.50 in scenario #1. Elasticity is the economic cycle; affinity is the consumer
  trend, so a "trading down" year moves demand from premium to basic without any per-product
  forecast being produced.
  A 10% fall in the demand index costs the premium line 18% of its market and the basic line 4%.
  That is the "different customer groups react differently" requirement, in one number per product.
- The event deck lives in `environment_event`. Each card has a window, a probability, a duration and
  three multipliers (optionally targeted at one product). At run creation, `EnvironmentService`
  draws each card with `Seed.uniform(runId:env:code)`; if it fires, the start round is drawn inside
  the window and frozen in `run_environment_event`. Trainees cannot see the deck or the draw; the
  debrief can, and a replay of the run produces the same schedule.
- **Paid market review.** `marketReport: true` in the monthly decision costs
  `marketReportCost`; `GET /teams/{id}/market-report` then returns current indices, the active events
  that have already started, and `forecastHorizon` rounds of forecast with ±`forecastBand` noise.
  The forecast reads the published timeline only — never the deck — so buying it cannot leak an
  event. It can still be wrong by up to the band, which is the point.

## 6. R&D — ruled (D10, D17)

A project is a row of scenario data:

```
code, name, targetProduct, hiddenCost, hintBand(±%), advantageType, advantageValue, minMonths
```

- **Proposal:** the facilitator/scenario offers 3–5 projects at run start; the same slate is open to
  all companies, so the choice is strategic, not lottery.
- **Funding:** a company allocates dollars per project per month. Investment accumulates.
- **Fixed duration (D10).** Every project has `duration_months`. Investment accumulates while it is
  active; the outcome is evaluated **at the end of that duration**, not when the money crosses the
  threshold. A project funded in its last month is evaluated like any other.
- **Outcome (D10, D17).** `ratio = cumulativeInvested / hiddenCost`, and
  `progress = (ratio − 0.80) / 0.20`.
  - `ratio ≥ 1.00` → **COMPLETED at full benefit**, always, whatever the curve.
  - `partial_benefit = false` → **100% or nothing**: any ratio below 1.00 fails.
  - `partial_benefit = true` and `ratio < 0.80` → **FAILED**, money gone.
  - otherwise the curve decides the factor:

| Curve | Factor at 85% funding | 90% | 95% | Shape |
|---|---|---|---|---|
| `LINEAR` | 0.25 | 0.50 | 0.75 | every dollar buys the same |
| `POWER` (exponent 2.0) | 0.0625 | 0.25 | 0.5625 | the last dollars buy the most |
| `POWER` (exponent 0.5) | 0.50 | 0.71 | 0.87 | early dollars buy the most |
| `CHANCE` | 25% chance of full | 50% | 75% | all or nothing, odds rise with funding |

- **`CHANCE` (D17).** The "dice" is a hidden threshold drawn uniformly in `[0.80, 1.00)` from a
  SHA-256 digest of `teamId:round:projectCode`. Success if `ratio ≥ threshold`, full benefit or none.
  Two consequences a facilitator should know: the odds are exactly the funding ratio, and a
  recomputed round gives the same answer, so a disputed month can be replayed in front of the class.
  The threshold is recorded in the round statement, so the debrief can say "you had 60%, the bar was
  94%".
- **Over-funding (D17).** `overfunding = REFUND` returns everything above the hidden cost at
  completion; `LOST` lets the programme keep it. Only completed projects can refund — a failed
  project has already consumed what it was given.
- The **terms** of every project (`partial_benefit`, curve, exponent, overfunding, duration, cost
  band) are public in the decision context; only `hiddenCost` is withheld.
- **No dice in the default model.** `LINEAR`/`POWER` are pure arithmetic and a trainee can explain a
  failure. `CHANCE` is opt-in per project and is seeded (above), so it stays reproducible even though
  it is uncertain. "You rolled badly" is never the whole explanation: the statement records the bar
  you had to clear.
- **Hint:** each project shows an estimated cost **band** (e.g. "Rp 90–130 million"), derived
  deterministically from `hiddenCost` and `hintBand`. No noise, no RNG.
- **Overshoot:** follows the project's `overfunding` policy (D17) — `REFUND` gives the excess back at
  completion, `LOST` keeps it. The shipped slate is `REFUND`.
- **Advantage types for v1** (each maps to an existing variable, so no new engine):
  1. `UNIT_COST_DOWN` — unit cost of product j −x%.
  2. `CAPACITY_UP` — units per worker for j +x%.
  3. `QUALITY_UP` — quality/tech level of j +x points.
  4. `DEMAND_UP` — base demand weight of j +x% (a "market development" project).
- A project that is abandoned mid-way loses its money. That is the risk the trainee is buying.

## 7. Cash, credit and failure

- No loans, no equity, no supplier credit in v1 (D7).
- All commitments are checked at submit against `cash` (§4 step 2).
- Payroll is monthly and unavoidable while staff exist; hiring costs one month of salary upfront,
  firing costs one month of salary as severance. **PROPOSED.**
- Insolvency rule (**D11**): `cash < 0` → status `INSOLVENT`, **production is forced to 0** next
  month, payroll, fixed cost and holding cost still accrue, and stock keeps selling. If cash returns
  to ≥ 0 the company goes back to `ACTIVE`. It is never removed from the run and never deleted —
  the debrief needs its history. Ranking: insolvent companies are ordered last, by cash.

## 8. KPIs for the debrief (PROPOSED)

| # | KPI | Unit | Source |
|---|---|---|---|
| 1 | Net profit — this month and cumulative | currency | revenue − all costs |
| 2 | Market share | % of units sold, per product and total | sold / pool |
| 3 | Cash balance | currency | `company.cash` |
| 4 | Brand | 0–100 | §5.3 |
| 5 | Stock on hand | units, per product | carried stock |
| 6 | Capacity utilisation | % | produced / capacity |
| 7 | Lost sales | units and % | demand − sold |
| 8 | R&D completed / tech index | count, index | portfolio |

Worksheet Q3 said six is the practical maximum on a slide. **Recommendation: 1–6 on the slide,
7–8 on the second page.** They are the two that explain *why* profit moved, and a trainee who
over-produced needs to see "lost sales 0, stock 4,000".

## 9. Schema — implemented in `V3__simulation_model.sql`

Written and tested on 2026-11-02. All additive except the `decision` unique key.
Measured numbers for the seeded scenario are in `docs/SCENARIO_CALIBRATION.md`.

```sql
-- scenario catalogue
product       (scenario_key, code, name, base_price_ref, base_unit_cost, base_quality,
               base_demand, demand_growth, units_per_worker)
channel       (scenario_key, code, name, reach_weight_jsonb, alpha, max_useful_spend)
rnd_project   (scenario_key, code, name, product_code, hidden_cost, hint_band,
               advantage_type, advantage_value, min_months)

-- per-company state
company_product (team_id, product_code, unit_price, quality_level, unit_cost, stock)
company_staff   (team_id, function, headcount)
company_rnd     (team_id, project_code, invested, status, started_round, completed_round)
team            + status, + cumulative_profit, + cumulative_revenue, + lost_sales_total

-- decision: one per company per month
alter table decision drop constraint decision_round_id_team_id_role_key_key;
alter table decision add constraint decision_round_team_key unique (round_id, team_id);
-- role_key stays as a nullable informational column, always 'OWNER' (D1)

-- monthly statement, for the debrief and the audit trail
round_statement (round_id, team_id, revenue, cogs, payroll, marketing, rnd, fixed, holding,
                 profit, cash_after, brand_after, jsonb per-product detail)
```

`round_result.score` keeps the headline numbers for the scoreboard; `round_statement` keeps the
line-item detail the PDF needs. Splitting them means the scoreboard query stays cheap and the
debrief does not recompute anything.

## 10. Company aspects not in your list — proposed, not built

Ordered by teaching value per day of work. All are additive to the model above.

| Aspect | What it teaches | Cost | Recommend |
|---|---|---|---|
| **Selling price per product** (Q-A) | price vs volume trade-off; without it the game is "produce the most" | ~0 (already in payload) | **yes, v1** |
| **Capacity purchase** (buy machines) | fixed vs variable cost, timing of investment | 1–2 days | yes, v1 |
| **Hiring / firing with severance** | workforce is a commitment, not a dial | 2 days | yes, v1 |
| **Quality level per product** (paid upgrade) | short-term profit vs long-term share | 2 days, overlaps R&D | optional, v1.1 |
| **Paid market research report** (competitor prices/stock) | information has a price; uncertainty | 2 days | v1.1, good differentiator |
| **Working capital / payment terms** | receivables, cash timing, the classic "profitable but broke" | 4–5 days | no, v2 |
| **New product launch from R&D** | portfolio management, multi-product D3 at full strength | 3 days | v2 if D3 needs it |
| **Dividends / shareholder value** | capital allocation | 2 days | no — no shareholders in the model |
| **Bribery / ethics event cards** | governance, consequence | 1 day | facilitator events, not a mechanic |

Two of these are already implied by your rulings and I have included them as **v1**: price and
capacity. Hiring/firing is in as production headcount. **D15 says every ticked aspect is wanted
eventually** — the phasing below is a build order, not a "no":

| Phase | Aspects | Why that order |
|---|---|---|
| v1 (by 31 Dec) | price, capacity purchase, hiring/firing, quality upgrade | each is one field plus one formula term |
| v1.1 (pilot fixes, Jan) | paid market research, quality tuning | needs a console screen and a report document |
| v2 | working capital / payment terms, new-product launch from R&D | revenue timing and product-unlock touch the statement and the product list |

Working capital and new-product launch are the two that **must stay additive**: revenue recognition
is isolated in §4 step 6, and a new product is a `company_product` row rather than a schema change.
Nothing in S2 code may assume the catalogue has exactly three rows.

## 11. Open items — all answered 2026-11-02

| # | Item | Outcome |
|---|---|---|
| Q-A | price decision | **yes** → D8 |
| Q-B | product catalogue | fixed set of 3 → D9 |
| Q-C | R&D success rule | deterministic, fixed duration, 80% floor → D10 |
| Q-D | insolvency | survive, zero production, rank last, can recover → D11 |
| Q-E | legacy scenario | none — scenario #1 invented, provisional → D16 |
| Q-F | round length | 8 cycles, 3 h total, clock visible, all per-simulation config → D12 |
| Q-G | facilitator powers | six ✓, two ✗, auto-close clock, pre-defined events → D12 |
| Q-H | KPI set | eight, six on the slide → D13 |
| Q-I | price elasticity | default for now, tunable data → D14 |

The reference table below is kept as the record of what was asked.

| # | Question | My recommendation | Blocks |
|---|---|---|---|
| Q-A | Is **selling price per product** a monthly decision? | **Yes.** Without price the market model has no trade-off and "market dominance" is just output volume. | decision payload, §5.2, forms |
| Q-B | Product catalogue: do all companies start with the **same fixed set** (e.g. 3 products), or choose their own mix? | Same fixed set of **3** in v1. Different mixes make the demand pools incomparable and the scoreboard unreadable. | scenario data, V3 |
| Q-C | R&D outcome: **deterministic threshold** (invested ≥ hidden cost) or a probability roll? | Deterministic threshold. A probability roll would contradict decision 4 (deterministic scoring) and cannot be explained in a debrief. Partial funding = no completion, money kept by the project. | `ScoringStrategy`, §6 |
| Q-D | When cash goes negative: **out of the game**, or **survive with zero production** and rank last? | Survive with zero production, rank last, marked INSOLVENT. Deleting a company breaks the debrief and teaches "you vanished", not "you ran out of money". | round engine, scoreboard |
| Q-E | Scenario numbers — do you have the old Excel/paper simulation to encode? | Send the file; encode 1:1 (§Q6 of the worksheet, option A/C). Without it every number is invented and needs calibration with you. | V3 seed, all formulas' constants |
| Q-F | Round length and total session time | 8 cycles × 20 min ≈ 3 h with intro and debrief. Confirm or give the real agenda. | facilitator console default only |

## 12. Delivery status against the plan

The S2 sprint was sized for "4 roles × 4 numeric inputs, one demand pool". The locked model is much
bigger, so it was split. **Both halves of the scoring engine are already built and green on
2026-11-02**, ahead of the 20 Nov slot — the rulings arrived before the sprint, which is why.

| Piece | Status |
|---|---|
| V3 schema: catalogue, company state, statement, decision key, round clock | done, Flyway v3 |
| Decision payload, validation (all errors at once), capacity check, cash shortfall 409 | done |
| `ScoringEngine`: production → cost → demand → sales → R&D → brand → cash | done, 9 unit tests |
| Round close + statement + scoreboard, idempotent | done, 9 API tests |
| Auto-close on the round clock (D12) | done, `RoundClock` |
| Decision context endpoint that never exposes hidden R&D cost | done |
| Scenario #1 calibration | done, `docs/SCENARIO_CALIBRATION.md` |
| STOMP push + facilitator console (S3) | next |
| Decision form UI, i18n (S4) | next |
| PDF/Excel debrief (S5) | next |

Remaining engine work that is deliberately deferred: quality upgrade per product (one extra field),
capacity purchase is in the payload but has no UI, and events are catalogued but not yet injectable.
None of them changes the schema.
