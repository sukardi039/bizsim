-- V31: the market environment is an economic model, not a demand dial (D81-D85).
--
-- Until now the economy moved market size through one number per round plus a per-product income
-- elasticity. That is a demand index with a league table, not a market: there were no customers in
-- it. This migration puts the customer in the model.
--
-- Three classes of potential customer exist in a scenario. Each product targets one class, and the
-- market the product competes for is that class's size (units it can afford) plus a stated
-- percentage of the other classes' customers. Economic events — an oil crisis, inflation, a
-- technology slump — do not touch a product's demand directly: they move a class's size and its
-- purchasing power, and the product inherits whatever happens to its class.
--
--   segment size (units, round r)  = base_size x seasonality x cohort scale
--                                    x demand_index(r) ^ segment.income_elasticity
--                                    x (1 + trend_strength(r) x segment.trend_affinity)
--                                    x product of the size multipliers of active events
--   segment purchasing power        = base_purchasing_power
--                                    x product of the pp multipliers of active events
--   market for product j            = size_s x pp_s
--                                    + cross_segment_pct_j x sum(size_k x pp_k for k != s)
--
-- The class is the unit of economic reaction; the product is the unit of competition. That is the
-- whole point, and it is why elasticity and trend affinity move from the product to the segment.
--
-- Everything here is scenario data (D16, D82): the shipped `standard-v1` carries the default model,
-- a tenant copies a template and edits the copy (R2/D22), and the data is part of the initial seed.

-- ---------------------------------------------------------------- customer classes

create table customer_segment (
    scenario_key          varchar(60)   not null references scenario (key) on delete cascade,
    code                  varchar(20)   not null,
    name                  varchar(120)  not null,
    -- Units the class can buy in a reference round, sized for ten companies (decision 5).
    base_size             numeric(14, 2) not null check (base_size >= 0),
    -- 1.000 = the class affords its base size at the reference price. Events move this.
    base_purchasing_power numeric(6, 3) not null default 1.000 check (base_purchasing_power > 0),
    income_elasticity     numeric(5, 2) not null default 1.00 check (income_elasticity >= 0),
    trend_affinity        numeric(4, 2) not null default 0.00 check (trend_affinity between -1 and 1),
    sort_order            integer       not null default 0,
    primary key (scenario_key, code)
);

comment on table customer_segment is
    'D81: the three classes of potential customer; a product targets one and inherits its economy';

-- Which class this product's customers come from, and how much of the other classes' custom it
-- also serves. Null target_segment = the pre-D81 model (base_demand x demand index), so a template
-- written before this migration keeps its numbers exactly.
alter table product add column target_segment varchar(20);
alter table product add column cross_segment_pct numeric(4, 3) not null default 0.000
    check (cross_segment_pct >= 0 and cross_segment_pct <= 1);

comment on column product.target_segment is
    'D81: the customer class this product is made for; null = legacy demand pool';
comment on column product.cross_segment_pct is
    'D81: share of the other classes'' market this product also competes for (0.02 = 2%)';

-- ---------------------------------------------------------------- events act on classes

-- What kind of shock this is, so the workbench can say "inflation" rather than "demand x 0.9".
alter table environment_event add column category varchar(30) not null default 'OTHER'
    check (category in ('SUPPLY_SHOCK', 'FINANCIAL_CRISIS', 'INFLATION', 'TECHNOLOGY',
                        'DEMAND_SHOCK', 'POLICY', 'OTHER'));

-- One row per (event, class). A missing row means "this event does not move that class".
-- Size is how many customers the class has; purchasing power is what each of them can spend.
create table environment_event_effect (
    scenario_key   varchar(60)  not null,
    event_code     varchar(40)  not null,
    segment_code   varchar(20)  not null,
    size_multiplier           numeric(6, 3) not null default 1.000 check (size_multiplier > 0),
    purchasing_power_multiplier numeric(6, 3) not null default 1.000
        check (purchasing_power_multiplier > 0),
    primary key (scenario_key, event_code, segment_code),
    foreign key (scenario_key, event_code)
        references environment_event (scenario_key, code) on delete cascade
);

