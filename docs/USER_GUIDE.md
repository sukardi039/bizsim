# bizsim — user guide

*Printable PDF: `docs/bizsim-user-guide.pdf` (regenerate with
`python scripts/md_to_pdf.py docs/USER_GUIDE.md docs/bizsim-user-guide.pdf`, needs `fpdf2`).*

How to sign in, which section each role sees, and what to click to get a class from
"nothing prepared" to "debrief on screen".

The product has **two jobs done by two people**:

| Job | Role | Does |
|---|---|---|
| **Building** | `BUILDER` | prepares templates (the economy), companies, **people (the trainees)**, and **packages**; happens well before a class |
| **Running** | `FACILITATOR` | starts sessions from a published package, opens/computes rounds, runs the debrief |
| both | `ADMIN` | everything above |
| neither | `PLAYER` | the trainee: signs in, decides, submits |

A `BUILDER` cannot run a session. A `FACILITATOR` does not build anything: the template, the
companies, the people and the package all arrive prepared, and their job is only to run the class.
A facilitator never seats anyone either: the class list is sealed inside the package, and a seat
left open stays open. This is enforced by the server, not just hidden on screen — if you cannot see a section, it is the
wrong account, not a bug.

---

## 1. Signing in

**Interface language.** The console ships in English and Chinese. Yours comes from, in order: the
**class language of the package** you are seated under (trainees), your own choice on the account,
the tenant default, then English. Template content — product names, trend labels, event text — is
never translated (D23).

1. Open your site: `https://<your-domain>/` — the browser serves the console and forwards
   `/api` to the backend.
2. Fill the three boxes on the sign-in card:
   - **Tenant** — your organisation's code, e.g. `demo`
   - **Email** — the address your account was created with (case and stray spaces are forgiven)
   - **Password** — at least 8 characters; the first password is handed to you, change it later
3. Press **Sign in**.

Top left after signing in: a **language selector** (English / 中文) and **sign out**. The language is
saved on your account, so it survives a reload. It translates the interface only — the content you
author (product names, event text, trend labels) is never translated.

**Forgot the tenant code or have no account?** An admin creates accounts (People → Add). If nobody
can sign in at all, see §9 "First account" below.

---

## 2. What each role sees

| Nav item | Builder | Facilitator | Admin |
|---|---|---|---|
| **Packages** | yes | — | yes |
| **Templates** | yes | — | yes |
| **Companies** | yes | — | yes |
| **People** | yes | — | yes |
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

### 3.1a Reading the debrief's position map (D101)

The debrief draws each company as a bubble: **price index** across, **quality index** up, both against
the class average of 1.00 (the dashed cross), and **bubble size = market share**. Four quadrants are
named — cheap, premium, stuck in the middle, priced above what they offer — so you can point at the
position rather than describe it.

- Each product is indexed **against its own class average**, then weighted by revenue actually booked.
  A company at 1.25 is a quarter dearer than the field **for the same product**, however wide its line.
- **Dashed trails** are earlier rounds: where each company has been, not only where it stopped.
- If **nobody has bought quality** this round, every quality index is 1.00 and the vertical axis switches
  to **brand** (indexed the same way), with a line under the title saying so. The map tells you when it
  cannot show quality rather than showing a flat line and letting you believe it.

Use it in the debrief to ask two questions: *is anyone actually differentiated, or is everyone competing
on price alone?* and *is the class converging on one position — which is Porter's "stuck in the middle"
in slow motion?*

### 3.1b The three presets (D100)

Four templates ship with the product: the standard economy and three **presets**, each tuned to make
one way of competing pay. Run the same class three times, on one preset each, and the position decides
who wins:

| Preset | It rewards | How it is built |
|---|---|---|
| **Cost leadership** | cheap, high volume, one product built fast | price moves demand hard, quality and brand hardly at all; the mass product is cheaper to make and faster to build; the market is three times the standard one, so volume can fill a factory |
| **Differentiation** | premium price, quality and marketing | quality and brand carry the buying decision, price is almost ignored, and the position costs 80 M a round to hold |
| **Focus** | one customer class, served properly | no product competes for the other classes' business; the niche is three times the standard size |

All three carry half of last round's share (the `inertia` setting above, 0.5), so a position has to be
defended month after month. Copy a preset and edit it exactly like the standard template.

### 3.1c Marketing channels are not market-specific

The three channels differ in **reach** and in **brand**, not in audience:

| Channel | Reach weight | Brand coefficient |
|---|---|---|
| Trade | 1.2 — reaches furthest for the same money | 1.2 |
| Digital | 1.0 — the reference | **1.6 — builds the most brand, one round later** |
| Field sales | 0.8 — reaches least | 0.8 |

**By default** marketing is one company-wide number: spend in any channel lifts your share in every
product and every class alike, so the choice is *how much* and *which channel mix*. About **20 M in one
channel** is the neutral point: below it you are less attractive than the reference company, and each
further dollar buys less (reach is square-rooted, and enters attraction at a power of 0.7).

