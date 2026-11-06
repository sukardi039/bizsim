-- Two R&D catalogues, not one bilingual list (D64, correcting D63).
--
-- D63 added a Chinese label to each project. That is not what was asked for: the set itself should
-- exist in Chinese, as its own set, so a Chinese class reads and chooses projects in Chinese while an
-- English class keeps the English set untouched.
--
-- So each project now belongs to a language, and the Chinese set is a real duplicate: same effects,
-- same costs, same durations, different codes (…-ZH) and Chinese names. A class only ever sees the set
-- for its language, which keeps the choice in front of it to five projects, not ten.

alter table rnd_project add column if not exists lang varchar(2) not null default 'en';

alter table rnd_project drop constraint if exists rnd_project_lang_check;
alter table rnd_project add constraint rnd_project_lang_check check (lang in ('en', 'zh'));

-- The labels D63 added are now redundant: the Chinese set carries its names itself.
alter table rnd_project drop column if exists name_zh;

comment on column rnd_project.lang is
    'D64: which language catalogue this project belongs to; a class sees only its own';

-- The catalogue a session reads is fixed when the session starts, not read per viewer: otherwise two
-- people in the same class could be choosing from different project sets.
alter table run add column if not exists lang varchar(2) not null default 'en';

comment on column run.lang is
    'D64: the R&D catalogue this session reads; fixed at creation from the class language';

update run set lang = coalesce((select p.locale from simulation_package p where p.id = run.package_id),
                               'en')
where run.package_id is not null;

-- The Chinese set: a duplicate of the five shipped projects, for every template that carries them
-- (tenant copies and frozen copies included, so an existing Chinese class is not left with English).
insert into rnd_project (scenario_key, code, name, lang, target_product, advantage_type,
                         advantage_value, hidden_cost, hint_band, duration_months, partial_benefit,
                         benefit_curve, benefit_exponent, overfunding)
select scenario_key,
       code || '-ZH',
       case code
           when 'RND-P1-COST' then '基础款流程效率'
           when 'RND-P2-COST' then '标准款流程效率'
           when 'RND-P3-QUAL' then '高端款质量计划'
           when 'RND-CAP'     then '工厂自动化'
           when 'RND-MKT-P1'  then '基础款市场开发'
       end,
       'zh', target_product, advantage_type, advantage_value, hidden_cost, hint_band,
       duration_months, partial_benefit, benefit_curve, benefit_exponent, overfunding
from rnd_project
where lang = 'en'
  and code in ('RND-P1-COST', 'RND-P2-COST', 'RND-P3-QUAL', 'RND-CAP', 'RND-MKT-P1')
  and not exists (
      select 1 from rnd_project other
      where other.scenario_key = rnd_project.scenario_key and other.code = rnd_project.code || '-ZH'
  );