comment on table environment_event_effect is
    'D82: an economic event moves each customer class''s size and purchasing power, never a product';

-- The draw already froze *which* cards fire and when. From here it also freezes *what they do*:
-- until now the multipliers were read live from the template, so editing a card silently changed a
-- class that had already been playing. A run keeps its own weather (D18).
alter table run_environment_event add column if not exists demand_multiplier numeric(6, 3)
    not null default 1.000;
alter table run_environment_event add column if not exists cost_multiplier numeric(6, 3)
    not null default 1.000;
alter table run_environment_event add column if not exists wage_multiplier numeric(6, 3)
    not null default 1.000;
alter table run_environment_event add column if not exists target_product varchar(20);
alter table run_environment_event add column if not exists effects jsonb not null default '[]'::jsonb;

-- The words are frozen with the numbers: the narrative a class reads must not change because a
-- builder edited the template after the run was created (D83/D84). A run drawn before this
-- migration has nulls here and reads the template's words instead.
alter table run_round_economy add column if not exists description varchar(600);
alter table run_round_economy add column if not exists description_zh varchar(600);
alter table run_round_economy add column if not exists label_zh varchar(160);

-- A run drawn before this migration keeps the numbers it was drawn under, not this migration's zeros.
update run_environment_event x
set demand_multiplier = e.demand_multiplier,
    cost_multiplier   = e.cost_multiplier,
    wage_multiplier   = e.wage_multiplier,
    target_product    = e.target_product
from environment_event e, run r
where r.id = x.run_id and e.scenario_key = r.scenario_key and e.code = x.event_code;

-- ---------------------------------------------------------------- the words

-- The brief description a player reads in the workbench, per language. One row per object and
-- language: the mechanic is stored once (segments, events, rounds), only the words are duplicated.
-- D64 separated *selectable* catalogues into one set per language; a description is not a choice,
-- so it lives here rather than multiplying the weather.
create table environment_text (
    scenario_key varchar(60) not null references scenario (key) on delete cascade,
    ref_type     varchar(10) not null check (ref_type in ('SEGMENT', 'EVENT', 'ROUND')),
    ref_code     varchar(40) not null,
    lang         varchar(2)  not null check (lang in ('en', 'zh')),
    name         varchar(160),
    description  varchar(800),
    primary key (scenario_key, ref_type, ref_code, lang)
);

comment on table environment_text is
    'D83: the multilingual name and description of a segment, an event or a cycle position';

-- ---------------------------------------------------------------- initial data

-- Three classes, sized exactly like the three products they serve, so the calibrated numbers of
-- scenario #1 do not move: P1 4000, P2 1500, P3 600 units per round for ten companies.
insert into customer_segment (scenario_key, code, name, base_size, base_purchasing_power,
                              income_elasticity, trend_affinity, sort_order)
select distinct p.scenario_key, 'SEG-BASIC', 'Mass market', 4000, 1.000, 0.40, -0.50, 1
from product p where p.code = 'P1'
on conflict (scenario_key, code) do nothing;

insert into customer_segment (scenario_key, code, name, base_size, base_purchasing_power,
                              income_elasticity, trend_affinity, sort_order)
select distinct p.scenario_key, 'SEG-MID', 'Middle market', 1500, 1.000, 1.00, 0.00, 2
from product p where p.code = 'P2'
on conflict (scenario_key, code) do nothing;

insert into customer_segment (scenario_key, code, name, base_size, base_purchasing_power,
                              income_elasticity, trend_affinity, sort_order)
select distinct p.scenario_key, 'SEG-PREMIUM', 'Affluent buyers', 600, 1.000, 1.80, 0.50, 3
from product p where p.code = 'P3'
on conflict (scenario_key, code) do nothing;

update product
set target_segment = case code
        when 'P1' then 'SEG-BASIC'
        when 'P2' then 'SEG-MID'
        when 'P3' then 'SEG-PREMIUM'
    end