**Channels are market-specific as shipped** (Templates → Channels). The three weight columns say how well
each channel reaches each customer class; the shipped template derives them from what the channel is —
Trade reaches the mass market hardest, Field sales the affluent class, and each channel's middle value
equals its own reach weight. Leave a column blank when a channel is not specific to that market: the class
is then contested as one pool, as it was before. Enter 0 for a class the channel cannot reach at all. A stated
weight replaces the channel's own for that class only; leave a cell blank and the channel stays
class-blind for it; enter **0** and that channel cannot reach the class at all. With weights in place the
market is contested **class by class**, so a company can be strong in the mass market and weak in the
premium one on the same product — which is what lets a differentiated position be *earned* rather than
assumed. The company's published share is still one number; the classes are how it was earned.

**Market share by product (D112).** The debrief's pie charts show who holds each product's market. The
circle is the **served market** — the pieces the class actually competed for — and whatever the class failed
to supply appears as its own slice, so the slices add up and a stockout is visible rather than hidden. Each
product gets its own pie; every company appears, including seats played by the system, which are shown by
**company name only**. To show the class, use **Share this map**: the link carries the pies and the position
map for every computed round, opens with no sign-in, and can be withdrawn.

**Channel names are translated too.** A channel's *code* is what a decision names (`TRADE`, `DIGITAL`,
`FIELD`) and never changes with language; its *name* is per language (D114), authored on the Channels tab in
the same translations cell the products use. The shipped templates carry 数字营销 / 经销商渠道 / 直销团队 for a
Chinese class. A language nobody filled in falls back to the authored name, so nothing reads as a blank.

**Markets are whole numbers of items.** The editor refuses a fractional market — a customer class is a
count of buyers, not a quantity — and says which field to fix, so `4000.5` becomes a question rather than
a silent change. Stock and production are whole for the same reason: an item is an item.

### 3.2 The seven tabs

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
| loyalty (D99) | **share carried over** — how much of last round's share a product keeps |
| quality (D40) | **Offer quality upgrades to the class** (checkbox, off by default), quality cost per point |
| **Per function** (table) | salary and **starting headcount** for Production, Sales, R&D, Admin |

The quality switch is a per-template mechanic: **off means the trainee form has no quality card and
any money sent to it is refused**. On, each company may buy quality points for a product at the price
you set, and the level persists across rounds up to 100. A template written before this feature has
no switch and therefore behaves exactly as it did.

**Share carried over** is the same kind of switch, and it is `0` unless you set it. At `0` every
round is a fresh contest: today's price, quality and marketing decide everything, and a company's
lead disappears the month it stops earning it. Set it to `0.5`, say, and each product keeps half of
the share it **actually sold** last round, with the other half re-contested — so a price cut, a
quality drive or a service failure takes a couple of months to work through the market, and a loyal
line is worth defending. It is a weight (0 to 1), applied per product: a company can hold a loyal
line and a failing one at the same time. Leave it off for a short course where every month should
teach its own lesson; turn it on when you want the class to argue about *defending* a position.

**Environment** — the round-by-round timeline. Each row belongs to a **language** (D86), and one
row is one round of that language's cycle: *language*, *round*, *demand index*, *cost index*, *wage
index* (1.00 = normal), *trend strength*, *trend label* (free text, shown to trainees), *note*, and
the **description** the workbench prints as the round's narrative. Every language present must have
the same number of rounds — the controller refuses a set with a gap. Use **Add a row** / **Remove
the last row** (they act on the language of the last row) and **Copy from … to …** to duplicate one
language's cycle into a new language code, then translate it in place. **Save** stores the whole
timeline.

**Customer classes** — the three groups of potential customer (D81). Each row is a class: code, name,
description, **translations**, *customers* (base units per round, sized
for ten companies), *purchasing power* (1.000 = the class affords that base), *income elasticity*
(how hard a downturn hits the class) and *trend affinity* (how much a consumer trend moves it). An
economic event never touches a product directly: it moves a class's customers and purchasing power,
and every product serving that class inherits the change. A class that a product points at cannot be
removed, and a template with sessions on it refuses edits — copy the template instead. Classes are
one mechanic with per-language words, not a set per language.

**Events** — the event deck: surprises drawn once per run, one **set per language** (D86).

| Column | Meaning |
|---|---|
| Language | which language's deck this card belongs to; a class only ever draws its own |
| Code | unique short id inside that language, e.g. `EV-OIL-CRISIS` (`…-ZH` in the Chinese set) |
| Name / Description | what the class sees, in that language |
| Kind of shock | supply shock, financial crisis, inflation, technology, demand shock, policy, **markets**, other — printed in the workbench |
| From round / To round | the window in which it can be drawn |
| Chance | probability 0.00–1.00 inside that window |
| Months | how long the effect lasts (1–12) |
| Demand ×, Cost ×, Wage × | market-wide legacy multipliers (1.00 = no change) |
| Target | one product code, or *all products* — legacy only |
| per class | two numbers: **customers multiplier** and **purchasing power multiplier** for that class |

