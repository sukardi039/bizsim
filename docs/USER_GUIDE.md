# bizsim — user guide

*Printable PDF: `docs/bizsim-user-guide.pdf` (regenerate with
`python scripts/md_to_pdf.py docs/USER_GUIDE.md docs/bizsim-user-guide.pdf`, needs `fpdf2`).*

How to sign in, which section each role sees, and what to click to get a class from
"nothing prepared" to "debrief on screen".

The product has **two jobs done by two people**:

| Job | Role | Does |
|---|---|---|
| **Building** | `BUILDER` | prepares templates (the economy), companies, and **packages**; happens well before a class |
| **Running** | `FACILITATOR` | starts sessions from a published package, seats trainees, opens/computes rounds, runs the debrief |
| both | `ADMIN` | everything above |
| neither | `PLAYER` | the trainee: signs in, decides, submits |

A `BUILDER` cannot run a session. A `FACILITATOR` cannot edit a template, a company, or publish a
package. This is enforced by the server, not just hidden on screen — if you cannot see a section, it
is the wrong account, not a bug.

---

## 1. Signing in

1. Open your site: `https://<your-domain>/` — the browser serves the console and forwards
   `/api` to the backend.
2. Fill the three boxes on the sign-in card:
   - **Tenant** — your organisation's code, e.g. `demo`
   - **Email** — the address your account was created with (case-sensitive)
   - **Password** — at least 8 characters; the first password is handed to you, change it later
3. Press **Sign in**.

Top left after signing in: a **language selector** (English / 中文) and **sign out**. The language is
saved on your account, so it survives a reload. It translates the interface only — the content you
author (product names, event text, trend labels) is never translated.

**Forgot the tenant code or have no account?** An admin creates accounts (People → Add). If nobody
can sign in at all, see §8 "First account" below.

---

## 2. What each role sees

| Nav item | Builder | Facilitator | Admin |
|---|---|---|---|
| **Packages** | yes | — | yes |
| **Templates** | yes | — | yes |
| **Companies** | yes | — | yes |
| **People** | yes | yes | yes |
| **Sessions** | — | yes | yes |

---

## 3. Builder — Templates (the economy)

This is where a simulation is designed. There is one shipped template,
`standard-v1` ("Standard manufacturing simulation"), marked **global**, and it is **read-only**.

### 3.1 Make your own copy

1. **Templates** in the left navigation.
2. Find the row **Standard manufacturing simulation** and press **Duplicate**.
3. A copy appears marked **yours** with status **draft** and *Based on* = the shipped key. It opens
   in the editor automatically; if not, click the row.

Everything below is edited **on your copy**. Press **Save** at the bottom of each tab. A banner
confirms *"Saved. New simulations from this template use these values."*

### 3.2 The six tabs

**Settings** — the class-level numbers.

| Group | Fields |
|---|---|
| pacing | rounds per simulation, minutes per round, cycles, rounds per cycle |
| starting position | starting cash, starting brand |
| costs | fixed cost per round, holding cost per unit, machine cost per worker |
| brand | brand decay per round |
| hiring | hiring cost (months), severance (months) |
| information | market review price, forecast horizon, forecast band |
| research | min R&D benefit ratio |
| dynamics | cycle demand drift, cycle cost drift |
| **Per function** (table) | salary and **starting headcount** for Production, Sales, R&D, Admin |

**Environment** — the round-by-round timeline. One row per round: *demand index*, *cost index*,
*wage index* (1.00 = normal), *trend strength*, *trend label* (free text, shown to trainees), *note*.
Use **Add a row** / **Remove the last row** to change the length, then **Save** (saved whole).

**Events** — the event deck: surprises drawn once per run.

| Column | Meaning |
|---|---|
| Code | unique short id, e.g. `EV-FUEL-SPIKE` |
| Name / Description | what the class sees |
| From round / To round | the window in which it can be drawn |
| Chance | probability 0.00–1.00 inside that window |
| Months | how long the effect lasts (1–12) |
| Demand ×, Cost ×, Wage × | multipliers while it is active (1.00 = no change) |
| Target | one product code, or *all products* |

Leave the deck empty for a calm economy. **Save** replaces the whole deck. The server refuses
duplicate codes, a probability outside 0–1, `From` > `To`, and non-positive multipliers.

**Products** — one row per product: code, name, price, unit cost, quality, base demand, units per
worker, income elasticity (how much a downturn hits this line), trend affinity.

**Channels** — code, name, reach weight, brand effect (how much brand helps in that channel).

**R&D projects** — code, name, target product (or all), advantage type
(`UNIT_COST_DOWN`, `CAPACITY_UP`, `QUALITY_UP`, `ATTRACTIVENESS_UP`), size, true cost (hidden from
trainees), duration in months, benefit curve (`LINEAR`, `POWER`, `CHANCE`), over-funding handling
(`REFUND`, `LOST`).

**Note:** the product, channel and R&D catalogue **locks once a session has used this template** —
those codes are stored in every decision, so rewriting them would strand a live class. Settings,
environment and events stay editable (each run freezes its own copy of them).

---

## 4. Builder — Companies