where code in ('P1', 'P2', 'P3')
  and exists (select 1 from customer_segment cs
              where cs.scenario_key = product.scenario_key
                and cs.code = case product.code
                                  when 'P1' then 'SEG-BASIC'
                                  when 'P2' then 'SEG-MID'
                                  else 'SEG-PREMIUM'
                              end);

-- Cross-segment reach: the basic line picks up a little of the middle class, the standard line is
-- where upgrading and trading down meet, the premium line sells to aspirational mass buyers.
update product set cross_segment_pct = 0.030 where code = 'P1';
update product set cross_segment_pct = 0.050 where code = 'P2';
update product set cross_segment_pct = 0.020 where code = 'P3';

-- The four shipped cards gain a category. Their old market-wide demand multiplier becomes a set of
-- per-class effects below, so a recession is no longer the same number for every customer.
update environment_event set category = 'SUPPLY_SHOCK'      where code = 'EV-FUEL-SPIKE';
update environment_event set category = 'FINANCIAL_CRISIS'  where code = 'EV-GEO-CONFLICT';
update environment_event set category = 'DEMAND_SHOCK'      where code = 'EV-RECESSION';
update environment_event set category = 'DEMAND_SHOCK'      where code = 'EV-PREMIUM-BOOM';

update environment_event
set demand_multiplier = 1.000, target_product = null
where code in ('EV-FUEL-SPIKE', 'EV-GEO-CONFLICT', 'EV-RECESSION', 'EV-PREMIUM-BOOM');

-- Three more shocks, the ones a trainer actually names: an oil crisis, inflation, a technology
-- slump. They are seeded for every template that carries the shipped product set (copies included).
-- Their windows deliberately overlap only in pairs: three cost-push cards fired at once multiply
-- input costs by ~1.5, which is a term no class survives, and the lesson is the pairing (an oil
-- crisis *and* inflation = stagflation), not a wipe-out.
insert into environment_event (scenario_key, code, name, description, category, earliest_round,
                               latest_round, probability, duration_months, demand_multiplier,
                               cost_multiplier, wage_multiplier, target_product)
select distinct s.key, v.code, v.name, v.description, v.category, v.earliest_round,
       v.latest_round, v.probability, v.duration_months, v.demand_multiplier, v.cost_multiplier,
       v.wage_multiplier, null
from scenario s
cross join (values
    ('EV-OIL-CRISIS', 'Oil crisis',
     'Energy prices double. Transport and materials cost more, and households have less left to spend.',
     'SUPPLY_SHOCK', 3, 6, 0.35, 2, 1.000, 1.180, 1.030),
    ('EV-INFLATION', 'Inflation',
     'Prices rise faster than wages. Every class can afford less, and the premium class postpones purchases.',
     'INFLATION', 4, 7, 0.35, 3, 1.000, 1.120, 1.080),
    ('EV-TECH-DEPRESSION', 'Technology slump',
     'A wave of automation leaves the premium category looking dated; buyers wait for the next generation.',
     'TECHNOLOGY', 2, 5, 0.30, 2, 1.000, 0.980, 1.000)
) as v(code, name, description, category, earliest_round, latest_round, probability,
       duration_months, demand_multiplier, cost_multiplier, wage_multiplier)
where exists (select 1 from product p where p.scenario_key = s.key and p.code = 'P1')
on conflict (scenario_key, code) do nothing;

-- Per-class effects. Values are provisional (D16): the shape is the lesson, not the decimals.
insert into environment_event_effect (scenario_key, event_code, segment_code, size_multiplier,
                                      purchasing_power_multiplier)