Leave the deck empty for a calm economy. **Save** replaces the whole deck (all sets). The server
refuses duplicate codes within a language, a probability outside 0–1, `From` > `To`, non-positive
multipliers, a class named twice in one card, and an effect on a class the template does not have.

**Adding a language.** Choose **Copy from** the language you want to start from, type the new code
(two lowercase letters, e.g. `ms`), press **Copy set**, then edit the copied cards and rounds: names,
descriptions and the cycle narrative in the new language. The copy keeps every number and every
class effect, and codes take the new suffix (e.g. `EV-OIL-CRISIS-MS`). A class runs in the language
of its package, so a new language needs no migration and no code change — only data.

**Products** — one row per product: code, name, **translations**, price, unit cost, quality, base
demand, units per worker, income elasticity, trend affinity, **customer class** (which class the
product is made for; blank = the legacy demand pool) and **cross-segment %** (the share of the other
classes' demand the product also competes for).

**Translations** (products and customer classes) — the **Add language** block beside each row holds
that object's name (and description, for a class) per language: type a two-letter code, the name in
that language, and press **Save**. The code and every number stay one row — only the words change with
the reader (D87). A language with no translation shows the name the author wrote, so a new class is
never blank.

**Channels** — code, name, reach weight, brand effect (how much brand helps in that channel).

**R&D projects** — code, name, target product (or all), advantage type
(`UNIT_COST_DOWN`, `CAPACITY_UP`, `QUALITY_UP`, `ATTRACTIVENESS_UP`), size, true cost (hidden from
trainees), duration in months, benefit curve (`LINEAR`, `POWER`, `CHANCE`), over-funding handling
(`REFUND`, `LOST`).

### 3.5 Auto workers

**Auto workers** is a library of reusable opponents. Each has a **level** (Low / Mid / High — how well
it plays) and a **personality** (Cautious / Balanced / Aggressive — what it wants). Creating one stores
its name with the level as a suffix, so a scoreboard reads `Market 1_high`, and gives it an identity
that can be seated like a person — but can never sign in.

To put one in a class:

1. Open the template you will publish from — **Templates → the template's name → Settings** — and tick
   **Offer auto workers to the class**, then **Save**. It sits directly under the quality switch, at the
   end of the settings grid. Templates written before this switch existed keep playing without
   opponents until it is ticked; the global `standard-v1` is read-only, so duplicate it first if you
   need to change it.
2. In **Packages**, pick the template and the companies; the class list now offers the auto workers
   beside the trainees, labelled `· auto`.
3. Publish and start the session as usual. The opponent plays every round by itself, under the same
   submission rules, the same clock and the same validation as a trainee.

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

## 5. Builder — People (the class)

People are part of the preparation, exactly like the template and the companies (D30). The builder
adds the trainees, because the class list travels inside the package and the facilitator only runs
the session.

1. **People** in the left navigation.
2. **Add a trainee** — name, email, initial password (8+ characters). A builder only ever creates
   **Trainee** accounts: the Role selector is an admin's, and the server refuses staff roles for
   anyone else.
3. Hand the credential over; the trainee can change it after signing in.
4. Back in **Packages**, tick a company and pick the trainee in the class list — one person per
   company (D27). A seat left open means that company plays with nobody; nothing can fill it later.

- **Show trainees already seated in a company** — a switch above the list, **off** by default. With it
  off the list shows only the people still free to put in a company: anyone who already holds a seat in
  a package's class list or in a session is left out. Switch it on to see the whole roster again —
  that is what you need before resetting the password of someone who already played.
- **Set password** — the per-row button fixes a mistyped or forgotten credential (8+ characters).
  A builder may reset a **trainee's** password; a staff account's password stays an admin's business.
- **Deactivate / Activate** — for someone who leaves, before or after playing. The login stops, the
  history stays readable.
- **Delete** — only an account that never played; once a decision was submitted the server answers
  *"deactivate the account instead"*.
- **Change a role** — admin only, in the same list.

A facilitator does not see **People** at all: the class arrives with the package they start.

---

## 6. Builder — Packages (the hand-over)

A package is the immutable bundle you hand to the facilitator: **one template + the companies + the
class list**.

1. **Packages** → **Publish a package**.
2. Give it a name used in class, e.g. *Autumn 2026*.
3. Choose the **Template** (your edited copy, not the global one).
4. **Class language** — English or 中文. A trainee seated from this package reads the interface, the
   environment and the R&D catalogue in that language for the whole session, whatever their own
   preference says. Their preference is not overwritten: the class language is scoped to the session
   and their stored choice is untouched.
5. Tick the **Companies** that will compete.
6. **Class list** — for each company, pick the trainee who will run it (accounts come from **People**).
   One person per company; the same person cannot sit in two competing companies. A seat left open
   means that company plays with nobody: **there is no way to fill it after publishing** (D33).
7. **Publish**.

The class list is part of the package because preparing happens before the class, not on the day.

Publishing **freezes a private copy of the template** and records its version. Editing your draft
afterwards changes nothing about this package — to change a published economy, edit the draft and
publish a **new** package.

**Retire** stops new sessions from using a package; sessions already run from it keep their history.

**Tidy up** appears when retired packages never ran a session; **Remove N** deletes them (and their
frozen copies) after a confirmation. Packages that taught a class are never offered.

---

## 7. Facilitator — running a class

### 7.1 Start a session

1. **Sessions** in the navigation. With no session selected you see **Start a session**.
2. Type a **Session name** (defaults to the package name).
3. Choose the **Package** — the line below shows the competing companies and the template version,
   so you can confirm you are running what you think you are.
4. Check the **Class list** shown under it: one line per company with the person prepared for it
   (*seat empty* means that company has no trainee — nobody can fill it once the session starts).
5. **Start session** — everyone on the class list is seated automatically.

The session opens with one company per profile, its product line already set, and the prepared class
already seated. If a package is missing, no builder has published one yet.

### 7.2 The run screen

Every company row on the scoreboard and in the debrief is labelled with the trainee seated in it
(*Alpha Industries · Ana*), so "how did she do?" is answered without a separate roster — the seating
itself stays with the builder and cannot be changed here (D33, D36).

Header: `Run <n>` · status · `round x of y` · cycles. There is **one** round button, because only
one of its two jobs is ever the right move (D38):

| What the button says | What it does |
|---|---|
| **Open round N** | nothing is open: opens round N for decisions (round 0 → 1 → …) |
| **Compute round** | a round is open: closes it and calculates results for every company |
| **Session finished** (greyed) | the last round is computed; nothing more to open |

One round is therefore two clicks with a pause in between — **Compute round**, read the debrief,
then **Open round N+1**. **Delete run** removes the session and everything played in it —
**accounts survive**.

You can only have **one session in progress at a time**. To run two cohorts in parallel you need two
facilitator accounts.

### 7.2.1 The round clock, the submissions and the audit trail

Under the header there is a **Round clock** card. It is the clock that closes the round automatically
when the time you set in the template runs out (D12):

| Button | What it does |
|---|---|
| **Pause clock** | stops time. The remaining time stops counting, nothing computes, and trainees can still submit. Use it for a discussion in the middle of a round. |
| **Resume clock** | starts it again and gives back exactly the time it was stopped — a clock paused for six minutes runs six minutes longer. |
| **Add 5 minutes** | moves the deadline out by five minutes (works while paused, and on a round that had no clock). |

**Void round N** appears when nothing is open and at least one round has been computed. It undoes the
last computed round: every company goes back to the state it had before that month, that round's
results and statements are deleted, and the round opens again so the class can correct the decision
and you can compute it again. The decisions themselves are kept — the class corrects a month, it does
not retype it. You are asked for a reason, which is kept in the audit trail. Rounds computed before
this feature existed carry no snapshot and are refused with *"cannot be restored"* rather than
half-undone.

**Submissions** lists every company with *submitted* or *not yet*, who sent it, and **show the month**
expands exactly what they asked for (volumes and prices, marketing, R&D, quality, staff, machines).
**Adjust cash** on the same row moves one company's balance by hand: type the amount (**negative to
fine, positive to lend or compensate**) and a reason of at least three characters, then **Apply**. The
change is immediate and lands in the audit trail; the class can see it.

