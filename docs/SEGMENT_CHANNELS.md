# D107 — Segment-specific marketing channels: design, for ruling

**Status: proposed, not built.** Written 20 Nov 2026 at the owner's request ("modify system model to
have segment specific (weighted) channels investment"). Nothing in the engine changes until the decisions
below are answered.

## What exists today

One company-wide `reach` number, and it is **segment-blind by construction**:

```
weighted = Σ (spend_channel × reach_weight_channel)          // one number per company
reach    = √(weighted / referenceSpend)                      // diminishing return
attraction(product) = price^a × quality^b × brand^c × reach^0.7
```

Then per **product** the cohort's pieces are allocated by attractiveness, and each product's pool is
sized by its segment. Segments decide **how big the pool is**, never **who wins it**. Marketing is
identical for every product and every class.

## What the owner wants

Channel investment that is **weighted per segment** — so Trade money reaches the mass market differently
from the premium one, and a trainee can choose *where* to push as well as *how much*.

## The structural consequence, stated plainly

The moment reach varies by segment, the pool can no longer be allocated as one number per product. It
must be **split by segment first, then allocated within each segment**. That is not a cosmetic change: it
moves the unit of competition from *(product)* to *(product, segment)*, and "your market share" stops
being one number.

## Decisions to rule

**D-A. Where does a segment's demand live?**
Today a product *serves* one class (`target_segment`) plus `cross_segment_pct` of the others' demand.

- (i) Keep it: a product's demand in each class = its own class's pool + its cross share. Marketing by
  segment then redistributes **within each class separately**, and a company can be strong in mass and
  weak in premium on the same product.
- (ii) Replace it: every product competes in every class, with per-(product, class) appeal only from
  price/quality. Simpler maths, but it discards D81's "this product is made for that class" idea.

*Recommend (i)* — it preserves what the presets were calibrated on and makes `cross_segment_pct`
meaningful rather than vestigial.

**D-B. What is the per-segment weight's shape?**
A matrix `reach_weight(channel, segment)` in the template (data, so it must be editable).

- (i) A full matrix, 3 channels × 3 classes = 9 numbers per template, defaulting to today's weights on
  the diagonal and lower off it.
- (ii) A per-segment *multiplier* on the existing channel weight (3 numbers + 3 numbers) — fewer knobs,
  less expressive, and a trainer cannot say "Field reaches premium best but mass barely at all".

*Recommend (i)*, with the shipped default equal to today's behaviour **for a class's own segment** so
existing templates are unchanged in effect.

**D-C. What happens to the published market share and the standings?**
Share is currently one number per company per round, and the scoreboard, the position map, the reports
and the audit all read it.

- (i) Scoreboard keeps the **company** share (sum over classes), and the debrief gains a per-class
  breakdown. Least disruptive; one new view.
- (ii) Share becomes per (product, class) everywhere and the scoreboard shows a matrix. Faithful, but it
  rewrites the debrief, the PDF/Excel reports, the position map and the audit at once.

*Recommend (i) for this release*, with (ii) as a follow-up if the class-level picture proves insufficient.

**D-D. Does brand stay company-wide?**
Brand is one number today and decays 10 % a month. If marketing is now targeted, brand arguably should be
per-segment too — but that doubles the state a team carries and makes the brand stock harder to teach.

- (i) Brand stays company-wide (one number). Channels differ in *reach to a class* and in *how much brand
  they build*.
- (ii) Brand becomes per-segment.

*Recommend (i)* — the teaching point is "where do you push", not "you now have three brands".

**D-E. What happens to the three D100 presets?**
Their winners were measured on the current model. Segment-specific reach changes how demand is carved, so
**every preset must be re-calibrated and re-proved**, or the claim "cost leadership wins in its preset"
becomes unproven.

*Recommend: the release does not ship until all three presets invert again, by the same acceptance test.*

## Size

| Piece | Work |
|---|---|
| Schema | `channel_reach(channel, segment, weight)` per template (V39), editable in the template editor |
| Engine | `attractiveness` per (product, segment); allocation loops segments inside products; cross-segment share applied per class |
| Scores | company share = sum over classes; new per-class breakdown in the debrief data |
| Console | marketing card gains a segment dimension; template editor gains the matrix; both languages |
| Reports | PDF/Excel gain a per-class share table |
| Calibration | all three presets re-measured; tables in `SCENARIO_CALIBRATION.md` regenerated |
| Audit | `audit_financials.py` re-derives the new allocation independently; must PASS on a full session |
| Docs | `SIMULATION_MODEL.md` §marketing rewritten, guide section for the trainer, D107 recorded |

Not an afternoon. It is a core-engine release with a calibration dependency, and I would rather say so
now than discover it in the middle.

## What I will not do without a ruling

Change the allocation unit, the meaning of market share, or the presets' calibration silently. Each of
those is visible to a trainer in the middle of a course, and the D100 claim ("this preset rewards this
position") is only true while the numbers that produced it hold.
