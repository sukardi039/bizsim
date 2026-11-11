-- V32: three market-side cards, and the kind of shock that names them (D81-D85).
--
-- The deck so far is supply- and confidence-driven. These three give a trainer the two states of
-- the stock market a class reads about every day, plus the one seasonal demand wave every consumer
-- business plans for. They act on classes exactly like the others (D82): the market moves wealth and
-- confidence, which is a class's size and purchasing power, never a product's demand line.
--
-- `MARKETS` joins the category list so the workbench can say "Markets" rather than filing a bull run
-- under "Demand shock". A holiday season is a demand shock, not a market one.

alter table environment_event drop constraint if exists environment_event_category_check;
alter table environment_event add constraint environment_event_category_check
    check (category in ('SUPPLY_SHOCK', 'FINANCIAL_CRISIS', 'INFLATION', 'TECHNOLOGY',
                        'DEMAND_SHOCK', 'POLICY', 'MARKETS', 'OTHER'));

-- Seeded for every template that carries the customer model (the shipped one and any tenant copy).
-- Windows avoid the three cost-push cards of V31: a crash on top of inflation is a depression, and
-- the deck is calibrated to teach one lesson per term, not to wipe the class out.
insert into environment_event (scenario_key, code, name, description, category, earliest_round,
                               latest_round, probability, duration_months, demand_multiplier,
                               cost_multiplier, wage_multiplier, target_product)
select distinct s.key, v.code, v.name, v.description, v.category, v.earliest_round,
       v.latest_round, v.probability, v.duration_months, v.demand_multiplier, v.cost_multiplier,
       v.wage_multiplier, null
from scenario s
cross join (values
    ('EV-BULL-RUN', 'Stock market bull run',
     'Share prices climb and household confidence returns. Discretionary spending rises, led by the affluent class.',
     'MARKETS', 2, 5, 0.30, 2, 1.000, 1.000, 1.000),
    ('EV-MARKET-CRASH', 'Stock market crash',
     'Share prices fall sharply and household wealth shrinks. Confidence collapses and big-ticket purchases are postponed.',
     'MARKETS', 3, 6, 0.25, 2, 1.000, 1.020, 1.000),
    ('EV-HOLIDAY-SEASON', 'Holiday season',
     'The holiday season lifts retail demand in every class. Mass-market gifting rises most, and logistics costs rise with it.',
     'DEMAND_SHOCK', 5, 8, 0.45, 1, 1.000, 1.030, 1.050)
) as v(code, name, description, category, earliest_round, latest_round, probability,
       duration_months, demand_multiplier, cost_multiplier, wage_multiplier)
where exists (select 1 from customer_segment cs where cs.scenario_key = s.key)
on conflict (scenario_key, code) do nothing;

-- Per-class reach. Provisional values (D16): the shape is the lesson — wealth sits with the
-- affluent, so a bull run and a crash both land hardest there, while a holiday season is broad and
-- strongest at the mass end.
insert into environment_event_effect (scenario_key, event_code, segment_code, size_multiplier,
                                      purchasing_power_multiplier)
select distinct e.scenario_key, e.code, v.segment_code, v.size_multiplier, v.pp_multiplier
from environment_event e
cross join (values
    -- bull run: wealth effect, concentrated at the top
    ('EV-BULL-RUN',       'SEG-BASIC',   1.010, 1.020),
    ('EV-BULL-RUN',       'SEG-MID',     1.050, 1.040),
    ('EV-BULL-RUN',       'SEG-PREMIUM', 1.150, 1.080),
    -- crash: the same channel, in reverse, and faster
    ('EV-MARKET-CRASH',   'SEG-BASIC',   0.960, 0.970),
    ('EV-MARKET-CRASH',   'SEG-MID',     0.860, 0.900),
    ('EV-MARKET-CRASH',   'SEG-PREMIUM', 0.720, 0.820),
    -- holiday season: broad demand, strongest at the mass end
    ('EV-HOLIDAY-SEASON', 'SEG-BASIC',   1.180, 1.060),
    ('EV-HOLIDAY-SEASON', 'SEG-MID',     1.140, 1.050),
    ('EV-HOLIDAY-SEASON', 'SEG-PREMIUM', 1.100, 1.040)
) as v(event_code, segment_code, size_multiplier, pp_multiplier)
where e.code = v.event_code
  and exists (select 1 from customer_segment cs
              where cs.scenario_key = e.scenario_key and cs.code = v.segment_code)
on conflict (scenario_key, event_code, segment_code) do nothing;

-- The words. English lives on the card row; Chinese beside the mechanic (D83).
insert into environment_text (scenario_key, ref_type, ref_code, lang, name, description)
select distinct e.scenario_key, 'EVENT', e.code, 'zh',
       case e.code
           when 'EV-BULL-RUN'       then '股市牛市'
           when 'EV-MARKET-CRASH'   then '股市崩盘'
           when 'EV-HOLIDAY-SEASON' then '节庆旺季'
           else e.name
       end,
       case e.code
           when 'EV-BULL-RUN' then
               '股价上涨，家庭信心回升。可选消费上升，其中高端客户涨得最多。'
           when 'EV-MARKET-CRASH' then
               '股价大幅下跌，家庭财富缩水。信心崩塌，大额消费被推迟。'
           when 'EV-HOLIDAY-SEASON' then
               '节庆旺季带动各阶层零售需求上升。大众市场的礼品消费涨幅最大，物流成本也随之上升。'
           else e.description
       end
from environment_event e
where e.code in ('EV-BULL-RUN', 'EV-MARKET-CRASH', 'EV-HOLIDAY-SEASON')
on conflict (scenario_key, ref_type, ref_code, lang) do nothing;
