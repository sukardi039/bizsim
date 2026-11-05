# Auto workers

A company in a bizsim class can be played by a policy instead of a person. This document is the
design of record for D47–D50; the rulings themselves are in [DECISIONS.md](DECISIONS.md).

**Why:** a class of four is a thin market — one timid team can flatten the competitive pressure the
simulation is supposed to create. A reusable opponent restores it, and gives a cohort something to
measure itself against that is neither a classmate nor a fixed benchmark.

**What it is not:** a second engine. An auto worker submits through the same path as a trainee and
its month is computed by the same `ScoringEngine`. If the two ever disagree, that is a bug.

---

## The three parts

| Part | Where | What it means |
|---|---|---|
| **Identity** | `app_user` with role `AUTO_WORKER` | The thing that signs a decision. Seats like a person, can never log in. |
| **Brain** | `bot_profile` | Level × personality + numeric knobs. Owned by a builder, versioned. |
| **Seat** | `player.bot_profile_id` | Non-null = this company is auto-played. Moving it to a person is a data change. |

Separating them is what makes the feature flexible: one brain can play in five classes at once, each
company keeps its own identity and trace, and a brain can be improved without touching a seat.

### Naming

The stored name ends with `_low`, `_mid` or `_high` — that is what a trainer reads on a scoreboard.
The suffix is **generated from the stored level**, not parsed on every use: `Market 1` + `HIGH` is
stored as `Market 1_high`. If someone types a suffix it wins at creation (`Market 2_low` with
`level: HIGH` is stored as `LOW`, loudly, in the response), but nothing at runtime parses the name.
Renaming therefore cannot swap the brain.

### Level and personality

**Level is how well it plays. Personality is what it wants.** They are different axes, and the matrix
of nine is what makes a rematch interesting.

| | Cautious | Balanced | Aggressive |
|---|---|---|---|
| **High** | careful, forecast-led | the reference opponent | price-led, share-hungry |
| **Mid** | conservative, profitable | solid, occasionally wrong | pushes volume |
| **Low** | timid, near break-even | naive | cheap prices, thin margins |

Shipped knobs (`AutoWorker.profile`), all overridable per profile row:

| Knob | LOW | MID | HIGH | What it does |
|---|---|---|---|---|
| `forecastBlend` | 0.35 | 0.70 | 1.00 | how far it trusts the published demand index |
| `demandNoise` | 0.28 | 0.14 | 0.04 | seeded error on its own forecast (±) |
| `marketingRate` | 0.8% | 2.0% | 3.5% | spend as a share of expected revenue |
| `targetShare` | entry position only | | | round-1 production anchor |
| buys market review | no | yes | yes | pays the fee like a trainee |
| plans R&D | no | no | yes | paces against the **hint band** |
| buys quality | no | no | yes | only when the template sells it |
| `cashBufferMonths` | 2 | 2 | 2 | discretionary spend needs this many months of fixed cost in hand |

---

## Rules of engagement

1. **Same submission path.** `RoundService.submit(...)` — same clock, same validator, same
   affordability check, same `decision` row, `submitted_by` = the bot's person.
2. **Same information.** Own statements, published indices, public catalogue. Never another
   company's decision, the event deck, or a project's hidden cost (it uses the hint band).
3. **Seated in the package**, frozen with it (D24/D27). No opponent appears mid-session.
4. **Visible, not viewable.** Trainees see a company labelled `_high`/`_mid`/`_low` and nothing else:
   no statement, no submission, no rationale, no share.
5. **A company like any other** for the facilitator: pause, extend, cash adjust, void — all apply,
   all audited in `facilitator_action`.
6. **A missed month is a missed month.** If a bot never submits, the round computes it like any
   company without a decision. There is no privileged rescue: a bot that gets free months would be
   advantaged, which is the one thing the design cannot allow.

---

## Traceability

| Level | Stored | Answers |
|---|---|---|
| Identity | `decision.submitted_by`, `player.bot_profile_id` | which company, which brain |
| Provenance | `bot_decision`: `profile_key`, `profile_version`, `params` (snapshot), `params_digest`, `seed_key`, `context_digest`, `rationale`, `mode`, `decided_at` | which brain version, seeded how, what it saw, what it chose, why |
| Reproduction | replay from `context_digest` + `params` snapshot | "would it do the same again?" — the answer a class asks out loud |

`mode` is `SCHEDULED` (the worker submitted during the round), `MANUAL` (a facilitator asked it to)
or `NONE` (the round closed with no decision).

The **rationale** is one line, stored with the decision and shown to staff in the submissions view —
e.g. `P1 800 @1m · staff 30 · marketing 3m · no R&D`. It is the difference between an opponent and
a black box.

---

## Leaving a class honest

- **Never stronger than allowed:** the policy is deterministic arithmetic over public information.
  No learning, no LLM, no tokens, no latency.
- **Never a trick:** the company is labelled. A hidden opponent reads as cheating when discovered.
- **Never a surprise in the numbers:** seated in the package, so the market is fixed before round 1.

---

## Slices

| # | Slice | State |
|---|---|---|
| 1 | V20: role, `bot_profile`, `bot_decision`, `player.bot_profile_id`, `Params.autoWorkers` | done |
| 2 | `AutoWorker` policy: nine profiles, seeded, rationale | done |
| 3 | Self-play harness: level ordering + determinism + calibration table | done |
| 4 | Builder UI: auto-worker library, seat a bot in a package | done — *Auto workers* tab; the package class list offers `Market 1_high · auto` beside the people |
| 5 | Worker: submit during the round, trace API + replay | done — `BotRoundService` (5s sweep, seeded 20–70% of the round), `GET /runs/{id}/bots`, `POST /runs/{id}/bots/decide`, `POST /runs/{id}/bots/{team}/replay` |
| 6 | User guide section | done — guide §3.5 and §7.2.2 |

Nothing seats a bot unless a template turns `autoWorkers` on **and** a package's class list names a
brain: two deliberate switches, so the feature is inert by construction rather than by discipline.

### Running a class with an opponent

1. Builder → **Auto workers** → create `Market 1`, level *High*, personality *Aggressive*. The stored
   name becomes `Market 1_high`.
2. Templates → turn **Offer auto workers to the class** on in the template's settings (D48).
3. Packages → pick that template and the companies → the class list now offers
   `Market 1_high · auto` beside the trainees → publish.
4. Facilitator → start the session from the package and open round 1. The opponent submits on its own
   a little way into the round; **Auto workers this session** shows what it decided, the brain version,
   the reason, and a **Replay** that must match.
5. Want it to move sooner? **Ask them to move now** — recorded as `MANUAL`, so the class log never
   claims it was scheduled when it was not.

---

## Calibration (self-play, 8 rounds, 6 companies, shipped template)

```
high 123,194,646 | mid 102,582,375 | low 894,000   (average profit per round)
```

The ordering holds, nobody is insolvent, and replaying the seed reproduces the numbers exactly. The
low level sits near break-even: it is meant to be beatable, not to be a victim.
