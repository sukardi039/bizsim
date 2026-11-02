# Scenario #1 — calibration record

Status: **provisional (D16).** No legacy scenario exists, so these numbers are invented. They are
frozen in `V3__simulation_model.sql` so the code and this document cannot disagree, and every number
below is a calibration target to be reviewed with the facilitator at the S7 rehearsal.

Currency: rupiah, whole units. "M" below means one million rupiah.

## 1. Why this file exists

A trainee must be able to say, after round 1, *why* they made or lost money. That is only true if the
cost base is sized against the revenue the market can actually pay. The first draft of these numbers
failed that test: industry capacity was about a third of demand, so every company sold out every
month and "market share" measured nothing but factory size. The numbers below were tuned by running
`ScenarioCalibrationTest` and reading the output, not by estimating on paper.

## 2. Company at the start

| Item | Value |
|---|---|
| Cash | 2,000 M |
| Brand | 50 of 100 |
| Production staff | 30 |
| Sales / R&D / Admin staff | 3 / 2 / 2 |
| Machine capacity | 0 worker-equivalents |

Monthly costs at the start: payroll 184 M (production 135, sales 21, R&D 16, admin 12), fixed
overhead 50 M. So roughly **234 M per month before any material is bought**. With 2,000 M in the
bank, a company survives about eight months of doing nothing — one full run — which is deliberate:
one catastrophic month should not end the game, two should.

## 3. Products

| Code | Name | Base price | Unit cost | Gross margin | Units per worker | Market demand (10-company run) |
|---|---|---|---|---|---|---|
| P1 | Basic | 1,000,000 | 590,000 | 41.0% | 40 | 4,000 / month |
| P2 | Standard | 2,500,000 | 1,430,000 | 42.8% | 15 | 1,500 / month |
| P3 | Premium | 5,000,000 | 2,780,000 | 44.4% | 6 | 600 / month |

Capacity is worker-equivalents: producing 400 P1 + 150 P2 + 60 P3 needs
`400/40 + 150/15 + 60/6 = 10 + 10 + 10 = 30` workers — exactly the starting workforce. That identity
is the core trade-off of the game: **the product mix is a capacity decision, not just a sales one.**

At a 10-company run the market is sized so that a company holding its "fair share" (about 8.2% after
the 18% baseline competitor) sells roughly what 24 of its 30 workers can build, leaving a little
slack to grow. A smaller cohort gets a proportionally smaller market, so the demand-to-capacity ratio
holds from 2 players to 10.

## 4. Channels

| Code | Name | Reach weight | Brand points per √M spent |
|---|---|---|---|
| DIGITAL | Digital | 1.00 | 1.6 |
| TRADE | Trade | 1.20 | 1.2 |
| FIELD | Field sales | 0.80 | 0.8 |

Reach is `sqrt(weighted spend / 20 M)`, so 20 M in a single 1.0-weight channel gives reach 1.0 and
80 M gives 2.0 — four times the money for twice the reach. Brand decays 10% a month, so marketing is
a subscription, not a purchase.

## 5. R&D slate (the hidden cost is never sent to the client)

| Code | Target | Effect | Hidden cost | Duration | Published band |
|---|---|---|---|---|---|
| RND-P1-COST | P1 | unit cost −15% | 300 M | 3 months | 240–360 M |
| RND-P2-COST | P2 | unit cost −15% | 350 M | 3 months | 280–420 M |
| RND-P3-QUAL | P3 | quality +15 points | 450 M | 4 months | 338–563 M |
| RND-CAP | whole line | capacity +20% | 400 M | 4 months | 300–500 M |
| RND-MKT-P1 | P1 | attractiveness +15% | 250 M | 2 months | 200–300 M |

D10/D17: the outcome is decided **at the end of the duration**, by `invested / hidden cost`. Below 80%
the project fails and the money is gone. From 80% to 100% the benefit follows the project's curve —
`LINEAR` by default; `POWER` (exponent 2.0) makes the last dollars worth the most; `CHANCE` turns the
80–100% band into a hidden success threshold, so the funding ratio is the probability of full success.
Every project can also switch partial credit off entirely (100% or nothing) and can keep or refund
over-funding.

**The shipped slate is `LINEAR` + `REFUND`**, so the measured table in §6 stays valid and the standard
scenario matches D10 exactly. Changing one project is one statement:

```sql
update rnd_project set benefit_curve = 'POWER', benefit_exponent = 2.0 where code = 'RND-CAP';
update rnd_project set benefit_curve = 'CHANCE'                          where code = 'RND-P3-QUAL';
update rnd_project set partial_benefit = false                           where code = 'RND-MKT-P1';
update rnd_project set overfunding = 'LOST'                             where code = 'RND-P2-COST';
```

If you change any of these for the pilot, re-run `ScenarioCalibrationTest` — it asserts the lesson
shape, and the curve values are covered by `ScoringEngineTest`.

## 6. Measured round 1 — three strategies, three companies, no R&D effects yet

Produced by `ScenarioCalibrationTest` against the real engine (3-company run, so the pool is 30% of
the table above: 1,830 units total).

