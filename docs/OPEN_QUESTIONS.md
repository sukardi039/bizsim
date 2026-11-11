# Open questions — answer sheet (2026-11-02)

> **ANSWERED 2026-11-02.** Every answer is transcribed as rulings D8–D16 in `docs/DECISIONS.md`.
> Keep this file as the record of what was asked and answered; do not edit it further.

**This is the only file you need to edit.** Put your answer on the `Answer:` line under each
question. If you accept my recommendation, write `default` — that is enough. Leave a line blank and
I will use the recommendation anyway and mark it as _assumed_ in `DECISIONS.md`.

Questions come from `docs/SIMULATION_MODEL.md` §11. Rulings D1–D7 are already locked in
`docs/DECISIONS.md`; these are the remainder. Q-G and Q-H came in from the retired worksheet, Q-I
is the one lesson question the old worked example was really asking.

---

## Q-A. Is selling price per product a monthly decision?

You listed volume, marketing, manpower and R&D — not price. Without price, demand allocation has no
trade-off and the winner is simply whoever produces the most units.

**Recommendation: yes.** One more field per product, almost no extra work.

```
Answer:
```

## default

## Q-B. Product catalogue: same fixed set for every company, or each company picks its own mix?

**Recommendation: same fixed set of 3 products for every company in v1.** Different product mixes
make the demand pools incomparable and the scoreboard unreadable. Each company still competes
differently through price, quality, brand and stock.

```
Answer:
Product count (if not 3): 3
```

---

## Q-C. How does an R&D project succeed?

| Option                                   | Meaning                                                                                                                                         |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| A. Deterministic threshold (recommended) | completed when cumulative investment ≥ hidden expected cost. Explains itself in a debrief.                                                      |
| B. Probability from investment ratio     | e.g. 80k invested against a 100k project = 80% chance. Contradicts decision 4 (deterministic scoring) and cannot be explained after a bad roll. |
| C. Threshold + partial benefit           | completed only at 100%, but the advantage scales with how far past the threshold you went.                                                      |

**Recommendation: A.** Partial funding = no completion, and the money stays with the project.

```
Answer (A/B/C):
```

## A. Deterministic threshold. Partial benefit only come after 80% of the threshold is reached. to have accumulative investment into a project, shouldn't the project to have a fixed duration? Yes, each project should have a fixed duration to allow for proper accumulation and evaluation of investments.

## Q-D. What happens when a company's cash goes negative?

**Recommendation: survive with zero production, rank last, marked INSOLVENT.** Deleting the company
breaks the debrief history, and "you ran out of money" is the lesson you want — not "you vanished
from the screen".

```
Answer (out / survive-and-rank-last / other):
```

## survive with zero production, rank last, marked INSOLVENT, still involve in future rounds as future sales return from inventory may bring cash back.

## Q-E. Do you have the old Excel/paper simulation to encode?

This is the last blocker for the scenario numbers: starting cash, unit costs, prices, demand pool,
salary, channel costs, brand decay.

```
Answer (yes + file path / no, invent it):
```

## no

## Q-F. Round length and total session time

**Recommendation: 8 cycles × 20 minutes ≈ 3 hours** including intro and debrief. This is a console
default only, not code.

```
Rounds per session (default 8):8
Minutes per round:
Total time available:3 hours
Round clock visible to players? (yes/no):yes
```

## these numbers must be configurable per simulation

## Q-G. Facilitator powers (moved here from the retired worksheet Q5)

Each power is a test case; each one added later also costs a console screen.

| Power                                                               | v1                                              |
| ------------------------------------------------------------------- | ----------------------------------------------- |
| Open / close a round, advance                                       | ✓                                               |
| Pause the round clock, extend time                                  | ✓                                               |
| Inject a scenario event (cost shock, demand spike, competitor move) | ✓                                               |
| Adjust a company's cash, with a mandatory reason note, audited      | ✓                                               |
| Void and recompute the last round                                   | ✓                                               |
| View every company's submitted decisions while a round is open      | ✓                                               |
| Edit a company's decision after submission                          | ✗                                               |
| Hand-edit a score                                                   | ✗ — breaks "server computes, deterministically" |

**Recommendation: keep the six ✓, keep both ✗.** This blocks S3, not S2.

```
Answer (keep all / drop X / add Y):
```

## as recommended but the system clock must close automatically at the end of each round. Scenario events should be pre-defined when setting up the simulation to avoid unintended disruptions

## Q-H. KPI set for the scoreboard and the debrief (moved from worksheet Q3)

| #   | KPI                                    | On the slide? |
| --- | -------------------------------------- | ------------- |
| 1   | Net profit — this month and cumulative | yes           |
| 2   | Market share (% of units sold)         | yes           |
| 3   | Cash balance                           | yes           |
| 4   | Brand (0–100)                          | yes           |
| 5   | Stock on hand                          | yes           |
| 6   | Capacity utilisation                   | yes           |
| 7   | Lost sales (units and %)               | page 2        |
| 8   | R&D completed / tech index             | page 2        |

**Recommendation: all eight, six on the slide.** Six is the practical maximum before a slide stops
being readable. Items 7 and 8 are the two that explain _why_ profit moved.

```
Answer (all eight / drop # / add):
```

## default

## Q-I. Does "the cheapest company loses" match the lesson you want to teach?

This sets the price elasticity in the demand formula (§5.2). It is one number, but it decides what
the whole game rewards.

