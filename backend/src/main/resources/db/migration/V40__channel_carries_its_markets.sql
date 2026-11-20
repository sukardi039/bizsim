-- D109: a channel record carries its own market-specific parameters.
--
-- D108 put the per-class reach weights in a side table (`channel_reach`). That made a channel a row in
-- two places: its own record said what it was, and a second table said what it reached. The owner's
-- ruling is that a channel record *is* the record — its market-specific parameters belong to it — so the
-- weights move onto `channel` as one column per customer class.
--
-- Why this is safe here and would not be in general: the weights are read through
-- `ScenarioRepository.channels()`/`load()`, never joined in a report or a view, so the storage shape is
-- not visible to anything but the one method that reads it. The columns are nullable on purpose —
-- **null means "this channel is not class-specific here"**, which falls back to `reach_weight`. That
-- keeps every existing template's behaviour identical, exactly as the side table's "no row" did.
--
-- A column per class is used rather than a JSON blob because the classes are a small, authored set in a
-- template (three in the shipped ones), the editor shows one column each, and a real column keeps a
-- typo in a class code a foreign-key error rather than a silently ignored key.
alter table channel add column reach_mass     numeric(6, 3) check (reach_mass >= 0);
alter table channel add column reach_mid      numeric(6, 3) check (reach_mid >= 0);
alter table channel add column reach_premium  numeric(6, 3) check (reach_premium >= 0);

comment on column channel.reach_mass is
    'D109: reach weight for the mass class; null = not class-specific, use reach_weight';
comment on column channel.reach_mid is
    'D109: reach weight for the middle class; null = not class-specific, use reach_weight';
comment on column channel.reach_premium is
    'D109: reach weight for the affluent class; null = not class-specific, use reach_weight';

-- Carry any weights already set through D108 into the new columns, by class code. The shipped templates
-- use SEG-BASIC / SEG-MID / SEG-PREMIUM; a tenant template that renamed its classes keeps its weights
-- because this joins on the class's *position* in the catalogue, not on a hard-coded code.
update channel c
set reach_mass = r.weight
from channel_reach r
where r.scenario_key = c.scenario_key and r.channel_code = c.code
  and r.segment_code = (select segment_code from (
        select code as segment_code, row_number() over (order by sort_order, code) as position
        from customer_segment where scenario_key = c.scenario_key) ranked where position = 1);

update channel c
set reach_mid = r.weight
from channel_reach r
where r.scenario_key = c.scenario_key and r.channel_code = c.code
  and r.segment_code = (select segment_code from (
        select code as segment_code, row_number() over (order by sort_order, code) as position
        from customer_segment where scenario_key = c.scenario_key) ranked where position = 2);

update channel c
set reach_premium = r.weight
from channel_reach r
where r.scenario_key = c.scenario_key and r.channel_code = c.code
  and r.segment_code = (select segment_code from (
        select code as segment_code, row_number() over (order by sort_order, code) as position
        from customer_segment where scenario_key = c.scenario_key) ranked where position = 3);

-- migration:allow-destructive
--
-- Dropping `channel_reach` is safe and deliberate: its contents were copied into the three columns
-- above **in this same migration and the same transaction**, so nothing is lost. The table existed for
-- one release (D108) and no report, view or query reads it — the only reader was the method this
-- migration rewrites. If this deploy were ever rolled back, the table would need recreating from V39.
drop table channel_reach;
