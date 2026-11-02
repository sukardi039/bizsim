-- V3: the simulation model ruled on 2026-11-02 (decisions D8-D16).
--
-- Three kinds of change:
--   1. scenario catalogue  -> what a product, a channel and an R&D project are (tuning data, not code)
--   2. company state       -> a product line and a workforce belong to a company, not to a decision
--   3. round/decision keys -> one decision per company per month, and a round can close itself

-- ---------------------------------------------------------------- scenario catalogue

create table product (
    scenario_key     varchar(60)   not null references scenario (key) on delete cascade,
    code             varchar(20)   not null,
    name             varchar(120)  not null,
    base_price       numeric(14, 2) not null check (base_price > 0),
    base_unit_cost   numeric(14, 2) not null check (base_unit_cost >= 0),
    base_quality     numeric(6, 2)  not null default 50,
    base_demand      numeric(14, 2) not null check (base_demand >= 0),
    units_per_worker numeric(10, 3) not null check (units_per_worker > 0),
    primary key (scenario_key, code)
);

create table channel (
    scenario_key varchar(60)  not null references scenario (key) on delete cascade,
    code         varchar(20)  not null,
    name         varchar(120) not null,
    reach_weight numeric(6, 3) not null check (reach_weight > 0),
    brand_alpha  numeric(6, 3) not null check (brand_alpha >= 0),
    primary key (scenario_key, code)
);

-- One project slate per scenario, open to every company in the run (D10). The expected cost is
-- hidden from players; `hint_band` is the width of the published estimate band.
create table rnd_project (
    scenario_key    varchar(60)   not null references scenario (key) on delete cascade,
    code            varchar(40)   not null,
    name            varchar(160)  not null,
    target_product  varchar(20),                       -- null = whole product line
    advantage_type  varchar(30)   not null
                    check (advantage_type in ('UNIT_COST_DOWN', 'CAPACITY_UP', 'QUALITY_UP', 'ATTRACTIVENESS_UP')),
    advantage_value numeric(6, 4) not null check (advantage_value > 0),
    hidden_cost     numeric(16, 2) not null check (hidden_cost > 0),
    hint_band       numeric(4, 2) not null default 0.20 check (hint_band > 0),
    duration_months integer       not null check (duration_months between 1 and 12),
    primary key (scenario_key, code)
);

-- ---------------------------------------------------------------- company state

-- A company's product line. product_code is validated against the run's scenario in code: team
-- does not carry scenario_key, so a composite foreign key would need a denormalised column.
create table company_product (
    team_id       bigint        not null references team (id) on delete cascade,
    product_code  varchar(20)   not null,
    unit_price    numeric(14, 2) not null check (unit_price > 0),
    quality_level numeric(6, 2)  not null,
    unit_cost     numeric(14, 2) not null check (unit_cost >= 0),
    stock         numeric(14, 2) not null default 0 check (stock >= 0),
    primary key (team_id, product_code)
);

create table company_staff (
    team_id   bigint      not null references team (id) on delete cascade,
    function  varchar(20) not null check (function in ('PRODUCTION', 'SALES', 'RND', 'ADMIN')),
    headcount integer     not null check (headcount >= 0),
    primary key (team_id, function)
);

create table company_rnd (
    team_id         bigint        not null references team (id) on delete cascade,
    project_code    varchar(40)   not null,
    invested        numeric(16, 2) not null default 0,
    months_invested integer       not null default 0,
    started_round   integer,
    status          varchar(20)   not null default 'ACTIVE'
                    check (status in ('ACTIVE', 'COMPLETED', 'FAILED')),
    benefit_factor  numeric(6, 4) not null default 0,
    completed_round integer,
    primary key (team_id, project_code)
);

alter table team add column status varchar(20) not null default 'ACTIVE'
    check (status in ('ACTIVE', 'INSOLVENT'));
alter table team add column machine_workers numeric(10, 2) not null default 0;
alter table team add column cumulative_revenue numeric(16, 2) not null default 0;
alter table team add column cumulative_profit numeric(16, 2) not null default 0;
alter table team add column lost_sales_total numeric(14, 2) not null default 0;

-- ---------------------------------------------------------------- round and decision keys

-- D12: the clock closes the round. Null means "no clock", which is how a facilitator-only run works.
alter table round add column closes_at timestamptz;

