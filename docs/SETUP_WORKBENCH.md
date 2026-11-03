# Setup workbench — the pre-session build

Status: **S6a and S6b shipped (3 Nov 2026); S6c–S6e designed.** This file is the agreed scope for the
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

The existing Trainees card survives as the *in-session* fallback (walk-in trainee), because a real
class always has one person who is not in the roster.

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
- **S6c — Edit settings, then environment.** `PUT /api/templates/{id}/settings` and
  `/environment`, with the same validation the engine relies on (`rounds ≥ 1`, indices > 0, at least
  one product). Save-from-UI, reload, and a run created from the edited template shows the new numbers.
- **S6d — Products, channels, R&D projects.** The remaining editor tabs.
- **S6e — People and roster, then start-from-roster.** Roster accounts (`INVITED`), company profiles,
  and a *Start session* action that creates the run and seats the roster. Retires the hardcoded
  `"standard-v1"` in the console.

S6a and S6b are shipped; S6c is next (the settings and environment editor), then S6d, then S6e.

---

## 8. Open questions for the product owner

1. **Template ownership — answered 3 Nov 2026.** `standard-v1` stays global; a tenant copies it and
   modifies its own copy if needed. Shipped as S6b.
2. **Company profiles — answered 3 Nov 2026.** One global, reusable profile that can be extended; it
   is reusable across simulations, not defined per session. Designed in §5 (item 3), not yet built.
3. **Trainee identity — answered 3 Nov 2026.** Personal: one account per person, re-seated per
   session. Deleting a session removes the seat and any account that exists only for it, and keeps a
   person who is seated in another session (already enforced and tested).
4. **Credential delivery.** Printed list, CSV export, or paste-into-WhatsApp from the roster screen?
   There is no email delivery in this product and adding it is its own decision.

Answers to 1–3 change the migrations; 4 is a screen detail.