**Audit trail** is the session's log of every hand laid on it — clock paused/resumed/extended, cash
adjusted, round voided — with the round, the company, the amount, the reason, who did it and when.
When a class asks "why did our cash jump?", this table is the answer.

### 7.2.2 The opponents

When a session has auto-played companies, **Auto workers this session** lists one row per opponent per
round: the brain and its version, how it was called (**scheduled**, **by hand**, or **missed**), and its
one-line reason. **Replay** re-runs the same input and compares — *"the same input produces the same
decision"* means the trace can be trusted in front of the class; a difference means the brain was
edited after that round.

**Ask them to move now** makes the opponents submit immediately instead of waiting for their moment.
It is recorded as *by hand*, so the log stays honest.

### 7.2.3 The class table

**Class standings** on the run page is the exact table the class reads — rank, company, cumulative
profit, ROCE, cash after the round and that round's profit, **ranked by cumulative profit** (D98) —
and the switch that decides whether they may read it. It starts **hidden**: press **Show the class** after
reading the debrief, and every computed round is then visible to the trainees; **Hide from the class**
takes it away again. Both moments are in the audit trail.

### 7.2.3b Development report, per company

Next to **Detail** on each row of the debrief there is **Development report**. It downloads one PDF
for that company: six dimensions — liquidity, profitability, market management, operations, pricing
and investment — each with a band (strong / steady / needs attention), one sentence quoting the
figures, and the three pieces of advice the report considers most urgent. It is the same document a
trainee can download for themselves (D76), generated here for any company in the session. It is
written in the language of the class, and it never names another company: it is a report on how this
company was run, not a comparison.

### 7.2.4 Starting over

**Reset the session** puts the run back to its pre-start condition: every company returns to the cash,
brand, stock, staff and R&D it opened with, and every round — with its decisions, results and
statements — is deleted. The class begins again from month 1 with the same people seated; nothing has
to be re-registered.

