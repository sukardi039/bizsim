-- V44: the facilitator can put a chart on the class's screens (D117).
--
-- Until now the only way a chart reached a trainee was a link the facilitator pasted into the room: the
-- frozen page (D103) is a record for phones and absentees, and the projector is the facilitator's own
-- screen. The class table (D52) already showed the pattern that actually works in a room — a per-run
-- control the facilitator flips, which the trainee console polls — and this table is its sibling.
--
-- **Why a table and not a column on `run`.** A show has a lifecycle (which chart, which round, who opened
-- it) that a class will ask about in the audit trail, and a column cannot carry it. One row per run while
-- a show is open; closing deletes the row, so "is anything being shown" is a single indexed lookup rather
-- than a nullable date to interpret.
--
-- **kind** is a code, not a sentence: `MARKET_PIES` today, and a second chart later without a migration.

create table run_show (
    run_id       bigint      primary key references run (id) on delete cascade,
    kind         varchar(20) not null check (kind in ('MARKET_PIES')),
    round_number integer     not null,
    opened_by    bigint      references app_user (id),
    opened_at    timestamptz not null default now()
);

comment on table run_show is
    'D117: the chart the facilitator is showing on the class''s own screens right now; no row means nothing is shown';

-- A show is a session control, so it belongs in the audit trail the class can be shown afterwards. The
-- check constraint enumerates the actions (V21, V24), so the two new ones are added here rather than
-- discovered at runtime as a 409 on a facilitator's button press -- which is exactly how this was found.
alter table facilitator_action drop constraint if exists facilitator_action_action_check;
alter table facilitator_action add constraint facilitator_action_action_check
    check (action in ('CLOCK_PAUSE', 'CLOCK_RESUME', 'CLOCK_EXTEND', 'CASH_ADJUST', 'ROUND_VOID',
                      'STANDINGS_SHOW', 'STANDINGS_HIDE', 'RUN_RESET', 'SHOW_OPEN', 'SHOW_CLOSE'));
