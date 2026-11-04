-- The debrief has to show where the money came from (ruling 4 Nov 2026): the cash balance a round
-- opened with, so a class can see opening -> closing across rounds.
--
-- It is recorded rather than derived, for two reasons: round 1 has no previous statement to read, and
-- cash does not move by profit alone -- building stock and buying capacity move cash without touching
-- profit. `opening_cash` is the balance the engine actually started from, so
-- `opening_cash + cash_change = cash_after` holds for every round by construction.

alter table round_statement add column opening_cash numeric not null default 0;

comment on column round_statement.opening_cash is
    'Cash the company held before the round was computed; cash_after - opening_cash is the cash movement';
