-- Chinese names for the R&D programme (D63).
--
-- The catalogue is one set of projects, not two: a class compares projects by code, cost and effect,
-- and a duplicated Chinese list would put two of everything in front of every trainee. What a Chinese
-- class needs is the *label* in its own language, so `name_zh` sits beside `name` and the interface
-- picks by the reader's language.
--
-- These are translations of the five shipped projects, not new programmes: same codes, same target
-- products, same effects. A builder may edit them in the template's R&D tab.
--
-- Applies to tenant copies too (they are or were cloned from the same codes), including frozen ones:
-- a label is not a mechanic, and a class reading Chinese should not meet English project names because
-- its template was frozen before the translation existed.

alter table rnd_project add column if not exists name_zh varchar(160);

update rnd_project set name_zh = case code
        when 'RND-P1-COST' then '基础款流程效率'
        when 'RND-P2-COST' then '标准款流程效率'
        when 'RND-P3-QUAL' then '高端款质量计划'
        when 'RND-CAP'     then '工厂自动化'
        when 'RND-MKT-P1'  then '基础款市场开发'
        else name_zh
    end
where code in ('RND-P1-COST', 'RND-P2-COST', 'RND-P3-QUAL', 'RND-CAP', 'RND-MKT-P1');

comment on column rnd_project.name_zh is
    'D63: the project name a Chinese class reads; English (`name`) is the fallback when this is null';