A company profile is the reusable definition of a company: its **name** and **which products it
carries**. Cash, brand and staff come from the template, not the profile.

1. **Companies** in the navigation.
2. The shipped profile `Standard manufacturer` is **read-only** — press **Duplicate** to get an
   editable copy, or press **Create profile** to make one from scratch.
3. Edit name, legal name and **product line** (`P1, P2` — or leave empty for the full line), press
   **Save**.

These profiles are what a package is built from, so make one per company that will compete.

---

## 5. Builder — Packages (the hand-over)

A package is the immutable bundle you hand to the facilitator: **one template + the companies + the
class list**.

1. **Packages** → **Publish a package**.
2. Give it a name used in class, e.g. *Autumn 2026*.
3. Choose the **Template** (your edited copy, not the global one).
4. Tick the **Companies** that will compete.
5. **Class list** — for each company, pick the trainee who will run it (accounts come from **People**).
   Leave a seat open if a walk-in will fill it. One person per company; the same person cannot sit in
   two competing companies.
6. **Publish**.

The class list is part of the package because preparing happens before the class, not on the day.

Publishing **freezes a private copy of the template** and records its version. Editing your draft
afterwards changes nothing about this package — to change a published economy, edit the draft and
publish a **new** package.

**Retire** stops new sessions from using a package; sessions already run from it keep their history.

**Tidy up** appears when retired packages never ran a session; **Remove N** deletes them (and their
frozen copies) after a confirmation. Packages that taught a class are never offered.

---

## 6. Facilitator — running a class

### 6.1 Start a session

1. **Sessions** in the navigation. With no session selected you see **Start a session**.
2. Type a **Session name** (defaults to the package name).
3. Choose the **Package** — the line below shows the competing companies and the template version,
   so you can confirm you are running what you think you are.
4. Check the **Class list** shown under it: one line per company with the person prepared for it
   (*seat empty* means a walk-in can be added after the session starts).
5. **Start session** — everyone on the class list is seated automatically.

The session opens with one company per profile, its product line already set, and the prepared class
already seated. If a package is missing, no builder has published one yet.

### 6.2 The run screen

Header: `Run <n>` · status · `round x of y` · cycles. Buttons:

| Button | What it does |
|---|---|
| **Open next round** | opens the next round for decisions (round 0 → 1 → …) |
| **Compute round** | closes the open round and calculates results for every company |
| **Delete run** | removes the session and everything played in it — **accounts survive** |

You can only have **one session in progress at a time**. To run two cohorts in parallel you need two
facilitator accounts.

### 6.3 Seating trainees — Trainees card

Under the run you see one block per company.

- **Seat an existing trainee** → pick an account → **Seat**. Use this to fill a seat left open in the
  package, or to replace someone who could not attend.
- **…or create a new one** — name, email, initial password (8+) → **Create and seat**. This is the
  walk-in case; the new account outlives the session and can be seated again later.

### 6.4 Scoreboard

One row per company for the latest computed round: profit, cash, share, stock. Empty until you
**Compute round**.

### 6.5 Debrief

Under the scoreboard, for any computed round:

1. Pick the **Round** in the selector.
2. Read across: revenue, cost, profit, cash, share, lost sales, capacity used/available.
3. **Detail** on a company expands a three-column breakdown, so the arithmetic reads left to right:
   - **Revenue** — revenue for the round, and units lost to stock-outs
   - **Cost** — cost of goods sold, payroll, marketing, R&D, fixed cost, stock holding,
     hiring/firing, capacity, market review, then **Total cost**
   - **Result** — opening cash (the balance the round started from), profit, any stock and capacity
     movement, then cash after the round, brand after the round, capacity used, market share

   **Opening cash + profit + stock/capacity movement = cash after the round**, and each round opens
   exactly where the previous one closed — that is the continuity line

   Revenue − Total cost = Profit, on screen, with no line hidden: anything the engine charged that is
   not one of the named lines appears as **Other charges** instead of being folded into a total.
4. The **Class** footer sums revenue and profit for the whole class.

Every number comes from that round's statements — the debrief cannot disagree with what the engine
charged.

### 6.6 Live

A feed of what has happened in the session (`round.opened`, `decision.submitted`, `round.closed`).

---

## 7. Trainee — playing a round

1. Sign in with the trainee account (tenant + email + password).
2. The decision screen shows the company, **Round x of y**, cash, brand, machines.

If you see **"No simulation yet"**, one of these is true — the page re-checks every 10 seconds, so
nothing needs reloading once the facilitator acts:

| What it means | Who fixes it |
|---|---|
| you are not seated in any session | facilitator: seat you in the Trainees card |
| you are seated but **no round is open** yet | facilitator: **Open next round** |
| the round was computed and the next one is not open | facilitator: **Open next round** |
3. Fill in:
   - **Production and price** — units to produce and the price per product (stock and unit cost are
     shown for reference)
   - **Workforce and capacity** — production staff
   - **Marketing** — spend per channel
   - **Research and development** — investment per project, *this month*
   - **Market review** — optional paid report; the cost is deducted from the result