-- D1: one submission per company per month. role_key stays as an informational column.
alter table decision drop constraint decision_round_id_team_id_role_key_key;
alter table decision add constraint decision_round_team_key unique (round_id, team_id);
alter table decision alter column role_key drop not null;

-- Line-item detail for the debrief. round_result keeps the headline numbers for the scoreboard, so
-- the board query stays cheap and the PDF never recomputes anything.
create table round_statement (
    round_id           bigint         not null references round (id) on delete cascade,
    team_id            bigint         not null references team (id) on delete cascade,
    revenue            numeric(16, 2) not null,
    cogs               numeric(16, 2) not null,
    payroll            numeric(16, 2) not null,
    marketing          numeric(16, 2) not null,
    rnd_spend          numeric(16, 2) not null,
    fixed_cost         numeric(16, 2) not null,
    holding_cost       numeric(16, 2) not null,
    hiring_cost        numeric(16, 2) not null,
    capacity_cost      numeric(16, 2) not null,
    profit             numeric(16, 2) not null,
    cash_after         numeric(16, 2) not null,
    brand_after        numeric(6, 2)  not null,
    lost_sales         numeric(14, 2) not null,
    capacity_used      numeric(10, 3) not null,
    capacity_available numeric(10, 3) not null,
    detail             jsonb          not null,
    primary key (round_id, team_id)
);

-- ---------------------------------------------------------------- scenario #1 (provisional, D16)

update scenario
set name = 'Standard manufacturing simulation',
    definition = '{
      "rounds": 8,
      "roundMinutes": 20,
      "startingCash": 2000000000,
      "startingBrand": 50,
      "fixedCostPerMonth": 50000000,
      "brandDecay": 0.10,
      "holdingCostPerUnit": 2000,
      "referenceQuality": 50,
      "referenceSpend": 20000000,
      "machineCostPerWorker": 40000000,
      "hiringCostMonths": 1,
      "severanceMonths": 1,
      "minRndBenefitRatio": 0.80,
      "baselineCompetitor": { "name": "Market Average", "share": 0.18 },
      "elasticity": { "price": 1.5, "quality": 0.8, "brand": 0.6, "marketing": 0.7 },
      "salaries": { "PRODUCTION": 4500000, "SALES": 7000000, "RND": 8000000, "ADMIN": 6000000 },
      "startingStaff": { "PRODUCTION": 30, "SALES": 3, "RND": 2, "ADMIN": 2 },
      "seasonality": [1.00, 1.00, 0.95, 1.05, 1.00, 1.10, 1.15, 1.20]
    }'::jsonb
where key = 'standard-v1';

insert into product (scenario_key, code, name, base_price, base_unit_cost, base_quality, base_demand, units_per_worker)
values ('standard-v1', 'P1', 'Basic',     1000000,  590000, 50, 4000, 40),
       ('standard-v1', 'P2', 'Standard',  2500000, 1430000, 50, 1500, 15),
       ('standard-v1', 'P3', 'Premium',   5000000, 2780000, 50,  600,  6);

insert into channel (scenario_key, code, name, reach_weight, brand_alpha)
values ('standard-v1', 'DIGITAL', 'Digital', 1.000, 1.6),
       ('standard-v1', 'TRADE',   'Trade',   1.200, 1.2),
       ('standard-v1', 'FIELD',   'Field sales', 0.800, 0.8);

insert into rnd_project (scenario_key, code, name, target_product, advantage_type, advantage_value,
                         hidden_cost, hint_band, duration_months)
values ('standard-v1', 'RND-P1-COST', 'Process efficiency on Basic',    'P1', 'UNIT_COST_DOWN', 0.1500, 300000000, 0.20, 3),
       ('standard-v1', 'RND-P2-COST', 'Process efficiency on Standard', 'P2', 'UNIT_COST_DOWN', 0.1500, 350000000, 0.20, 3),
       ('standard-v1', 'RND-P3-QUAL', 'Quality programme for Premium',  'P3', 'QUALITY_UP',     15.0000, 450000000, 0.25, 4),
       ('standard-v1', 'RND-CAP',     'Automation of the plant',        null, 'CAPACITY_UP',     0.2000, 400000000, 0.25, 4),
       ('standard-v1', 'RND-MKT-P1',  'Market development for Basic',   'P1', 'ATTRACTIVENESS_UP', 0.1500, 250000000, 0.20, 2);
