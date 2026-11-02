# Migrations — rules and runbook

Flyway is not hard to *use*. It is hard to use when the team has no rules, because it remembers what
it applied and treats any divergence as a hard failure. That is the feature: a deploy can never
silently reshape a table that holds a class's finished rounds. Everything below exists to keep the
failure modes boring.

Schema lives in `backend/src/main/resources/db/migration`, is applied on every startup, and is
recorded in `flyway_schema_history`. There are four migrations today: V1 baseline, V2 scenario
placeholder, V3 simulation model, V4 R&D curves.

---

## 1. The five rules

1. **Never edit a migration that has been applied anywhere.** Flyway checksums each file; an edit
   produces `Migration checksum mismatch` and the app refuses to start. Change the schema with a new
   migration instead — even to fix a typo in a comment.
2. **One concern per migration, named `V<n>__verb_noun.sql`** (`V5__add_company_notes.sql`). A
   migration that adds a table and rewrites data cannot be reasoned about when it fails.
3. **Never renumber after merge.** If two branches both created V5, the later pull request renumbers
   its own file before merging. Renumbering an applied file is rule 1 with extra steps.
4. **Destructive statements need `-- migration:allow-destructive`** in the file, plus a reviewer who
   read it. Dropping a table is sometimes right; doing it quietly never is. A test enforces this.
5. **Forward-only.** Rollback is a new forwarding migration that reverses the effect. There is no
   undo in Flyway Community, and restoring a backup is not a rollback plan for a live class.

---

## 2. Failure → cause → fix

| What you see at startup | What happened | What to do |
|---|---|---|
| `Migration checksum mismatch for migration version 3` | Someone edited an applied file | Restore the file (`git checkout -- <file>`), then add a new migration for the change. `flyway:repair` rewrites the recorded checksum and can hide a real schema difference — use it only when you are certain the file is the correct one and the database is the one that is stale. |
| `Found more than one migration with version 5` | Two branches added the same number | Renumber the newer file in the later PR, do not reorder history |
| `Detected resolved migration not applied to database: 3.1` | A hotfix numbered below the current version | `out-of-order` is off by design. Release the hotfix as a *new higher* version; the database should never be told to walk backwards |
| `Detected failed migration to version 4` | Only possible on databases without transactional DDL | Not us: PostgreSQL rolls DDL back, so a failed migration leaves nothing. If this ever appears, run `flyway:repair` to clear the row, fix the SQL, and redeploy |
| `Schema "public" is not empty` | Attaching bizsim to a database that already has tables | Compare the schema by hand, then baseline deliberately: `mvn flyway:baseline -Dflyway.baselineVersion=<n>` |
| Startup hangs for minutes | A migration is doing real work | Look at `pg_stat_activity`. Data backfills do not belong in a startup migration; ship them as a separate, idempotent job |
| Startup fails, database unreachable | Database not up yet | `connect-retries: 10` at 5s covers a normal container start; beyond that it is a real outage |
| Five instances start at once | No problem | Flyway takes an advisory lock; the others wait (`lock-retry-count: 60`) |

---

## 3. Commands

The Maven plugin is pinned to the same Flyway version the application runs, so `info` from your
laptop and `migrate` in production give the same answer.

```bash
cd backend
export BIZSIM_DB_URL=<url> BIZSIM_DB_USER=<user>   # plus the credential variable
mvn flyway:info        # what is applied, what is pending, what is pending-and-not-resolved
mvn flyway:validate    # checksums only; run this before every release
mvn flyway:repair      # clears failed rows and fixes checksums — never changes the schema
mvn flyway:baseline    # attaches an existing database; needs -Dflyway.baselineVersion=<n>
```

Anything not covered: `-Dflyway.url=... -Dflyway.user=...` overrides the environment.

There is deliberately **no `flyway:clean`** in this project: `clean-disabled: true` makes it fail
rather than drop every table. Dropping a schema is done by a human on a database they have named out
loud.

---

## 4. What we do not do

| Not used | Why |
|---|---|
| `spring.jpa.hibernate.ddl-auto` | Cannot be reviewed, cannot express data changes, and silently alters populated tables. We have no JPA entities either |
| `flyway.clean` on any database with data | It deletes everything; there is no undo |
| Editing an applied migration | Checksum failure, and a lie about what production ran |
| One migration per release | Impossible to bisect when one statement fails |
| Manual `psql` in production | The schema stops matching the folder, and the next deploy fails |
| Test-only schema (`H2`, in-memory) | `jsonb`, identity columns and ordering would behave differently; tests run real PostgreSQL |

---

## 5. What the tests enforce

Run by CI on every pull request (`mvn -B verify`), not by review discipline:

- **Naming and contiguity** — versions must be 1..n with no gap or duplicate, and files must match
  `V<n>__lower_snake_case.sql` (`MigrationNamingTest`).
- **Destructive statements need an allowance marker** (`MigrationNamingTest`).
- **A second startup applies nothing** — an existing database is never touched
  (`MigrationSafetyTest`).
- **A broken migration rolls back completely** — table gone, no history row, previous schema still
  valid (`MigrationSafetyTest`, using a deliberately broken fixture in `src/test/resources`).
- **An empty database gets the whole schema** on startup, including the shipped scenario
  (`SchemaCreationTest`).

---

## 6. Adding a migration

1. `git pull` — take the highest existing number, add one. Re-check right before merging; a rebase
   that brings in someone else's V5 means you renumber.
2. Write the SQL for PostgreSQL. Prefer additive changes; never `drop table` without the marker.
3. Run `mvn test` — the naming, rollback and schema tests all execute.
4. Run `mvn flyway:validate` against staging before the release.
5. Update `docs/DECISIONS.md` if the change encodes a product ruling, in the same commit.
