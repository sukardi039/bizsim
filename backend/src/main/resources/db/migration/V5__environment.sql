-- V5: the environment model (ruling D18).
--
-- Market size is not constant: each round carries a small set of economic indices, and each product
-- reacts to them differently because its customer group reacts differently. That is the whole point
-- of the feature, so sensitivity lives on the product and the economy lives in its own table.
--
-- The indices multiply, they do not replace, the base demand and unit costs:
--   market size for product j in round r = base_demand_j x seasonality x cohort scale
--                                          x demand_index(r) ^ income_elasticity_j
--                                          x event multipliers active for j
--   unit material cost                   = base cost x cost_index(r) x active cost events
--   payroll                              = salary x headcount x wage_index(r) x active wage events

alter table product add column income_elasticity numeric(5, 2) not null default 1.00
    check (income_elasticity >= 0);

-- The economics timeline: one row per round. Missing rounds fall back to 1.0, so a short table is
-- legal and a long run does not break.
create table scenario_round (
    scenario_key  varchar(60)  not null references scenario (key) on delete cascade,
    round_number  integer      not null check (round_number between 1 and 24),
    demand_index  numeric(6, 3) not null default 1.000 check (demand_index > 0),
    cost_index    numeric(6, 3) not null default 1.000 check (cost_index > 0),
    wage_index    numeric(6, 3) not null default 1.000 check (wage_index > 0),
    note          varchar(200),
    primary key (scenario_key, round_number)
);

-- The event deck. A deck entry is not a schedule: whether it fires, and when inside its window, is
-- drawn once per run from a seed, so the event is unpredictable to trainees but fixed and replayable
-- for the facilitator (D18, and D12: nothing is authored mid-run).
create table environment_event (
    scenario_key      varchar(60)  not null references scenario (key) on delete cascade,
    code              varchar(40)  not null,
    name              varchar(160) not null,
    description       varchar(400) not null,
    earliest_round    integer      not null check (earliest_round >= 1),
    latest_round      integer      not null,
    probability       numeric(4, 2) not null check (probability >= 0 and probability <= 1),
    duration_months   integer      not null check (duration_months between 1 and 12),
    demand_multiplier numeric(6, 3) not null default 1.000 check (demand_multiplier > 0),
    cost_multiplier   numeric(6, 3) not null default 1.000 check (cost_multiplier > 0),
    wage_multiplier   numeric(6, 3) not null default 1.000 check (wage_multiplier > 0),
    target_product    varchar(20),                       -- null = the whole market
    primary key (scenario_key, code),
    check (latest_round >= earliest_round)
);

-- The draw, frozen at run creation. Trainees never see this table; the debrief does.
create table run_environment_event (
    run_id          bigint      not null references run (id) on delete cascade,
    event_code      varchar(40) not null,
    starts_round    integer     not null,
    duration_months integer     not null,
    primary key (run_id, event_code)
);

-- The paid market review is a real cost line, so it gets a real column rather than being folded
-- into marketing (which would silently boost brand).
alter table round_statement add column report_cost numeric(16, 2) not null default 0;

-- ---------------------------------------------------------------- scenario #1 (provisional, D16)

update scenario
set definition = definition
    || '{
         "marketReportCost": 15000000,
         "forecastHorizon": 2,
         "forecastBand": 0.08
       }'::jsonb
where key = 'standard-v1';

-- Cyclicality is the teaching point: an economic downturn barely dents the basic product and
-- badly dents the premium one.
update product set income_elasticity = 0.40 where scenario_key = 'standard-v1' and code = 'P1';
update product set income_elasticity = 1.00 where scenario_key = 'standard-v1' and code = 'P2';
update product set income_elasticity = 1.80 where scenario_key = 'standard-v1' and code = 'P3';

insert into scenario_round (scenario_key, round_number, demand_index, cost_index, wage_index, note)
values ('standard-v1', 1, 1.000, 1.000, 1.000, 'Steady'),
       ('standard-v1', 2, 1.020, 1.020, 1.010, 'Mild expansion'),
       ('standard-v1', 3, 0.950, 1.050, 1.020, 'Slowdown, input prices rising'),
       ('standard-v1', 4, 0.900, 1.080, 1.030, 'Trough'),
       ('standard-v1', 5, 0.930, 1.060, 1.030, 'Bottoming out'),
       ('standard-v1', 6, 1.000, 1.030, 1.020, 'Recovery'),
       ('standard-v1', 7, 1.060, 1.010, 1.020, 'Expansion'),
       ('standard-v1', 8, 1.100, 1.000, 1.030, 'Peak');

insert into environment_event (scenario_key, code, name, description, earliest_round, latest_round,
                               probability, duration_months, demand_multiplier, cost_multiplier,
                               wage_multiplier, target_product)
values
('standard-v1', 'EV-FUEL-SPIKE', 'Fuel and logistics spike',
 'An energy shock raises transport and material costs across every product line.',
 2, 6, 0.55, 2, 1.000, 1.120, 1.000, null),
('standard-v1', 'EV-GEO-CONFLICT', 'Regional conflict',
 'A conflict disrupts shipping lanes: input costs jump and confidence falls for two months.',
 3, 7, 0.45, 2, 0.940, 1.150, 1.010, null),
('standard-v1', 'EV-RECESSION', 'Consumer recession',
 'Households cut discretionary spending. Premium demand falls hardest.',
 3, 6, 0.40, 2, 0.880, 0.970, 1.000, null),
('standard-v1', 'EV-PREMIUM-BOOM', 'Premium segment boom',
 'A new affluent buyer segment opens; premium demand rises for two months.',
 4, 6, 0.35, 2, 1.200, 1.000, 1.000, 'P3');