select distinct e.scenario_key, e.code, v.segment_code, v.size_multiplier, v.pp_multiplier
from environment_event e
cross join (values
    -- supply shocks: costs up, the cheap end absorbs a little trade-down
    ('EV-FUEL-SPIKE',      'SEG-BASIC',   1.020, 1.000),
    ('EV-FUEL-SPIKE',      'SEG-MID',     0.970, 0.980),
    ('EV-FUEL-SPIKE',      'SEG-PREMIUM', 0.900, 0.960),
    -- conflict: confidence falls, premium waits
    ('EV-GEO-CONFLICT',    'SEG-BASIC',   0.980, 0.990),
    ('EV-GEO-CONFLICT',    'SEG-MID',     0.940, 0.970),
    ('EV-GEO-CONFLICT',    'SEG-PREMIUM', 0.880, 0.950),
    -- recession: the classic trading-down lesson
    ('EV-RECESSION',       'SEG-BASIC',   1.020, 0.950),
    ('EV-RECESSION',       'SEG-MID',     0.900, 0.920),
    ('EV-RECESSION',       'SEG-PREMIUM', 0.720, 0.850),
    ('EV-PREMIUM-BOOM',    'SEG-BASIC',   1.000, 1.000),
    ('EV-PREMIUM-BOOM',    'SEG-MID',     1.050, 1.020),
    ('EV-PREMIUM-BOOM',    'SEG-PREMIUM', 1.250, 1.080),
    -- oil crisis: energy is a bigger share of a premium buyer's basket
    ('EV-OIL-CRISIS',      'SEG-BASIC',   1.030, 0.980),
    ('EV-OIL-CRISIS',      'SEG-MID',     0.950, 0.950),
    ('EV-OIL-CRISIS',      'SEG-PREMIUM', 0.820, 0.900),
    -- inflation: purchasing power falls for everyone, hardest for the premium class
    ('EV-INFLATION',       'SEG-BASIC',   0.960, 0.900),
    ('EV-INFLATION',       'SEG-MID',     0.900, 0.860),
    ('EV-INFLATION',       'SEG-PREMIUM', 0.820, 0.780),
    -- technology slump: the premium category is the one that looks obsolete
    ('EV-TECH-DEPRESSION', 'SEG-BASIC',   1.020, 1.000),
    ('EV-TECH-DEPRESSION', 'SEG-MID',     0.920, 0.950),
    ('EV-TECH-DEPRESSION', 'SEG-PREMIUM', 0.750, 0.880)
) as v(event_code, segment_code, size_multiplier, pp_multiplier)
where e.code = v.event_code
  and exists (select 1 from customer_segment cs
              where cs.scenario_key = e.scenario_key and cs.code = v.segment_code)
on conflict (scenario_key, event_code, segment_code) do nothing;

-- The words: English segments, then Chinese for both class and event labels.
insert into environment_text (scenario_key, ref_type, ref_code, lang, name, description)
select distinct cs.scenario_key, 'SEGMENT', cs.code, 'en', cs.name,
       case cs.code
           when 'SEG-BASIC' then
               'Mass-market households buying on price and availability. In a downturn they trade down into this category rather than out of the market.'
           when 'SEG-MID' then
               'Middle-income households balancing price against quality. This is where upgrading and trading down meet, so the class moves both ways with confidence.'
           else
               'Affluent buyers who pay for quality and brand. Their purchases are the first to be postponed when confidence falls and the first to return when it recovers.'
       end
from customer_segment cs
on conflict (scenario_key, ref_type, ref_code, lang) do nothing;

insert into environment_text (scenario_key, ref_type, ref_code, lang, name, description)
select distinct cs.scenario_key, 'SEGMENT', cs.code, 'zh',
       case cs.code
           when 'SEG-BASIC' then '大众市场'
           when 'SEG-MID' then '中端市场'
           else '高端客户'
       end,
       case cs.code
           when 'SEG-BASIC' then '以价格和可得性为决策依据的大众家庭。经济下行时，他们会降级消费进入这一品类，而不是退出市场。'
           when 'SEG-MID' then '在价格与品质之间权衡的中等收入家庭。这里是消费升级与降级交汇的地方，因此这一群体随信心双向移动。'
           else '愿意为品质和品牌付费的高收入客户。信心下降时他们的购买最先被推迟，复苏时也最先回来。'
       end