- If **yes** → price matters, but quality/brand/availability can beat a 10% discount. The trainee
  learns that undercutting without a supporting story destroys margin.
- If **no, cheapest should win** → price elasticity goes high and the game becomes a price war.
- If **don't know yet** → I use the recommendation and the exponent stays scenario data, so you can
  change it without code after the first pilot.

```
Answer (yes / cheapest-wins / tune later):
```

## tune later

## Optional: the extra company aspects (spec §10)

Tick what should be in v1. Unticked = deferred, no argument needed.

```
[x ] Selling price per product        (Q-A; recommended v1)
[x] Capacity purchase (buy machines) (recommended v1, ~2 days)
[x] Hiring / firing with severance   (recommended v1, ~2 days)
[x] Quality upgrade per product      (v1.1, ~2 days)
[x] Paid market research report      (v1.1, ~2 days)
[x] Working capital / payment terms  (v2, ~5 days)
[x] New product launch from R&D      (v2, ~3 days)
[ ] Dividends / shareholder value    (not recommended)
```

## Note: soe r&d project could also be aiming at reducing production cost of existing products.

## Deferred (not ruled) — 6 Nov 2026: market-pool scaling

Raised while explaining how share is allocated; the product owner asked to revisit later, so these are
**open, not decided**. Nothing in the engine changes until they are ruled.

- **D-a (proposal):** keep the proportional scaling (`pool ∝ N`), i.e. reject a fixed total market.
  Rationale: a fixed pool makes a small class capacity-bound and a large one comfortable — the failure
  mode recorded in `SCENARIO_CALIBRATION.md` §1.
- **D-b (proposal):** make `REFERENCE_COMPANIES` a template parameter (`market.referenceCompanies`,
  default 10) and quote `base_demand` **per company**, so the divisor stops being an invisible
  constant. The anchor to state in the builder: at 10, 400 P1 + 150 P2 + 60 P3 = 30 workers = the
  starting workforce, and fair share (8.2% after the 18% baseline) sells ~24 of those 30.
- **D-c (proposal, correctness):** scale on **trading** companies (seated and not `INSOLVENT`), not on
  `teams.size()`. Empty seats and bankrupt companies currently enlarge the pool and absorb demand they
  cannot serve, so reported shares do not sum to the served target.
- **D-d (proposal, display only):** for comparing cohorts across terms, show share **of the served
  market** (out of 82%) or as an index against the `1/N` fair share, because "20%" means different
  things in a 4-class and a 10-class. Scoring stays as it is.

Reference: `docs/SIMULATION_MODEL.md` §5, `ScoringEngine.allocateDemand()`, `ScenarioCalibrationTest`.

## Deferred (not ruled) — 11 Nov 2026: tenant provisioning (a frontend for creating tenants)

Raised while explaining what a tenant is. The product owner asked to keep the offer and do it later,
so this is **open, not decided** — nothing changes until it is ruled.

**What exists today.** Multi-tenancy is real at the data layer (every table carries `tenant_id`, the
token carries `tenantId`, cross-tenant ids answer 404, and the tests run `acme` + `other` in one
database), but a tenant can only be *created* at boot, one per edit of an environment variable:
`BIZSIM_BOOTSTRAP_TENANT` (tenant + first ADMIN) or `BIZSIM_INIT_TENANT` (tenant + a played class,
D72). `POST /api/admin/init-data` runs for the caller's own tenant and cannot create one. So many
tenants per deployment is possible; provisioning them is an operator ritual.

**Decision A — the provisioning shape.** One of:

- **A1 (proposal):** a **platform endpoint** `POST /api/platform/tenants`, guarded by a credential
  that is *not* a tenant account (a super-admin secret in the environment, or a separate operator
  token). A tenant `ADMIN` must never be able to mint tenants — that is the boundary the two-party
  model rests on (D24), and the endpoint's first test is `403` for every tenant role.
- **A2:** a CLI flag on boot (`--create-tenant <code> --admin <email>`), operator-only, no HTTP
  surface.
- **A3:** keep the environment ritual, documented in `DEPLOYMENT.md` as the supported way.

A frontend for A1 needs two screens and a rule: list tenants (code, name, locale, created, counts of
accounts/packages/runs), create one (code, name, language, first admin email + initial credential),
and — undecided — suspend/rename, which change data another tenant depends on.

**Decision B — the isolation promise.** Shared database and process (`tenant_id` scoping) is cheap and
is what runs today: one backup, one upgrade, one restart for everyone, and one noisy class affects
another. The alternatives are database-per-tenant or deployment-per-tenant, which are operations
models rather than schema changes — the same application runs either way. Whatever is chosen has to
be written in `DEPLOYMENT.md`, because "where is my data and can I get it back on its own" is the
first question a customer asks.

**Also undecided:** `tenant.code` is a single global namespace, so codes need a naming rule for
customers (a slug), and the login form will accept any code — a plan is needed for what a prospective
customer receives (code + admin account + link), since there is no self-service sign-up.

**Verification the work must carry, whichever shape is ruled:** a tenant admin gets `403` on
provisioning; two tenants created through the new path cannot see each other's packages, templates,
company profiles or trainees; the created admin can sign in and reach the workbench; and the second
tenant starts with the shipped `standard-v1` template and the global company profile, read-only, and
nothing else.


## Sign-off

```
Product owner: ______________________   Date: __________
```

When this file is answered I write the V3 migration, the `ScoringStrategy` contract and the S2a
tests — no further questions.