It asks for confirmation first, and it is recorded in the audit trail. Use it for a botched setup or a
demonstration that has to be repeated, not to correct one month — for that, **void the last round**.

### 7.2.5 Programmes of several simulations

A package can hold **several parts** — a course rather than one simulation. The class list is chosen
once, so the same companies and the same people play every part; each part uses its own template and
starts from the same opening condition, so the parts can be compared fairly.

- **Starting:** pick the part in **Start a session** (Part 1, Part 2…). A part only opens once the
  previous one is finished. **A part is started once:** parts that already have a session are not
  offered in the list (they are named underneath as *already started*), and *Start session* is
  disabled when every part has begun. To replay a part, **Reset** that session or publish a new
  package — you cannot open a second session of the same part by accident.
- **Comparing:** the run screen of any part shows **Programme comparison** — each company's profit in
  every part, the move against the previous part, and the move against the class average.
- **Resetting:** reset one part from its run screen, or the whole programme from the package.

### 7.3 Scoreboard

One row per company for the latest computed round: profit, cash, share, stock. Empty until you
**Compute round**.

### 7.4 Debrief

Under the scoreboard, for any computed round:

1. Pick the **Round** in the selector.
2. Read across: revenue, cost, profit, cash, share, lost sales, capacity used/available.
3. **Detail** on a company expands its month, in reading order:
   - **Submission · round N** — what the company asked for: units, price and quality spend per
     product, marketing per channel, R&D per project, production staff and machines, and whether the
     market review was bought. A company that sent nothing says so.
   - **Results · round N** — then a three-column breakdown, so the arithmetic reads left to right:
   - **Revenue** — revenue for the round, and units lost to stock-outs
   - **Cost** — cost of goods sold, payroll, marketing, R&D, fixed cost, stock holding,
     hiring/firing, capacity, market review, then **Total cost**
   - **Result** — opening cash (the balance the round started from), profit, any stock and capacity
     movement (cash tied up in stock built this month and in machines bought — neither is a cost, so
     neither touches profit), then cash after the round, brand after the round, capacity used, market
     share. Where the statement can name the parts, two lines sit underneath it — "其中：存货变动"
     and "其中：研发超额退回" — so the number is explained rather than asserted.

   **Opening cash + profit + stock/capacity movement = cash after the round**, and each round opens
   exactly where the previous one closed — that is the continuity line. The same breakdown is shown
   to the trainee for their own company (§8), so a learner can go over their round outside class.

   Revenue − Total cost = Profit, on screen, with no line hidden: anything the engine charged that is
   not one of the named lines appears as **Other charges** instead of being folded into a total.

   Above the table, **Where each company sits** places every company on **price against quality**,
   both relative to the class average (1.00), with the bubble sized by market share (D98). It is the
   one picture that answers "are we the cheap supplier, the premium one, or stuck between the two?".

   Under the three columns, **Per product** breaks the same round down line by line — one row per
   product with **Opening**, **Produced**, **Demand**, **Sold**, **Share**, **Lost**, **Closing**,
   **Revenue** and **COGS**. **Share** is that product's own market share: units sold against the
   product's whole market pool, on the same denominator as the company share in the scoreboard (D97),
   so a company can be strong in one line and weak in another and see both. This is the answer to "82 pieces were lost — of which product?" and "0.94 left in
   stock — of which product?": a company that stocked out on one product and kept a little of
   another shows both, separately. The rows add up to the totals above, because they are the same
   stored lines, not a second calculation. Units are whole pieces (D34); the market share stays a
   percentage of the whole market.
   **Share by customer class** sits above the position map (D115). One small table per product, one row
   per company, one column per customer class. A cell is **the demand the company was awarded in that class,
   as a share of that class's served market** — the pieces the class actually contested — with the awarded
   pieces underneath. It is *not* units sold: a company that stocked out still won its share of the class and
   sold less, and that shortfall appears on its product line as lost sales. The tables exist only on a
   template that weights channel reach by class; on a class-blind template the market is one pool and there is
   nothing to break down.

4. The **Class** footer sums revenue and profit for the whole class.

Every number comes from that round's statements — the debrief cannot disagree with what the engine
charged.

### 7.4.1 The three reports (profit and loss, cash flow, assets and liabilities)

Under the per-round detail, every company's month ends with three reports for the same round (D88).
They are read top to bottom, and the trainee sees exactly the same three for their own company (§8).

1. **Profit and loss** — **Revenue**, less **Cost of goods sold**, gives **Gross profit**. Under it
   sit the operating expenses: payroll, marketing, R&D, quality upgrade, fixed cost, stock holding,
   hiring and firing, and the market review. Their total is **Total operating expenses**, and revenue
   less all of it is **Operating profit** — the same profit the scoreboard ranks.
   A note under the table says the thing trainees always ask: *machines bought this month are not an
   expense; they are on the balance sheet at cost.*
2. **Cash flow statement** — starts from the round's profit, removes the part of it that was not cash
   by showing the **change in inventories**, and arrives at **net cash from operations**. **Machines
   bought** and any **R&D refund received** are investing; **financing is zero** because the model
   has no borrowing. **Net movement in cash** plus the opening cash equals the closing cash the
   round detail shows above.