4. **Submit round n**. Submitting again replaces the decision until the round is computed.

Errors come back in your own language and name the field, e.g. *"P1: the price must be greater than
zero"* or *"Not enough cash: the plan needs X, you have Y."*

---

## 8. Admin — accounts

**People** lists every account in the tenant with role and status.

- **Add a trainee** — name, email, initial password (8+), and the **Role** selector:
  **Trainee** / **Facilitator** / **Builder** / **Admin**. Only an admin sees the role selector; the
  server refuses staff roles for anyone else.
- **Set password** — a per-row button (admins only). Use it when someone cannot sign in: a
  mistyped credential at creation, a forgotten password, or a password handed to the wrong person.
  The new password needs 8+ characters and is stored hashed; hand it over and they can change it
  again after signing in.
- **Change a role** — the role cell in the list is a dropdown for admins. Pick the new role and it
  saves immediately; the account keeps its password and its history. Two guards: you cannot change
  your **own** role (ask another admin), and the **last active admin** cannot be demoted — create a
  second admin first.
- **Deactivate** stops a login and keeps their history — use this when a person leaves. **Activate**
  reverses it.
- **Delete** removes an account that never played. Once someone has a computed decision, deletion is
  refused with *"deactivate the account instead"* — that is deliberate, history must stay truthful.

### First account (fresh deployment)

The server creates the first account from three environment variables on start:

```
BIZSIM_BOOTSTRAP_TENANT       e.g. demo
BIZSIM_BOOTSTRAP_EMAIL        your email
BIZSIM_BOOTSTRAP_ADMIN_SECRET 8+ characters
BIZSIM_BOOTSTRAP_ROLE         optional, defaults to ADMIN
```

Keep it an **ADMIN**: only an admin can create the builder and facilitator accounts. Existing
accounts are never rewritten by this; remove the variables once you have signed in.

---

## 9. Rules that surprise people

| Rule | Why |
|---|---|
| The shipped template is read-only | every class would otherwise be playing a different economy |
| Duplicate to edit | your copy is yours; the base stays a reference |
| Catalogue locks after a session used the template | decisions store product codes |
| Published packages are frozen | a class that was prepared cannot be changed mid-term |
| Re-publishing makes a new package | the old one stays as it taught |
| One session per facilitator | a second parallel cohort needs a second account |
| Sessions are disposable, accounts are permanent | deleting a run keeps the people |
| A builder cannot see sessions | preparing is not watching |

---

## 10. If something looks wrong

| What you see | Cause / fix |
|---|---|
| `Unauthorized` / `invalid or expired token` | sign out and in again; the token lasts 8 hours |
| **sign-in says the password is wrong** | the tenant code and email are now matched ignoring case, so check instead: (1) the account is **active** — a deactivated account gives the same answer, (2) the password actually has 8+ characters, (3) no stray spaces pasted in. Admin fix: **People → Set password** |
| `This action is for facilitators` | you are signed in as a builder; use a facilitator account for sessions |
| `only a builder prepares…` | the account is a facilitator; templates/companies/packages need a builder or admin |
| `No published packages yet` | a builder has not published one; see §5 |
| `you already have a simulation in progress` | finish or delete the current run, or use another facilitator |
| `the shipped profile is read-only` | duplicate it first |
| **no Role dropdown when adding someone** | you are signed in as a facilitator, or the deployed build predates the selector — an admin sees it; otherwise redeploy (§10, `api/version`) |
| **after a redeploy the screens look unchanged** | the console's `index.html` was cached. Hard-refresh (Ctrl+Shift+R) or use a private window; from this release it is served `no-store` and cannot stick |
| **no admin account at all** (everyone is a facilitator or trainee) | set `BIZSIM_BOOTSTRAP_EMAIL=<existing account>` + `BIZSIM_BOOTSTRAP_PROMOTE=true` in Dokploy, restart, sign in, remove the variables — role only, password unchanged |
| **created an account with the wrong role** | **People** → the role cell is a dropdown for admins → pick the right role; no need to delete and recreate |
| `this package has been retired` | publish a new package |
| Blank screen, or a feature you were told exists is missing | check `https://<domain>/api/version`: `builtAt` is the build time of the running image. If it predates the change, redeploy. `latest` only tracks database migrations, so a UI-only release does not move it |

---

## 11. Run sheet for a class

**Before (builder, days earlier)**

1. Duplicate the template, adjust settings / environment / events / catalogue, **Save** each tab.
2. Create or copy the company profiles.
3. Create the trainee accounts (People) and a facilitator account — an admin does this.
4. Publish a package with those companies **and the class list** (one trainee per company).
5. Hand the package name to the facilitator.

**On the day (facilitator)**

5. Sign in → **Sessions** → **Start a session** → pick the package → tick trainees → **Start session**.
6. **Open next round** → trainees decide and submit → **Compute round**.
7. Repeat for each round; watch the **Live** feed.
8. **Debrief** → pick the round → walk the class through revenue, cost and profit, expand **Detail**.

**After**

9. **Delete run** if you want a clean slate (accounts stay), or leave it as history.
10. **Tidy up** on Packages removes retired, never-used packages.
