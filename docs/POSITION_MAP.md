# D101 — the position map indexes like with like, and says when it has nothing to say

Ruled and built 18 Nov 2026, after looking at the D98 map on a live class (the cost-leadership preset).
The screenshot showed four companies on **one horizontal line, all left of the price average**, which
sent me to the code and found a defect rather than a presentation question.

## The defect

`positionsFrom` placed a company by the **unweighted mean of its products' prices**, then divided by
the class mean of those means. A company selling all three products averaged to a mid price; a company
selling only the cheap P1 averaged to the low price and landed furthest left. The map was therefore
drawing **product-line breadth and calling it price posture** — Myson, priced at 1.25× the catalogue,
appeared left of average. It also contradicted the table beneath it, which is the worst outcome for a
chart whose job is to be read at a glance.

## What it does now

1. **Each product is indexed against that product's own class average**, then weighted by **revenue
   actually booked** (falling back to units sold, then to the plain mean when a company sold nothing).
   An index of 1.25 therefore means "a quarter dearer than the field **for the same product**", whatever
   the product line looks like. Breadth no longer masquerades as posture.
2. **Four named quadrants** — cheap / premium / stuck in the middle / priced above what they offer — so
   Porter's warning is pointed at rather than described in the caption.
3. **Numbered ticks** on both axes (0.70 … 1.30) and the class average marked by its own dashed cross.
4. **True positions, honest labels**: bubbles are drawn where the numbers put them (displacing a dot
   would lie). Labels are pushed apart vertically when two would collide.
5. **Trails**: every earlier computed round is placed the same way, so each bubble carries a dashed path
   of where that company has been — the movement a multi-round course is actually about. The round on
   screen is the trail's last point.
6. **It admits when the vertical axis is flat.** If every quality index is within 0.02 of the others —
   nobody has bought quality this round — the axis becomes **brand** (indexed the same way), the
   quadrant words change with it, and the caption says so: *"Nobody has bought quality this round, so
   the vertical axis is brand instead…"*. The live class that triggered this was exactly that case.

## Evidence

Run on a local stack (v0.19.0 + this change, four computed rounds, four companies, one of them buying
quality and brand):

| Check | Result |
|---|---|
| Axis labels in the DOM | `Price index →`, `Brand index →` (flat-quality switch fired) |
| Ticks | `0.70, 0.85, 1.00, 1.15, 1.30` on both axes |
| Quadrant words | cheap, no following · premium, with a brand · stuck in the middle · priced above what they offer |
| Bubbles | 4, at four distinct points, no overlap (nearest pair ≈ 31 px against radii ≈ 19 px) |
| Trails | 4 polylines, one per company, drawing the path into the selected round |
| Caption | the flat-quality sentence renders whenever the axis switches |
| Backend suite | 211 tests, 0 failures (no backend change: this is a view over stored statements) |

Languages: every new string is in **both** catalogues (EN/ZH), and the switch note is translated rather
than left English in a Chinese class.

## What it is not

Not a scoring change and not a new table: the map still reads `round_result.score` and
`round_statement.detail.products[]`, so historical rounds are re-rendered without being rewritten. And
a downloaded PDF/Excel debrief still shows the tables only — the map is a console view.
