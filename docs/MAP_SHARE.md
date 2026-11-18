# D103 — reviewing earlier rounds, and a map the class can hold

Ruled 18 Nov 2026, from three observations made on a live class:

1. *"The positioning chart needs a lot more clarity. It is very difficult to read."*
2. *"It needs to be sharable to players."*
3. *"How do trainers review the previous rounds of plays?"*

D101 answered the first in the console (indexing, quadrants, ticks, trails, the flat-axis switch). What
it did **not** answer is size: the chart was a 340 px figure beside a table, and on a projector that is
unreadable whatever is written inside it. This ruling is about the other two questions and about the
size.

## Sharing — a link, not an account

`POST /api/runs/{runId}/map-share` freezes the session's picture into a page and returns an absolute
URL. `GET /share/map/{token}` serves it with **no authentication at all**: a trainee opens it on a phone
from a chat message, which is the whole point of "sharable".

The consequences are deliberate and each one is a design choice:

| Property | What it means | Why |
|---|---|---|
| **A snapshot** | The figures are frozen into the page when the link is made | The class must be able to rely on what it was shown; a recomputed round must not silently change a picture that has already been discussed |
| **Unguessable** | 32 random bytes, stored **hashed** | A database read cannot mint links; a link is not an authorisation to anything else |
| **Revocable** | `revoked_at`, permanent, keeps the row | The record that a class was shown a picture is part of the session's history |
| **Expires** | 90 days | A share is for a class, not for ever |
| **Nothing secret** | Only the debrief's own figures and company names | The same numbers are already on the trainees' standings screen |

Access to *create* a link follows the session's own rule: a facilitator may share any round of a run they
own; a trainee only of a run they play in; a builder who does not run this class, and any other tenant,
are refused. Withdrawing follows the same rule.

## Reviewing earlier rounds — three ways, from casual to systematic

- **In the console**, the debrief's round selector already walks every computed round (D69), and the
  position map now carries **trails** (D101), so one screen shows where each company came from.
- **The shared page** shows **every computed round, newest first**, one chart per round, each with its
  own caption. That is the answer to "the class asks why my position is where it is": the page they were
  sent contains the whole path, not just today's picture.
- **The exports** (PDF/Excel) remain the formal record of a round's statements.

## Clarity — what changed for the reader

- The shared page draws the map at **620 px** (92 vw on a phone) with the numbers **in a table beside
  it**: company, price index, the vertical index, share. A chart without its numbers is a poster.
- The console's own map grew from 340 px to 440 px, with larger ticks, labels and shares.
- On the shared page nothing is abbreviated: the caption, the four quadrant names, the axis titles and
  the flat-axis note are the console's own strings, in the **class language the package froze** (D86) —
  not the reader's browser locale.

## How it is drawn, and why not in Java

Spring cannot render a React component, and drawing the chart a second time in Java would produce two
pictures that agree today and drift later — the failure D101 was. So `web/scripts/map-template.mjs`
renders the console's own `PositionMap` **at build time** for a fixed six-slot dataset, and stores the
result as an SVG with `{{nameN}}` and `__NUM__` placeholders.

What that precomputes is **geometry only** — bubble positions on each axis, trail paths, label rows —
and that is legitimate because both axes are indices around 1.00: the *positions* move with the data,
the *words* do not. The numbers substituted in are the shares (one per company, in document order); the
indices live in the geometry, not the text.

Two things guard the seam:

- the template records how many names and figures it contains, and the backend **refuses to serve a page
  whose counts disagree** (a share in the wrong bubble is the worst failure a debrief chart can have);
- `npm run map-template:check` fails the build when the component has moved and the template has not.

## Evidence

| Check | Result |
|---|---|
| Template build | 6 bubbles, 10 figures, 6 name slots; both axes' strings carried for EN/ZH |
| Backend substitution | counts verified at render time; a mismatch throws rather than draws |
| Share link | created by a facilitator, opened in a browser **with no Authorization header** |
| Withdrawn / expired | 404, never a login prompt |
| Player access | can open their own run's page; cannot create a link for a run they do not play in |
| Migration | `V36__map_share.sql`, migrations 35 → **36** |
