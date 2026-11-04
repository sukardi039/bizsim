# Setup workbench — the pre-session build

Status: **S6a–S6e shipped (3 Nov 2026), and the workbench is now a separate job from execution (D24).** This file is the agreed scope for the
next phase, so the slices can be cut without re-litigating the shape.

Glossary, because it caused a question: a **session** is one simulation run (one `run` row, one class
day). A **training package** is several sessions delivered together (a term, a workshop series). The
product only models the session; a package is a human notion for now.

---

## 1. The requirement (product owner, 3 Nov 2026)

> Deleting a run should delete trainee information at the same time. The current way of creating a
> trainee account is acceptable for testing, but in real life the whole set of simulation template,
> facilitator and trainee accounts must be created up-front, before the start of the simulation
> session. A set-up workbench must be created to build the simulation template (environment, company
> profile, simulation settings, etc.), facilitator and trainee account. Trainee accounts should be
> created by the facilitator at the beginning of the simulation.

Two rulings hide in there, and they are worth separating because only one is code:

- **R1 — session data is disposable.** Deleting a run removes its play data *and* the trainee
  accounts created for it. Done (see `DECISIONS.md` consequence 8).
- **R2 — preparation is a separate phase.** A session is not created in one click on a blank screen.
  Templates, facilitator accounts and trainee accounts are prepared *before* the session, in a
  workbench; the session starts from that prepared set.

R1 shipped. This document is R2.

---

## 2. What exists today

| Piece | Where | State |
|---|---|---|
| Template | `scenario` table: `key`, `name`, `definition jsonb`, `version` | One row, `standard-v1`, written by migration V2/V6. **No API, no UI.** |
| Template content | `Scenario`: `params` (rounds, minutes, starting cash/brand, fixed cost, salaries, elasticities…), `products`, `channels`, `projects`, `rounds`, `events` | Read by `ScenarioRepository.load(key)` |
| Company | `team` row: `name`, `company_name` + a product line created from the scenario at run creation | No reusable "company profile" |
| Facilitator | `app_user` with role `FACILITATOR` | Created by bootstrap env vars or an admin |
| Trainee | `app_user` with role `PLAYER` + a `player` seat | Created inside the console per company, per run |
| Session | `run` (+`round`, `team`, `decision`, `round_result`, `round_statement`) | Created from `"standard-v1"`, hardcoded in the console |

So the *data* for templates already exists — it is a jsonb document plus side tables. What does not
exist is any way to build one inside the product, or to prepare people before a session starts.

---

## 3. Gaps, precisely

1. **Templates are code.** Adding a scenario means a Flyway migration. A facilitator cannot copy,
   adjust or name one.
2. **No ownership.** `scenario.key` is global; a tenant cannot have its own variant without inventing
   a key in SQL.
3. **Companies are anonymous.** `team` is a name on a run. There is no reusable profile (product mix,
   starting position) to pick when setting up a cohort.
4. **Accounts and sessions are entangled.** A trainee account only exists because a run was created,
   and — after R1 — it dies with that run. That is right for rehearsal data, wrong for a school that
   runs the same trainees through several sessions in a term.
5. **No preparation screen.** Everything is done from the console, after the run exists.

---

## 4. Target flow

```
SETUP (before the session, any time)          SESSION (the class day)         AFTER
──────────────────────────────────────        ──────────────────────────      ─────────────
template: environment + companies + settings → pick template, pick companies,  debrief
facilitator account                           pick the prepared trainees,
trainee roster (names + emails)               start  → seats are filled
```

The workbench is where a facilitator prepares; the console stays the place where a session is driven.

---

## 5. Data model changes

**Shipped (V9, 3 Nov 2026):**

1. **`scenario` is tenant-extensible.** `tenant_id` (null = the shipped global template),
   `status` (`DRAFT` | `PUBLISHED`), `based_on` (provenance). `key` stays the **primary key** rather
   than becoming `(tenant_id, key)`: every catalogue table references `scenario (key)`, and widening
   the key would rewrite five foreign keys for no gain. A tenant copy gets its own unique key,
   namespaced by tenant code (`demo-standard-v1`, then `demo-standard-v1-2`).