3. **Assets and liabilities** — **cash + inventories + machines at cost = total assets**;
   **liabilities** are zero and printed as a line rather than left out; **paid-in capital** (the
   starting cash), **retained earnings** (profit of every round so far) and any **R&D refunds
   received** add to **total equity**; and **total liabilities and equity** equals total assets.

Two lines appear only when there is something to say:

- **R&D refunds received** — money returned when a project was over-funded. The cash came back while
  the profit and loss had already charged the full request, so it sits in equity and is named.
- **Inventory revaluation** — only on old statements computed before the weighted-average basis
  (D79), or when stored stock did not carry into the next round. It is shown, never folded away, and
  a **Difference** line appears whenever the two sides do not agree.

Nothing here is recomputed: each figure is read from the round's stored statement, so an old round
still reads as it did on the day it was computed.

The statements are three separate panels, each with its own heading and the round it covers (a badge
top-right, and a line under the title saying whether it is *for the round* or *as at the end of the
round*). Inside each panel the figures run down one column, aligned on the right, with a **subheading
for every block of lines** — Income and Operating expenses, Operating / Investing / Financing
activities and Cash movement, Assets and Liabilities and Equity. A rule separates each subtotal, a
double rule closes the result, and the balance sheet is printed in **two stacked blocks** — **Assets**
first, then **Liabilities and equity** — each at full width, so a label and several rounds of figures
have the room they need (D95).

The facilitator reads them inline under the company detail. A **trainee** opens them one at a time in
a pop-up from the buttons beside *My results* (§8, D91), so their results card stays a short page
rather than three long statements stacked on it.

Every report shows **the round in front of you and the two before it** (D92), one column per round,
named above its figures, **oldest on the left and the current round in the right-most column** — the
way a comparative statement is printed (D94). A comparison is what a debrief is for, and it is the
question trainees ask — *"is this better or worse than last month?"*. Early in a term there is less to
compare: round 1 has one column, round 2 has two, and from round 3 on there are three. A line that a
round did not carry prints as zero in that column, so the columns stay aligned.

### 7.5 Presenting and exporting the debrief

Three buttons sit next to the round selector:

| Button | What you get |
|---|---|
| **Present** | a full-screen classroom view of the selected round. Page 1 is the six numbers you read out — profit, cumulative profit, market share, cash, brand, stock — one card per company, labelled with the trainee. Page 2 is the explanation: lost sales, capacity used, R&D completed, status. **← →** change page, **↑ ↓** change round, **Esc** closes. |
| **Download PDF** | an A4 landscape report of the whole term. It opens with **two summaries** — *Summary by trainee* (one page per company: its rounds, then a Total line) and *Comparison by month* (one page per round, every company side by side) — and then gives the detail behind each month: what each company sent and every cost line. Company and trainee names are rendered as authored, including Chinese. |
| **Download Excel** | the same numbers as a workbook: **By trainee** (one row per company across the term: rounds, totals, final cash, average share), **Results** (one row per company per round), **Submissions** (the plan per company per round), **Cost lines** and **Statements** (every line of every report, with its statement, section, level and amount), each with a Round column so the whole term can be filtered or pivoted. |

Both files are built from the stored results, never a recomputation, so a downloaded report matches
what the class saw on screen. **The document is written in the language you are using** — switch the
console to 中文 and the download comes out in Chinese, labels and sheet names included. Company,
product and trainee names stay exactly as they were entered. Present view is also what prints cleanly: Ctrl/Cmd+P in **Present** mode
hides the console and keeps the cards.

**Where the three statements travel (D88, D92).** The workbook carries them in full on the
**Statements** sheet, for every company and round. A **trainee's** PDF prints them **once, at the end,
for every round of the term**, a column per round — the progression in one table rather than the same
statement repeated on each month's page. The **class** PDF does not carry them: it is the projection
document for the room, and the statements belong to one company at a time — open a company's
**Detail** on screen for those, or read them from the workbook. The **Submissions** sheet and the
PDF's *What you sent* table list the products each company actually carries, not the whole catalogue
(D89).

### 7.6 Live

A feed of what has happened in the session (`round.opened`, `decision.submitted`, `round.closed`).

---

## 8. Trainee — playing a round

**Guided rounds.** If your facilitator switched it on, the **first one or two rounds** arrive with the
form already filled in — a sensible starting plan. Read it, change anything you like, then submit:
nothing is sent until you press **Submit**, and the results are yours either way. **Start from blank**
clears it if you would rather build your own. From the following round the form is blank again.


