# Open questions — answer sheet (2026-11-02)

**This is the only file you need to edit.** Put your answer on the `Answer:` line under each
question. If you accept my recommendation, write `default` — that is enough. Leave a line blank and
I will use the recommendation anyway and mark it as *assumed* in `DECISIONS.md`.

Questions come from `docs/SIMULATION_MODEL.md` §11. Rulings D1–D7 are already locked in
`docs/DECISIONS.md`; these six are the remainder.

---

## Q-A. Is selling price per product a monthly decision?

You listed volume, marketing, manpower and R&D — not price. Without price, demand allocation has no
trade-off and the winner is simply whoever produces the most units.

**Recommendation: yes.** One more field per product, almost no extra work.

```
Answer:
```

---

## Q-B. Product catalogue: same fixed set for every company, or each company picks its own mix?

**Recommendation: same fixed set of 3 products for every company in v1.** Different product mixes
make the demand pools incomparable and the scoreboard unreadable. Each company still competes
differently through price, quality, brand and stock.

```
Answer:
Product count (if not 3):
```

---

## Q-C. How does an R&D project succeed?

| Option | Meaning |
|---|---|
| A. Deterministic threshold (recommended) | completed when cumulative investment ≥ hidden expected cost. Explains itself in a debrief. |
| B. Probability from investment ratio | e.g. 80k invested against a 100k project = 80% chance. Contradicts decision 4 (deterministic scoring) and cannot be explained after a bad roll. |
| C. Threshold + partial benefit | completed only at 100%, but the advantage scales with how far past the threshold you went. |

**Recommendation: A.** Partial funding = no completion, and the money stays with the project.

```
Answer (A/B/C):
```

---

## Q-D. What happens when a company's cash goes negative?

**Recommendation: survive with zero production, rank last, marked INSOLVENT.** Deleting the company
breaks the debrief history, and "you ran out of money" is the lesson you want — not "you vanished
from the screen".

```
Answer (out / survive-and-rank-last / other):
```

---

## Q-E. Do you have the old Excel/paper simulation to encode?

This is the last blocker for the scenario numbers: starting cash, unit costs, prices, demand pool,
salary, channel costs, brand decay.

```
Answer (yes + file path / no, invent it):
```

---

## Q-F. Round length and total session time

**Recommendation: 8 cycles × 20 minutes ≈ 3 hours** including intro and debrief. This is a console
default only, not code.

```
Rounds per session (default 8):
Minutes per round:
Total time available:
Round clock visible to players? (yes/no):
```

---

## Optional: the extra company aspects (spec §10)

Tick what should be in v1. Unticked = deferred, no argument needed.

```
[ ] Selling price per product        (Q-A; recommended v1)
[ ] Capacity purchase (buy machines) (recommended v1, ~2 days)
[ ] Hiring / firing with severance   (recommended v1, ~2 days)
[ ] Quality upgrade per product      (v1.1, ~2 days)
[ ] Paid market research report      (v1.1, ~2 days)
[ ] Working capital / payment terms  (v2, ~5 days)
[ ] New product launch from R&D      (v2, ~3 days)
[ ] Dividends / shareholder value    (not recommended)
```

---

## Sign-off

```
Product owner: ______________________   Date: __________
```

When this file is answered I write the V3 migration, the `ScoringStrategy` contract and the S2a
tests — no further questions.