2. **A copy is complete** — the definition document plus `product`, `channel`, `rnd_project`,
   `scenario_round`, `environment_event`. A copy that missed a catalogue table would load with no
   products and only fail at the first round, in a classroom.

**Still to design — not written, and not needed before S6e:**

3. **`company_profile`** (global base + tenant copies, same pattern as templates): `name`,
   `product_line`, `starting position`, `notes`. A run's `team` rows would be created from profiles.
4. **Roster** — trainee accounts that exist before a session: `app_user.status`
   (`INVITED` | `ACTIVE` | `DISABLED`). The `player` (seat) stays per-run: **the person is
   tenant-level, the seat is session-level** — which is what deleting a run already enforces.
5. **`run.template_version`** so a session records which template it was played on even after the
   template is edited (same reasoning as D18's frozen economy).

---

## 6. Screens

| Screen | Purpose | Reads / writes |
|---|---|---|
| **Setup → Templates** | list templates (name, rounds, cycles, products, status); *Duplicate*; *Publish* | `scenario` |
| **Setup → Template editor** | tabs: *Environment* (per-round demand/cost/wage indices, trend labels, event deck), *Products* (price, unit cost, quality, demand, units per worker, elasticities), *Channels*, *R&D projects*, *Settings* (rounds, minutes, starting cash/brand, fixed cost, salaries, report cost, machine cost) | `scenario.definition` + side tables |
| **Setup → Companies** | company profiles: name + product line, reusable across cohorts | `company_profile` |
| **Setup → People** | facilitator accounts; trainee roster (name, email, generated credential, status) | `app_user` |
| **Console → Start session** | pick template + companies + trainees → creates the run and fills the seats | `run`, `team`, `player` |

The Trainees card that used to sit under the run screen was removed (D33, ruling 4 Nov 2026): the
facilitator's page holds rounds, scoreboard, debrief and live events only, and a seat left open in a
package stays open.

---

## 7. Phases (each independently shippable and testable)

- **S6a — Template library, read-only. SHIPPED.** `GET /api/templates`, Setup → Templates page listing
  `standard-v1` (owner, status, products, rounds, provenance).
- **S6b — Duplicate a template. SHIPPED.** `POST /api/templates/{key}/copy` → tenant-owned `DRAFT`
  with a tenant-namespaced key; V9 adds `tenant_id`/`status`/`based_on`; `TemplateApiTest` proves the
  copy is complete and the global template is untouched.
- ~~**S6a — Template library, read-only.**~~ `GET /api/templates` + a Setup page listing
  `standard-v1` (name, rounds, cycles, products, channels, projects, rounds table). Proves the
  document can be surfaced before anything can be edited. No schema change.
- **S6c — Edit settings and environment. SHIPPED.** `GET /api/templates/{key}` returns the editable
  settings plus the environment timeline; `PUT .../settings` **merges** the submitted scalars into the
  definition document (elasticity and baseline calibration are never dropped, unknown keys ignored)
  and bumps `version`; `PUT .../environment` replaces the timeline whole, because positions are a
  table and a partial edit has no meaning. Only a template the tenant owns is editable — the shipped
  one answers `409 template.read_only`. Validation returns codes (`template.settings_invalid`,
  `template.environment_invalid`) with the offending field. Console: clicking a template opens the
  editor with **Settings**, **Environment**, **Events**, **Products**, **Channels** and **R&D** tabs.
- **S6d — Products, channels, R&D projects. SHIPPED.** `PUT .../products`, `/channels`, `/projects`
  replace each catalogue whole (a catalogue is a set). Validation refuses what the engine cannot run:
  at least one row, unique codes, positive price/cost/reach/size, `advantageType` limited to the four
  branches in `ScoringEngine` (`UNIT_COST_DOWN`, `CAPACITY_UP`, `QUALITY_UP`, `ATTRACTIVENESS_UP`),
  `benefitCurve` to `LINEAR|POWER|CHANCE`, `overfunding` to `REFUND|LOST`, `durationMonths >= 1`, and
  a project's `targetProduct` must exist. **A template that already has a simulation refuses catalogue
  edits** (`409 template.in_use`): its companies were created from these codes and its decisions name
  them. Settings and the environment stay editable, because each run freezes its own economy (D18).
- **S6f — Every editable parameter, exposed. SHIPPED.** The settings form carries all 22 definition
  keys, including the `salaries` and `startingStaff` maps (per function: production, sales, R&D,
  admin) and `minRndBenefitRatio` / `cycleDemandDrift` / `cycleCostDrift`; a new **Events** tab edits
  the event deck (code, name, description, round window, chance, duration, demand/cost/wage
  multipliers, target product), saved whole and validated at the edge (`template.events_invalid`).
  Only engine calibration stays out of the form (D26).
- **S6e — Company profiles and start-from-prepared. SHIPPED.** V11 adds `company_profile`
  (global base + tenant copies, same pattern as templates). `GET/POST/PUT /api/companies` and
  `POST /api/companies/{id}/copy`; `POST /api/runs/prepared` takes a template, profile ids and
  trainee ids, validates every piece **before writing anything**, creates the run with a company per
  profile, narrows each company to its profile's product line, and seats the trainees in order.
  The console gained a **Companies** tab and a **Start a session** card that replaces the empty
  state, so a session is no longer assembled by typing company names.

S6a and S6b are shipped; S6c is next (the settings and environment editor), then S6d, then S6e.

---

## 7a. Who does what (D24)

| Role | Job | Sees |
|---|---|---|
| `BUILDER` | prepares templates, companies and **packages** | Packages · Templates · Companies · People |
| `FACILITATOR` | runs sessions from published packages | Sessions · People |
| `ADMIN` | both jobs | everything |
| `PLAYER` | competes | the decision form for their company |

The boundary artefact is a **package**: `simulation_package` (V12) records one template *version*, the
company profiles in it, and who published it. `POST /api/packages` (builder) → `POST
/api/packages/{id}/sessions` (facilitator). A package is retired, never deleted; `run.package_id`
records which package a cohort came from.

## 7b. Lifecycle after publishing

`GET /api/packages/tidy-up` lists retired packages that never ran a session; `POST` removes them and
their frozen template copies. A package that ran a class is never offered — `run.package_id` is the
guard. The console shows the list with a confirmation before deleting anything.

## 8. Open questions for the product owner

0. **How tightly does a package bind to its template? — answered 3 Nov 2026 (option A: freeze on
   publish).** `POST /api/packages` clones the draft into a private immutable row
   (`scenario.frozen_for_package`), and the package's `template_key` is that frozen copy; execution
   loads it, so a later edit to the draft cannot reach a class. Re-publishing an edited draft makes a
   **new** package on a **new** frozen copy; the old package retires. Frozen rows are hidden from the
   template library and refuse edits. (Option B — a `scenario_version` table — remains the path if
   templates ever need real version history.)

1. **Template ownership — answered 3 Nov 2026.** `standard-v1` stays global; a tenant copies it and
   modifies its own copy if needed. Shipped as S6b.
2. **Company profiles — answered 3 Nov 2026.** One global, reusable profile that can be extended; it
   is reusable across simulations, not defined per session. Designed in §5 (item 3), not yet built.
3. **Trainee identity — answered 3 Nov 2026.** Personal: one account per person, re-seated per
   session. Deleting a session removes the seat and any account that exists only for it, and keeps a
   person who is seated in another session (already enforced and tested).
4. **Class list on a published package — deferred 3 Nov 2026 ("keep your suggestion in mind, we keep
   the current arrangement first").** *Current arrangement, in force:* the class list is part of the
   frozen package (D27). To change who sits where before a session you either publish a new package,
   or pass an explicit `traineeIds` on the start request (which overrides the package without
   touching it); after the session starts nothing fills or replaces a seat (D33). *The
   suggestion, held for later:* split the seat table out of the freeze — seats editable on a
   published package, template + companies still frozen. That would let a builder fix a roster
   without minting a new package version, at the cost of one more "what did this package mean when it
   ran?" distinction: the seating a class actually played would no longer be implied by the package
   row, so the run would have to record its own seating snapshot (it already does, via `player`) and
   a debrief would have to read that, not the package. Not built; revisit if rostering churn becomes
   routine.

5. **Credential delivery.** Printed list, CSV export, or paste-into-WhatsApp from the roster screen?
   There is no email delivery in this product and adding it is its own decision.

Answers to 1–3 change the migrations; 4 is a screen detail.
