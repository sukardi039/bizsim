# Computational audit

The product tells a trainee what their month cost, what it earned and what cash they have left. Those
figures end up in an accounting report, so "the screen adds up" is not enough: each number has to be
reproducible from stated rules by someone who does not trust the code. This document defines what is
computed, what must be true of it, and how the claim is tested.

**Result of the last full run: 4,854 checks over 88 company-rounds across three classes — every
figure re-derived independently and matching.** The two defects the audit found are in
[Findings](#findings); both are fixed.

## What "audit" means here

`scripts/audit_financials.py` is a **second implementation of the model**, written from
`docs/SIMULATION_MODEL.md` and the decision log, sharing no code with the engine. It reads a
database, replays every class from its decisions, the catalogue and the run's economy, and compares
what it computes with what the engine stored — field by field, company by company, round by round.
A disagreement means one of the two implementations is wrong, and the report names the company, the
round, the field, both values and the delta, so it can be argued about with numbers.

It is not a unit test: it never calls the engine. It is not a report checker: it does not read the
deployment's own output back to itself.

## The inventory

Every figure the engine computes, in the order the engine computes it.

### Environment (per run, per round)

| Quantity | Rule |
|---|---|
| demand index | `run_round_economy.demand_index` × active events' demand multipliers |
| cost index | `…cost_index` × active events' cost multipliers |
| wage index | `…wage_index` × active events' wage multipliers |
| class size index | `demand_index ^ class.income_elasticity × (1 + trend_strength × class.trend_affinity)` × active events' size multipliers, 6 dp |
| class purchasing power | `base_purchasing_power` × active events' purchasing-power multipliers, 6 dp |
| demand per product | for a product with a class: `size index × purchasing power ÷ base purchasing power`, 6 dp; for a legacy product: `demand_index ^ income_elasticity × (1 + trend_strength × trend_affinity)` × product-targeted event multipliers, 6 dp |
| an event is active | `starts_round ≤ round < starts_round + duration_months`, read from the run's frozen row (D84) |

### Planning (per company, per round)

| Quantity | Rule |
|---|---|
| production staff | the decision's headcount, frozen at the previous value when the company is insolvent |
| workers | production staff + machine workers |
| unit cost | `stored unit cost × R&D cost multiplier × cost index`, 2 dp |
| selling price | the decision's price if > 0, otherwise last month's |
| quality | stored quality + R&D quality bonus + quality bought this month, capped at 100 |
| quality bought | `min(investment ÷ cost per point, 100 − quality)`; unspendable money is not charged |
| required capacity | `Σ units ÷ (units per worker × R&D capacity multiplier)` |
| capacity factor | `min(1, workers ÷ required)`; production is scaled down when short |
| materials | `Σ produced units × unit cost`, 2 dp |
| payroll | `Σ headcount × salary × wage index` |
| hiring / severance | `Δ headcount × salary × hiring (or severance) months` |
| holding | `opening stock × holding cost per unit` (opening, not closing) |
| capacity bought | the decision's `capacityPurchase`, which is **money** (D77) |
| machines installed | `capacityPurchase ÷ machine cost per worker` |
| market report | the report fee if the decision bought the review, else 0 |
| cash fitting | if materials exceed `cash − every other commitment`, production is scaled by `available ÷ materials` |

### Market (per product, per round)

| Quantity | Rule |
|---|---|
| pool (product with a class) | `units(base_size × size index × purchasing power) + cross_segment_pct × Σ_{k≠s} units(base_size_k × size index_k × pp_k)`, then × `seasonality × companies ÷ 10`, whole units (D85) |
| pool (legacy product) | `base demand × seasonality × (companies ÷ 10) × demand index for the product`, whole units |
| cohort target | `round(pool × (1 − baseline competitor share))` |
| attractiveness | `(reference price ÷ price) ^ price elasticity × (quality ÷ reference quality) ^ quality elasticity × (brand ÷ 50) ^ brand elasticity × max(reach, 0.01) ^ marketing elasticity × R&D attractiveness multiplier` |
| reach | `√(Σ channel spend × channel weight ÷ reference spend)` |
| demand | the cohort target split by attractiveness share, floored, remainder to the largest fractions, ties by company order |

### Settlement (per company, per round)

| Quantity | Rule |
|---|---|
| sold | `min(demand, opening + produced)` |
| lost | `demand − sold` |
| closing stock | `opening + produced − sold` |
| revenue | `sold × price`, 2 dp per product |
| COGS (weighted) | `sold × (opening stock value + produced × unit cost) ÷ (opening units + produced)` |
| COGS (replacement) | `sold × this month's unit cost` |
| closing stock value (weighted) | `opening value + produced × unit cost − COGS` |
| R&D progress | `months since first investment`; at the deadline `ratio = invested ÷ hidden cost` |
| R&D outcome | `ratio ≥ 1 → completed (100%)`; below `minRndBenefitRatio` or no partial credit → failed; else `progress = (ratio − min) ÷ (1 − min)`, shaped by LINEAR / POWER / CHANCE (a seeded SHA-256 threshold) |
| refund | `invested − hidden cost` when completed and the project refunds over-funding |
| profit | `revenue − COGS − payroll − marketing − R&D − quality − fixed − holding − hiring − report` |
| cash after | `opening cash + revenue − materials − payroll − marketing − R&D − quality − fixed − holding − hiring − capacity bought − report + refund` |
| brand | `opening brand × (1 − decay) + Σ channel alpha × √(spend ÷ 1,000,000)`, capped to 0…100 |
| status | insolvent when cash after < 0 |
| market share | `units sold × 100 ÷ total pool` (a share of the whole market, the baseline competitor included) |
| utilisation | `capacity used × 100 ÷ capacity available` |
| cumulative profit | previous cumulative + this round's profit; `total_score` is that number |

## The invariants

These must hold for every company and every round. The audit tests each one on stored data as well as
on its own recomputation, so a figure can be wrong in the same way in both places and still be caught
here.

1. `profit = revenue − COGS − payroll − marketing − R&D − quality − fixed − holding − hiring − report` — exactly, to the cent.
2. `cash change − profit = COGS − materials − capacity bought + refund`. Everything cash paid that profit did not charge, and the reverse.
3. Under weighted-average costing that inventory term equals `−(closing stock value − opening stock value)`; under replacement costing it equals `−(closing units − opening units) × this month's cost`. The statement records which basis it was computed under (D80), so a report never has to guess.
4. Per product: `closing = opening + produced − sold`, `lost = demand − sold`, `sold = min(demand, opening + produced)`, and none of them negative.
5. `revenue = Σ product revenue`, `COGS = Σ product COGS`, and each product line's revenue is `sold × price`.
6. Chains: this round's opening cash equals last round's closing cash (plus any facilitator cash adjustment, which is named when it explains a difference); this round's opening stock equals last round's closing stock; brand, machine count and staff carry over.
7. Cumulative revenue, cumulative profit and closing cash equal the running sum of the statements. `total_score` equals cumulative profit.
8. The score map the class table reads (`cash`, `profit`, `revenue`, `marketShare`, `stock`, `lostSales`, `capacityUtilisation`) equals the statement it is derived from.
9. The cohort's demand adds up to exactly the whole units the market offers it — no unit invented, none lost.
10. Capacity used never exceeds capacity available; production never exceeds the plan.
11. Money fields carry 2 decimals, unit fields 2, and no stored figure is null where a number is due.

## Coverage

The audit is only as good as the classes it reads, so the harness plays deliberately awkward ones
(`scripts/audit_play.py`) through the real API rather than the engine's own test doubles:

| Class | What it exercises |
|---|---|
| weighted (6 companies, 8 rounds, package) | hoarding, liquidating a warehouse to zero over three months, quality buying, buying two machines a month, over-funded research with refunds, partial-credit research, companies that never submit, three companies driven insolvent |
| replacement (4 companies, 8 rounds, copied template) | the other costing basis under the same strategies, prices at twelve times the catalogue |
| weighted with facilitator powers (4 companies, 2 rounds) | a cash adjustment between rounds, and a round voided and replayed |

Not exercised because the shipped catalogue has no such project: `POWER` and `CHANCE` research curves,
non-refunding over-investment, and a research failure below the minimum ratio. Those paths are
covered by unit tests (`ScoringEngineTest`) and by the same formulas in the auditor, but no class has
produced data through them.

## Tolerances

Exact where the chain is decimal. Two allowances, both documented rather than tuned:

* **One unit of demand**, where two companies' largest-remainder fractions are within a floating-point
  ulp: Java's `Math.pow`/`BigDecimal.valueOf` and Python's `**`/`repr` can disagree in the last bit,
  which moves a unit between two companies. The class total is still required to match exactly, so
  this cannot hide a real allocation error.
* **One cent** on anything that passed through a double (brand points, attractiveness).

## Findings

Both were found by the audit, not by review, and both are fixed.

### F1 — a price had no ceiling, and an absurd one made the round impossible to compute

A price is the only number a decision can send that cash does not bound. `jsonb` accepted any positive
value; `company_product.unit_price` is `numeric(14,2)`, so a price above 10¹² failed at compute time,
inside `closeAndCompute`'s transaction. The round rolled back, stayed **OPEN**, and every retry failed
the same way: the class was stuck, with submissions accepted and no result. Reproduced by a company
that multiplied its price by thirty each month (round 4 → 810 billion; round 5 → overflow).

Fixed: `price.too_high` at submit, capped at 100× the catalogue price — generous for any elasticity
experiment, and inside the column. Test: `RoundFlowApiTest.failures_carry_codes…`.

### F2 — a statement did not record the costing basis it was computed under

The basis (D79) lived only on the template. Editing a template therefore changed the meaning of
statements already issued under the old basis — the auditor caught this when a run silently switched
from replacement to weighted-average between rounds. Frozen packages were safe; a session started
straight from a template was not.

Fixed: `round_statement.cost_basis` (V30), written at compute time and back-filled for existing rows,
so historical reports are self-describing.

### Earlier, same method (before this document)

* **D77** — `capacityPurchase` was sent as a machine *count* but read as *money*: buying three machines
  charged 3 currency units and installed none. Capacity could only ever come from hiring.
* **D79** — COGS was charged at the replacement cost while the cash had left at an earlier month's
  cost, so stock carried through a term was revalued and `cash − profit` never closed.

## Running it

```bash
# a database with classes in it (see scripts/audit_play.py for building one)
export BIZSIM_DSN="postgresql://user@host:5432/bizsim"
python scripts/audit_financials.py            # add --verbose to see deltas inside tolerance
```

Exit code 0 means every check passed; 1 prints the disagreements. It needs `psycopg` and nothing else.
Run it after any change to the engine, and keep the output with the release.

## What this does not prove

* That the **model** is right — only that the implementation does what the model says. Whether
  elasticity 1.5 on price is the right pedagogy is a product decision, not an audit finding.
* That the **reports** render correctly: the PDF and Excel exports read the same statement rows, so
  their arithmetic follows, but the layout is checked separately (`docs/USER_GUIDE.md`, D76).
* Anything about **performance** or concurrency. Two facilitators computing different classes at the
  same moment do not touch each other's rows, but that is a design argument, not a measurement.
