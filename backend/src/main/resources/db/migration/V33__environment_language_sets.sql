-- V33: the environment is per language, like the R&D catalogue (D86, extending D64).
--
-- D83 stored the mechanic once and put only the words in `environment_text`. That reads well until a
-- third language arrives: adding one then means touching every row in two tables, and a translator
-- cannot author a card as a card. The product owner's ruling is the R&D pattern — a language owns a
-- full set (cards, cycle, narrative), and a class reads only its own set, chosen by `run.lang`.
--
-- So:
--   * `environment_event.lang` and `scenario_round.lang`, with `-<LANG>` codes on the duplicated set
--     (R&D uses `-ZH`; that convention is reused so the two catalogues read alike);
--   * `scenario_round.description` — the narrative moves onto the row it belongs to;
--   * `environment_text` shrinks to customer classes only (a class is one mechanic with per-language
--     words, not a per-language set: two drifting class sizes would be a defect, not a translation);
--   * language codes widen from ('en','zh') to any two lowercase letters, so a new language is data.
--
-- The Chinese set is seeded as a duplicate of the English one: same numbers, same effects, Chinese
-- words. The draw seeds on the canonical (suffix-stripped) code, so a run draws the same weather in
-- whichever language its class reads.

-- ---------------------------------------------------------------- language columns

alter table environment_event add column if not exists lang varchar(2) not null default 'en';
alter table environment_event drop constraint if exists environment_event_lang_check;
alter table environment_event add constraint environment_event_lang_check
    check (lang ~ '^[a-z]{2}$');

alter table scenario_round add column if not exists lang varchar(2) not null default 'en';
alter table scenario_round add column if not exists description varchar(600);
alter table scenario_round drop constraint if exists scenario_round_pkey;
alter table scenario_round add primary key (scenario_key, lang, round_number);
alter table scenario_round drop constraint if exists scenario_round_lang_check;
alter table scenario_round add constraint scenario_round_lang_check check (lang ~ '^[a-z]{2}$');

alter table environment_text drop constraint if exists environment_text_lang_check;
alter table environment_text add constraint environment_text_lang_check check (lang ~ '^[a-z]{2}$');

-- The run freezes the narrative in the language it plays (D84). The two-column split is gone.
alter table run_round_economy drop column if exists description_zh;
alter table run_round_economy drop column if exists label_zh;

comment on column environment_event.lang is
    'D86: which language deck this card belongs to; a class sees only its own';
comment on column scenario_round.lang is
    'D86: which language cycle this position belongs to; a run reads only its own';
comment on column scenario_round.description is
    'D86: the round narrative a player reads, in this row''s language';

-- ---------------------------------------------------------------- English set gets its words

-- The round narrative was in environment_text; it belongs on the row.
update scenario_round r
set description = t.description
from environment_text t
where t.scenario_key = r.scenario_key
  and t.ref_type = 'ROUND'
  and t.lang = 'en'
  and t.ref_code = r.round_number::text
  and r.lang = 'en';

-- ---------------------------------------------------------------- the Chinese set

-- Cards: a full duplicate with Chinese words where they exist. `environment_text` is the only place
-- the Chinese wording was stored, so it is folded in here before those rows are removed.
insert into environment_event (scenario_key, code, name, description, category, lang, earliest_round,
                               latest_round, probability, duration_months, demand_multiplier,
                               cost_multiplier, wage_multiplier, target_product)
select e.scenario_key,
       e.code || '-ZH',
       coalesce((select t.name from environment_text t
                 where t.scenario_key = e.scenario_key and t.ref_type = 'EVENT'
                   and t.ref_code = e.code and t.lang = 'zh'), e.name),
       coalesce((select t.description from environment_text t
                 where t.scenario_key = e.scenario_key and t.ref_type = 'EVENT'
                   and t.ref_code = e.code and t.lang = 'zh'), e.description),
       e.category, 'zh', e.earliest_round, e.latest_round, e.probability, e.duration_months,
       e.demand_multiplier, e.cost_multiplier, e.wage_multiplier, e.target_product
from environment_event e
where e.lang = 'en'
  and not exists (select 1 from environment_event other
                  where other.scenario_key = e.scenario_key and other.code = e.code || '-ZH');

-- The duplicate reaches the same classes with the same numbers (D82).
insert into environment_event_effect (scenario_key, event_code, segment_code, size_multiplier,
                                      purchasing_power_multiplier)
select f.scenario_key, f.event_code || '-ZH', f.segment_code, f.size_multiplier,
       f.purchasing_power_multiplier
from environment_event_effect f
join environment_event e
  on e.scenario_key = f.scenario_key and e.code = f.event_code and e.lang = 'en'
where not exists (select 1 from environment_event_effect other
                  where other.scenario_key = f.scenario_key
                    and other.event_code = f.event_code || '-ZH'
                    and other.segment_code = f.segment_code);

-- Cycle: same indices, Chinese label and narrative.
insert into scenario_round (scenario_key, lang, round_number, demand_index, cost_index, wage_index,
                            trend_strength, trend_label, note, description)
select r.scenario_key, 'zh', r.round_number, r.demand_index, r.cost_index, r.wage_index,
       r.trend_strength,
       coalesce((select t.name from environment_text t
                 where t.scenario_key = r.scenario_key and t.ref_type = 'ROUND'
                   and t.ref_code = r.round_number::text and t.lang = 'zh'), r.trend_label),
       r.note,
       coalesce((select t.description from environment_text t
                 where t.scenario_key = r.scenario_key and t.ref_type = 'ROUND'
                   and t.ref_code = r.round_number::text and t.lang = 'zh'), r.description)
from scenario_round r
where r.lang = 'en'
  and not exists (select 1 from scenario_round other
                  where other.scenario_key = r.scenario_key and other.lang = 'zh'
                    and other.round_number = r.round_number);

-- The words live in the set now; `environment_text` keeps only customer classes.
delete from environment_text where ref_type in ('EVENT', 'ROUND');

comment on table environment_text is
    'D86: per-language names and descriptions for customer classes only; cards and cycles carry their own words';