from customer_segment cs
on conflict (scenario_key, ref_type, ref_code, lang) do nothing;

insert into environment_text (scenario_key, ref_type, ref_code, lang, name, description)
select distinct e.scenario_key, 'EVENT', e.code, 'zh',
       case e.code
           when 'EV-FUEL-SPIKE'      then '燃料与物流价格飙升'
           when 'EV-GEO-CONFLICT'    then '地区冲突'
           when 'EV-RECESSION'       then '消费衰退'
           when 'EV-PREMIUM-BOOM'    then '高端需求繁荣'
           when 'EV-OIL-CRISIS'      then '石油危机'
           when 'EV-INFLATION'       then '通货膨胀'
           when 'EV-TECH-DEPRESSION' then '技术萧条'
           else e.name
       end,
       case e.code
           when 'EV-FUEL-SPIKE' then
               '能源冲击推高了所有产品线的运输和材料成本。'
           when 'EV-GEO-CONFLICT' then
               '冲突扰乱了航运通道：投入成本上涨，信心下滑，持续两个月。'
           when 'EV-RECESSION' then
               '家庭削减可选消费。高端需求跌幅最大，大众品类的降级需求反而增加。'
           when 'EV-PREMIUM-BOOM' then
               '新的富裕买家群体出现，高端需求在两个月内上升。'
           when 'EV-OIL-CRISIS' then
               '能源价格翻倍。运输和材料更贵，家庭可用于消费的收入减少。'
           when 'EV-INFLATION' then
               '物价上涨快于工资。每个客户群体的购买力都下降，高端客户推迟购买。'
           when 'EV-TECH-DEPRESSION' then
               '自动化浪潮让高端品类显得过时，买家等待下一代产品。'
           else e.description
       end
from environment_event e
where e.code like 'EV-%'
on conflict (scenario_key, ref_type, ref_code, lang) do nothing;

-- The cycle narrative, for the templates that still carry the shipped four-position cycle.
insert into environment_text (scenario_key, ref_type, ref_code, lang, name, description)
select distinct r.scenario_key, 'ROUND', r.round_number::text, 'en',
       case r.round_number
           when 1 then 'Stable'
           when 2 then 'Expansion'
           when 3 then 'Recession'
           else 'Recovery'
       end,
       case r.round_number
           when 1 then
               'Demand and costs are at their baseline. A good month to build stock or invest in brand.'
           when 2 then
               'Households are confident and premium buyers are trading up, but input costs are rising with demand.'
           when 3 then
               'Demand is falling while input prices stay high. Consumers trade down; the premium class is hit hardest.'
           else
               'The trough has passed. Orders return slowly and costs remain elevated.'
       end
from scenario_round r
where r.round_number <= 4
  and exists (select 1 from scenario_round x
              where x.scenario_key = r.scenario_key
                and x.round_number = 3 and x.demand_index = 0.920)
on conflict (scenario_key, ref_type, ref_code, lang) do nothing;

insert into environment_text (scenario_key, ref_type, ref_code, lang, name, description)
select distinct r.scenario_key, 'ROUND', r.round_number::text, 'zh',
       case r.round_number
           when 1 then '平稳'
           when 2 then '扩张'
           when 3 then '衰退'
           else '复苏'
       end,
       case r.round_number
           when 1 then '需求与成本都处于基准水平。适合备货或投资品牌的一个月。'
           when 2 then '家庭信心充足，高端客户正在升级消费，但投入成本随需求上升。'
           when 3 then '需求下滑而投入价格仍处高位。消费者降级消费，高端客户受冲击最大。'
           else '低谷已经过去。订单缓慢回流，成本仍然偏高。'
       end
from scenario_round r
where r.round_number <= 4
  and exists (select 1 from scenario_round x
              where x.scenario_key = r.scenario_key
                and x.round_number = 3 and x.demand_index = 0.920)
on conflict (scenario_key, ref_type, ref_code, lang) do nothing;
