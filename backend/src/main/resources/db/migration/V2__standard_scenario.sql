-- A placeholder standard scenario. The definition lands here once the domain questions in the
-- delivery plan §7 are answered (round inputs, market model, KPI set). Until then it carries only
-- what the round engine needs: a round count. Code must not depend on more than this.
insert into scenario (key, name, definition, version)
values ('standard-v1', 'Standard manufacturing simulation', '{"rounds": 6}'::jsonb, 1);
