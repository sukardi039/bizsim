-- V46: a bought market review is paid for the round, and reading it makes the purchase stick (D124).
--
-- The purchase travels with the submitted plan, so a trainee could read the forecast, edit the plan and
-- submit again with the box cleared — and the round would no longer be billed for a report they had already
-- seen. The fix is a record of the *purchase*, separate from the plan that carried it: once a round has been
-- bought, every later submission of that round bills it again and the console cannot untick it.
--
-- A separate table rather than a flag on `decision`, because the plan is replaced on every re-submit
-- (`on conflict ... do update set payload`) — a flag living inside the payload is exactly what got lost.

create table report_purchase (
    round_id  bigint      not null references round (id) on delete cascade,
    team_id   bigint      not null references team (id) on delete cascade,
    bought_at timestamptz not null default now(),
    primary key (round_id, team_id)
);

comment on table report_purchase is
    'D124: a market review bought for a round. Sticky: re-submitting the plan cannot withdraw it.';
