-- Standings as a class moment (D52): the facilitator decides when the class may see the table.
--
-- Hidden by default. A cohort that never reveals it sees nothing, which keeps the first round's
-- teaching on the company's own numbers rather than on the ranking; reveal it and every later round
-- reads it like a scoreboard. The choice is a session setting, not a template one: it is about how a
-- particular class is run.

alter table run add column if not exists standings_visible boolean not null default false;

comment on column run.standings_visible is
    'D52: whether trainees may read /runs/{id}/standings. Staff always can.';

-- The same table records the moment, so a class can ask "when did they see the ranking?".
alter table facilitator_action drop constraint if exists facilitator_action_action_check;

alter table facilitator_action add constraint facilitator_action_action_check
    check (action in ('CLOCK_PAUSE', 'CLOCK_RESUME', 'CLOCK_EXTEND', 'CASH_ADJUST', 'ROUND_VOID',
                      'STANDINGS_SHOW', 'STANDINGS_HIDE'));
