# Setup workbench — the pre-session build

Status: **design, not built.** This file is the agreed scope for the next phase, so the slices can be
cut without re-litigating the shape.

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

## 5. Data model changes (proposed)

1. **`scenario` becomes a tenant-owned template**
   - `tenant_id bigint` (nullable for the shipped `standard-v1`, which stays global and read-only),
   - `status varchar` — `DRAFT` | `PUBLISHED`,
   - `based_on varchar` — the template it was cloned from, for provenance,
   - key becomes `(tenant_id, key)` unique instead of a global primary key.
2. **`company_profile`** (new, tenant-owned): `id`, `tenant_id`, `name`, `product_line` (which products
   from the template the company carries), `notes`. A run's `team` rows are created *from* profiles,
   so the same company setup can be reused for the next cohort.
3. **Roster** — trainee accounts that exist before a session:
   - `app_user` gains `status` (`INVITED` | `ACTIVE` | `DISABLED`) so a prepared account is visibly
     "not yet in a session";
   - `player` (the seat) stays per-run, as today: **the person is tenant-level, the seat is
     session-level.** This is what R1 already enforces when deleting a run.
4. **`run` gains `template_version`** so a session records exactly which template it was played on,
   even if the template is edited later (the same reasoning as D18's frozen economy).

No migration is written until the phases below are agreed — three of these change primary keys and
deserve their own migration with its own test.

---

## 6. Screens

| Screen | Purpose | Reads / writes |
|---|---|---|
| **Setup → Templates** | list templates (name, rounds, cycles, products, status); *Duplicate*; *Publish* | `scenario` |
| **Setup → Template editor** | tabs: *Environment* (per-round demand/cost/wage indices, trend labels, event deck), *Products* (price, unit cost, quality, demand, units per worker, elasticities), *Channels*, *R&D projects*, *Settings* (rounds, minutes, starting cash/brand, fixed cost, salaries, report cost, machine cost) | `scenario.definition` + side tables |
| **Setup → Companies** | company profiles: name + product line, reusable across cohorts | `company_profile` |
| **Setup → People** | facilitator accounts; trainee roster (name, email, generated credential, status) | `app_user` |
| **Console → Start session** | pick template + companies + trainees → creates the run and fills the seats | `run`, `team`, `player` |

The existing Trainees card survives as the *in-session* fallback (walk-in trainee), because a real
class always has one person who is not in the roster.

---

## 7. Phases (each independently shippable and testable)

- **S6a — Template library, read-only.** `GET /api/templates` + a Setup page listing
  `standard-v1` (name, rounds, cycles, products, channels, projects, rounds table). Proves the
  document can be surfaced before anything can be edited. No schema change.
- **S6b — Duplicate a template.** `POST /api/templates/{key}/copy` → a tenant-owned `DRAFT` copy.
  First schema change (`tenant_id`, `status`, `based_on`), with a migration and a test that the
  global template is never mutated.
- **S6c — Edit settings, then environment.** `PUT /api/templates/{id}/settings` and
  `/environment`, with the same validation the engine relies on (`rounds ≥ 1`, indices > 0, at least
  one product). Save-from-UI, reload, and a run created from the edited template shows the new numbers.
- **S6d — Products, channels, R&D projects.** The remaining editor tabs.
- **S6e — People and roster, then start-from-roster.** Roster accounts (`INVITED`), company profiles,
  and a *Start session* action that creates the run and seats the roster. Retires the hardcoded
  `"standard-v1"` in the console.

S6a and S6b are the ones that unblock everything else and carry the least risk; S6c–S6e are the bulk.

---

## 8. Open questions for the product owner

1. **Template ownership.** Should every tenant be able to create templates (S6b as described), or is
   a template always global and only *adjusted* per run? The first needs `tenant_id` on `scenario`.
2. **Company profiles.** Is a company a *reusable* profile (same product mix next term) or always
   defined per session? Reusable costs one table but removes retyping every cohort.
3. **Trainee identity across sessions.** Same trainee in three sessions this term: one person-level
   account re-seated each time, or a fresh account per session (today's behaviour, and what R1
   assumes)? This decides whether `app_user` needs a status and whether deleting a run may ever
   remove someone who is also in another session — today it keeps them.
4. **Credential delivery.** Printed list, CSV export, or paste-into-WhatsApp from the roster screen?
   There is no email delivery in this product and adding it is its own decision.

Answers to 1–3 change the migrations; 4 is a screen detail.