| # | Strategy | Revenue | Profit | Share | Cash after | Brand | Sold | Lost | Util |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Premium: +5% price, 30 M marketing across digital+trade, 500/150/40 | 800.89 M | **+95.96 M** | 24.14% | 1,916.19 M | 55.95 | 441.7 | 4.2 | 97.2% |
| 2 | Discount: −10% price, 30 M trade, 600/150/30 | 800.81 M | **+23.36 M** | 30.35% | 1,884.91 M | 51.57 | 555.5 | 27.2 | 100% |
| 3 | Base price, 30 M split across three channels, 600/150/30, 30 M into R&D-P1 | 749.42 M | **+23.57 M** | 24.91% | 1,803.52 M | 56.78 | 455.8 | 16.3 | 100% |

The lesson is in columns 2 and 3: **the discount company sells 26% more units than the premium
company and earns a quarter of the profit.** Volume can be bought; margin cannot. That is exactly
what D14 asked the model to teach, and it is now an assertion in the test (`unitsB > unitsA` and
`profitB < profitA`), so the scenario cannot drift into "cheapest wins" by accident.

## 7. Tuning dials, if the facilitator wants a different game

| Desired change | Change this | In |
|---|---|---|
| Harsher cash pressure | `startingCash` down, `fixedCostPerMonth` up | scenario definition |
| Price matters more | `elasticity.price` up (e.g. 2.5); watch for a price war | scenario definition |
| Brand matters more | `elasticity.brand` up, `brandDecay` down | scenario definition |
| More capacity pressure | `units_per_worker` down | `product` rows |
| Longer/shorter projects | `duration_months`, `hidden_cost` | `rnd_project` rows |
| Softer R&D failure | `minRndBenefitRatio` down (e.g. 0.6) | scenario definition |

Every one of these is data. Changing any of them means re-running `ScenarioCalibrationTest` and
updating §6 of this file, because a calibration table that no longer matches the seed is worse than
no table at all.

## 8. Known gaps in this calibration

- **Seasonality is flat for three of eight months** (1.00, 1.00, 0.95, 1.05, 1.00, 1.10, 1.15, 1.20).
  It exists so the last month can reward stock built earlier; it has not been played yet.
- **No price floor or ceiling** beyond "greater than zero". A company can price at 1 rupiah and win
  every unit of demand; the margin lesson punishes it, but a facilitator should see it coming.
- **The first three months of any run are the same game** for every cohort, because the project slate
  and starting state are identical. Differentiation starts at round 1 through pricing and mix only.
- **Marketing has no saturation cap** beyond the square root. At 1,000 M of spend the reach term is
  about 7, which is large but finite; no test currently covers absurd spend.

## 9. Cycle template, trend and event deck (D18, D19)

The scenario stores **one cycle**, four positions long. A run expands it to as many cycles as the
facilitator chooses (default 2, so 8 rounds; `rounds` can stretch it) and applies a small drift so
the second cycle is not a carbon copy.

| Cycle position | Demand | Cost | Wage | Trend | Label |
|---|---|---|---|---|---|
| 1 | 1.000 | 1.000 | 1.000 | 0.00 | Stable |
| 2 | 1.050 | 1.030 | 1.010 | +0.40 | Consumers trading up |
| 3 | 0.920 | 1.070 | 1.030 | −0.40 | Consumers trading down |
| 4 | 0.960 | 1.050 | 1.030 | −0.20 | Cautious |

Drift: **+2% demand, +1% cost and wage per cycle**. Cycle 2, position 1 is therefore 1.020 demand;
position 3 of cycle 2 is 0.938. Round 1 is neutral (1.000), so the measured table in §6 stays exact.

Income elasticity: **P1 0.40, P2 1.00, P3 1.80**. At position 3 the premium line keeps
`0.92^1.8 = 86%` of its market from the economy alone — and then the consumer trend takes another
`1 + (−0.40 × 0.50) = 80%`, so 69% in total. The basic line keeps `0.92^0.4 = 97%` and *gains* 20%
from the trade-down, i.e. 116%. That asymmetry is the whole point: a premium-only portfolio is a bet
on the cycle and on taste at the same time.

Trend affinity: **P1 −0.50, P2 0.00, P3 +0.50**. The report shows the trend as a label and a signed
strength; it never shows a per-product forecast (consequence 14).

| Event | Window | Chance | Duration | Demand | Cost | Wage | Target |
|---|---|---|---|---|---|---|---|
| Fuel and logistics spike | 2–6 | 55% | 2 | — | ×1.12 | — | all |
| Regional conflict | 3–7 | 45% | 2 | ×0.94 | ×1.15 | ×1.01 | all |
| Consumer recession | 3–6 | 40% | 2 | ×0.88 | ×0.97 | — | all |
| Premium segment boom | 4–6 | 35% | 2 | ×1.20 | — | — | P3 |

Each card fires at most once per run, on a round drawn inside its window, frozen when the run is
created. Two runs of the same scenario therefore differ — that is the point — but a replay of one run
reproduces its schedule exactly, which is what makes the debrief defensible.

Market review: 15 M per month, two rounds of forecast, ±8% band, and it includes the consumer-trend
forecast alongside the economic one. On a 2,000 M balance that is 0.75% of starting cash to buy a
slightly wrong view of next month — cheap enough to try, expensive enough to think about.