1. Sign in with the trainee account (tenant + email + password).
2. The decision screen shows the company, **Round x of y**, cash, brand, machines — but only while a
   round is **open**. Before the facilitator opens round 1 you get a waiting card (*"Waiting for your
   facilitator to open round 1"*) with **no form and no submit button**; the same happens between
   rounds. The page re-checks every 10 seconds, so nothing needs reloading once they open it, and
   your results from rounds already computed stay readable underneath.

If you see **"No simulation yet"**, you are not on any class list: the builder seats you in the
package's class list and publishes a new package; the facilitator cannot seat you during a session.

The **round clock** is on your screen while a round is open: *12:34 left in this round*, turning red
under a minute, and *Paused at 12:34* while your facilitator has stopped time. It counts down on its
own — you do not need to reload.

**Market environment.** Above the decision form is a card that explains the economy you are selling
into: a sentence on what this round is doing, then one row per **class of potential customer** —
which product serves it, how many customers there are now, and their purchasing power (×1.00 is
normal). Under it, the economic events that have already started are listed with their kind — an oil
crisis, inflation, a technology slump — and one sentence each. Economic events move the *classes*,
not your products directly, so a premium line suffers in a recession because its customers are the
ones postponing purchases, and the card is where you can see that happening. The words are in the
language your class runs in.

| What it means | Who fixes it |
|---|---|
| you are not seated in any session | builder: seat you in the package class list (People → then Packages) |
| you are seated but **no round is open** yet | you see the waiting card; facilitator: **Open next round** |
| the round was computed and the next one is not open | you see the waiting card; facilitator: **Open next round** |
3. Fill in:
   - **Production and price** — units to produce and the price per product (stock and unit cost are
     shown for reference)
   - **Workforce and capacity** — production staff
   - **Marketing** — spend per channel
   - **Research and development** — investment per project, *this month*. A project that has
     already finished (**COMPLETED** or **FAILED**) shows a closed field and *"No further
     investment"*: the money cannot be spent again, and leaving it at 0 does not block the round.
   - **Quality upgrade** — only when your template offers it (D40). One line per product: the level
     the product carries now, how much you spend this month, and the points that money buys. The
     level stays for every later round and stops at 100; money beyond that is not charged. The spend
     is a cost of the month, like R&D, so it lowers profit and cash by the same amount.
   - **Market review** — optional paid report; the cost is deducted from the result
4. **Submit round n**. Submitting again replaces the decision until the round is computed.
5. **Your results** — once a round is computed, the trainee gets the same summary and detail the
   class debrief shows for their company:
   - a **summary table**, one row per computed round (revenue, cost, profit, cash, lost sales,
     capacity used);
   - **the newest computed round opens by itself** as soon as it is computed — no click needed, and
     the page picks it up within ten seconds on its own (D96). Clicking an earlier row reads that
     round instead, until the next result lands;
   - the chosen round in **detail** under Revenue · Cost · Result;
   - **Per product**, the same round broken down by product (opening, produced, demand, sold,
     **share**, lost, closing, revenue, COGS) — this is where "which product lost those pieces?" and
     "which product am I actually winning?" are answered for the trainee too. **Share** is that
     product's percentage of its own market (D97); rounds computed before the field existed show —.
   - the **three reports** for that round — profit and loss, cash flow statement, and assets and
     liabilities — under the detail, exactly as §7.4.1 describes them. They are your own company's
     books, built from the same stored statement, and nobody else's.

   **Competitors' figures and the market share stay on the facilitator's screen**; the trainee sees
   only their own company. Everything comes from the same statement rows the debrief reads.

6. **Class standings** — above your results, once your facilitator has shown it and a round has been
   computed: rank, company, **cumulative profit**, **ROCE**, cash after the round and that round's
   profit — **the class is ranked by cumulative profit** (D98), with cash shown as information and
   ROCE as profit on the capital the company was given. Your own line is highlighted. That is all a trainee sees of anyone
   else — no market share, no cost lines, no statements. Your facilitator decides when it appears.
7. **Development report (PDF)** beside them is your term report: six dimensions — liquidity,
   profitability, market management, operations, pricing and investment — each graded and explained in
   one sentence from your own figures, with what to try next term. It is written in the language of
   your class and it never compares you with a named competitor.
8. **My results (PDF / Excel)** in the same card gives the trainee their own report — one page
   per round: that month's summary, then what they sent, then the cost lines, then the **three
   statements** for that round (profit and loss, cash flow statement, assets and liabilities) — their
   company only, no market share, no competitor line. The Excel file carries the statements too, one
   row per line on its **Statements** sheet, so the term can be filtered or pivoted. A trainee who has
   not been seated in the session gets a refusal, not an empty file.
9. **The three reports, on screen (D91, D92)** — beside *My results* are three buttons: **Profit and
   loss**, **Cash flow statement**, **Assets and liabilities**. Each opens that report in a pop-up for
   the round selected in the table above, showing **that round and the two before it side by side**
   (one column when you are in round 1, two in round 2). The pop-up carries the report name and the
   same sections and rulings as the facilitator sees — the round is named over its column, not in the
   header — and closes with **Esc**, the **Close** button or a click outside it.

Errors come back in your own language and name the field, e.g. *"P1: the price must be greater than
zero"* or *"Not enough cash: the plan needs X, you have Y."* A price is the one number the system has
to bound for you: it must be at most a hundred times the catalogue price, because a price beyond that
cannot be stored. Nothing realistic comes close — demand collapses long before it.

**Why a loss can end with more cash (D79).** Stock is charged at the price the company actually paid
for it, so cash and profit only differ for three reasons: stock still on the shelf, machines bought,
and research money returned after over-funding a project. None of the three is a cost. A term that
starts and ends with an empty shelf therefore has cash movement exactly equal to its cumulative
profit — if the two ever disagree on such a session, that is a bug, not a teaching point. Older
packages published before D79 use *replacement* costing (stock is revalued at this month's cost), and
their development report says so in the bridging sentence instead of pretending the numbers add up.

---

**Chinese classes:** the R&D catalogue exists in two sets — English and Chinese — and a session reads
only its own, decided by the package's **Language**, which is now required when you publish (English or
中文). There is no "follow each trainee" option: the language also decides which project set the class
competes on, and that cannot vary per reader. Builders see both sets
in the template's **R&D projects** tab, distinguished by the *Catalogue* column (English / 中文).

## 9. Admin — accounts

**People** lists every account in the tenant with role and status. Building and admin see it; a
facilitator does not (D30).

- **Add a trainee** — name, email, initial password (8+), and the **Role** selector:
  **Trainee** / **Facilitator** / **Builder** / **Admin**. Only an admin sees the role selector; the
  server refuses staff roles for anyone else.
- **Set password** — a per-row button. Staff may reset a **trainee's** password; only an admin may
  reset a staff password. Use it when someone cannot sign in: a mistyped credential at creation, a
  forgotten password, or a password handed to the wrong person. The new password needs 8+
  characters and is stored hashed; hand it over and they can change it again after signing in.
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

## 10. Rules that surprise people

| Rule | Why |
|---|---|
| The shipped template is read-only | every class would otherwise be playing a different economy |
| Duplicate to edit | your copy is yours; the base stays a reference |
| Catalogue locks after a session used the template | decisions store product codes |
| Published packages are frozen | a class that was prepared cannot be changed mid-term |
| Re-publishing makes a new package | the old one stays as it taught |
| One session per facilitator | a second parallel cohort needs a second account |
| Sessions are disposable, accounts are permanent | deleting a run keeps the people |
| A company trades only the products its profile gave it | its stock lives in one row per product; a product it does not carry has nowhere to live, so the decision form does not offer it and the server refuses it (D89) |
| A builder cannot see sessions | preparing is not watching |

---

## 11. If something looks wrong

| What you see | Cause / fix |
|---|---|
| `Unauthorized` / `invalid or expired token` | sign out and in again; the token lasts 8 hours |
| **sign-in says the password is wrong** | the tenant code and email are now matched ignoring case, so check instead: (1) the account is **active** — a deactivated account gives the same answer, (2) the password actually has 8+ characters, (3) no stray spaces pasted in. Admin fix: **People → Set password** |
| `This action is for facilitators` | you are signed in as a builder; use a facilitator account for sessions |
| `only a builder prepares…` | the account is a facilitator; templates/companies/packages need a builder or admin |
| `No published packages yet` | a builder has not published one; see §6 |
| `you already have a simulation in progress` | finish or delete the current run, or use another facilitator |
| `the shipped profile is read-only` | duplicate it first |
| **no Role dropdown when adding someone** | you are signed in as a facilitator, or the deployed build predates the selector — an admin sees it; otherwise redeploy (§11, `api/version`) |
| **after a redeploy the screens look unchanged** | the console's `index.html` was cached. Hard-refresh (Ctrl+Shift+R) or use a private window; from this release it is served `no-store` and cannot stick |
| **no admin account at all** (everyone is a facilitator or trainee) | set `BIZSIM_BOOTSTRAP_EMAIL=<existing account>` + `BIZSIM_BOOTSTRAP_PROMOTE=true` in Dokploy, restart, sign in, remove the variables — role only, password unchanged |
| **created an account with the wrong role** | **People** → the role cell is a dropdown for admins → pick the right role; no need to delete and recreate |
| `this package has been retired` | publish a new package |
| Blank screen, or a feature you were told exists is missing | check `https://<domain>/api/version`: `builtAt` is the build time of the running image. If it predates the change, redeploy. `latest` only tracks database migrations, so a UI-only release does not move it |

---

## 12. Run sheet for a class

**Before (builder, days earlier)**

1. Duplicate the template, adjust settings / environment / events / catalogue, **Save** each tab.
2. Create or copy the company profiles.
3. Create the trainee accounts (People) and a facilitator account — an admin does this.
4. Publish a package with those companies **and the class list** (one trainee per company).
5. Hand the package name to the facilitator.

**On the day (facilitator)**

5. Sign in → **Sessions** → **Start a session** → pick the package → tick trainees → **Start session**.
6. **Open next round** → trainees decide and submit → **Compute round**. *Open next round* only opens;
   if a round is still open it refuses and tells you to compute first.
7. Repeat for each round; watch the **Live** feed.
8. **Debrief** → pick the round → walk the class through revenue, cost and profit, expand **Detail**.

**After**

9. **Delete run** if you want a clean slate (accounts stay), or leave it as history.
10. **Tidy up** on Packages removes retired, never-used packages.
