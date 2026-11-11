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
| quality (D40) | **Offer quality upgrades to the class** (checkbox, off by default), quality cost per point |
| **Per function** (table) | salary and **starting headcount** for Production, Sales, R&D, Admin |

The quality switch is a per-template mechanic: **off means the trainee form has no quality card and
any money sent to it is refused**. On, each company may buy quality points for a product at the price
you set, and the level persists across rounds up to 100. A template written before this feature has
no switch and therefore behaves exactly as it did.

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
4. **Class language** — English, 中文, or *follow each trainee's own choice*. A trainee seated from this
   package reads the interface in that language for the whole session, whatever their own preference
   says. Their preference is not overwritten: the class language is scoped to the session and their
   stored choice is untouched.
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

**Class standings** on the run page is the exact table the class reads — rank, company, cash after the
round and that round's profit, **ranked by cash** — and the switch that decides whether they may read
it. It starts **hidden**: press **Show the class** after
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
   - **Result** — opening cash (the balance the round started from), profit, any **stock and capacity
     movement** (cash tied up in stock built this month and in machines bought — neither is a cost, so
     neither touches profit), then cash after the round, brand after the round, capacity used, market
     share

   **Opening cash + profit + stock/capacity movement = cash after the round**, and each round opens
   exactly where the previous one closed — that is the continuity line. The same breakdown is shown
   to the trainee for their own company (§8), so a learner can go over their round outside class.

   Revenue − Total cost = Profit, on screen, with no line hidden: anything the engine charged that is
   not one of the named lines appears as **Other charges** instead of being folded into a total.

   Under the three columns, **Per product** breaks the same round down line by line — one row per
   product with **Opening**, **Produced**, **Demand**, **Sold**, **Lost**, **Closing**, **Revenue**
   and **COGS**. This is the answer to "82 pieces were lost — of which product?" and "0.94 left in
   stock — of which product?": a company that stocked out on one product and kept a little of
   another shows both, separately. The rows add up to the totals above, because they are the same
   stored lines, not a second calculation. Units are whole pieces (D34); the market share stays a
   percentage of the whole market.
4. The **Class** footer sums revenue and profit for the whole class.

Every number comes from that round's statements — the debrief cannot disagree with what the engine
charged.

### 7.5 Presenting and exporting the debrief

Three buttons sit next to the round selector:

| Button | What you get |
|---|---|
| **Present** | a full-screen classroom view of the selected round. Page 1 is the six numbers you read out — profit, cumulative profit, market share, cash, brand, stock — one card per company, labelled with the trainee. Page 2 is the explanation: lost sales, capacity used, R&D completed, status. **← →** change page, **↑ ↓** change round, **Esc** closes. |
| **Download PDF** | an A4 landscape report of the whole term. It opens with **two summaries** — *Summary by trainee* (one page per company: its rounds, then a Total line) and *Comparison by month* (one page per round, every company side by side) — and then gives the detail behind each month: what each company sent and every cost line. Company and trainee names are rendered as authored, including Chinese. |
| **Download Excel** | the same numbers as a workbook: **By trainee** (one row per company across the term: rounds, totals, final cash, average share), **Results** (one row per company per round), **Submissions** (the plan per company per round) and **Cost lines**, each with a Round column so the whole term can be filtered or pivoted. |

Both files are built from the stored results, never a recomputation, so a downloaded report matches
what the class saw on screen. **The document is written in the language you are using** — switch the
console to 中文 and the download comes out in Chinese, labels and sheet names included. Company,
product and trainee names stay exactly as they were entered. Present view is also what prints cleanly: Ctrl/Cmd+P in **Present** mode
hides the console and keeps the cards.

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
     capacity used) — click a row to choose it;
   - the chosen round in **detail** under Revenue · Cost · Result;
   - **Per product**, the same round broken down by product (opening, produced, demand, sold,
     lost, closing, revenue, COGS) — this is where "which product lost those pieces?" is answered
     for the trainee too.

   **Competitors' figures and the market share stay on the facilitator's screen**; the trainee sees
   only their own company. Everything comes from the same statement rows the debrief reads.

6. **Class standings** — above your results, once your facilitator has shown it and a round has been
   computed: rank, company, cash after the round and that round's profit, in that order of importance —
   **the class is ranked by cash**. Your own line is highlighted. That is all a trainee sees of anyone
   else — no market share, no cost lines, no statements. Your facilitator decides when it appears.
7. **Development report (PDF)** beside them is your term report: six dimensions — liquidity,
   profitability, market management, operations, pricing and investment — each graded and explained in
   one sentence from your own figures, with what to try next term. It is written in the language of
   your class and it never compares you with a named competitor.
8. **My results (PDF / Excel)** in the same card gives the trainee their own report, **one page per
   round**: that month's summary, then what they sent, then the cost lines — their company only, no
   market share, no competitor line. A trainee who has not been seated in the session gets a
   refusal, not an empty file.

Errors come back in your own language and name the field, e.g. *"P1: the price must be greater than
zero"* or *"Not enough cash: the plan needs X, you have Y."*

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
