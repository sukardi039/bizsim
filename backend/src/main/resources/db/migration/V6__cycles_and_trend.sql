-- V6: cycles are a setup parameter, and the forecast carries a consumer trend (D19).
--
-- Two changes:
--   1. the economy timeline is generated per run from a *cycle template*, so a facilitator chooses
--      how many cycles a session covers and how long each one is, and can add rounds if the cohort
--      needs more time;
--   2. a consumer-trend signal rides alongside the economic indices. It is one number per round plus
--      one affinity per product, so it tilts demand between segments without anyone forecasting per
--      product.

-- How many rounds and cycles this particular run covers. Null means "take it from the scenario".
alter table run add column rounds integer;
alter table run add column cycles integer;

-- The generated economy for this run. Frozen at creation like the event draw, so a replay is exact.
create table run_round_economy (
    run_id         bigint       not null references run (id) on delete cascade,
    round_number   integer      not null check (round_number between 1 and 48),
    demand_index   numeric(6, 3) not null check (demand_index > 0),
    cost_index     numeric(6, 3) not null default 1.000 check (cost_index > 0),
    wage_index     numeric(6, 3) not null default 1.000 check (wage_index > 0),
    trend_strength numeric(4, 2) not null default 0.00 check (trend_strength between -1 and 1),
    trend_label    varchar(60),
    primary key (run_id, round_number)
);

-- Positive strength means consumers trade up; the affinity says which way a product leans.
--   multiplier for j = 1 + trend_strength x trend_affinity_j
alter table product add column trend_affinity numeric(4, 2) not null default 0.00
    check (trend_affinity between -1 and 1);
alter table scenario_round add column trend_strength numeric(4, 2) not null default 0.00
    check (trend_strength between -1 and 1);
alter table scenario_round add column trend_label varchar(60);

-- The scenario now describes cycles rather than an absolute run length.
update scenario
set definition = definition
    || '{
         "cycles": 2,
         "cycleLength": 4,
         "cycleDemandDrift": 0.02,
         "cycleCostDrift": 0.01
       }'::jsonb
where key = 'standard-v1';

-- One cycle of four positions. Two cycles of it is the default run; three is one update.
-- Round 1 stays neutral (1.000) so the calibrated round-1 table remains valid.
delete from scenario_round where scenario_key = 'standard-v1';

insert into scenario_round (scenario_key, round_number, demand_index, cost_index, wage_index,
                            trend_strength, trend_label, note)
values ('standard-v1', 1, 1.000, 1.000, 1.000,  0.00, 'Stable',            'Start of cycle'),
       ('standard-v1', 2, 1.050, 1.030, 1.010,  0.40, 'Consumers trading up',   'Peak'),
       ('standard-v1', 3, 0.920, 1.070, 1.030, -0.40, 'Consumers trading down', 'Recession'),
       ('standard-v1', 4, 0.960, 1.050, 1.030, -0.20, 'Cautious',          'Recovery');

update product set trend_affinity = -0.50 where scenario_key = 'standard-v1' and code = 'P1';
update product set trend_affinity =  0.00 where scenario_key = 'standard-v1' and code = 'P2';
update product set trend_affinity =  0.50 where scenario_key = 'standard-v1' and code = 'P3';

-- Existing runs predate the generated timeline; they keep working through the scenario fallback.
