-- D80: a statement records the inventory basis it was computed under.
--
-- The basis is a template setting (D79), and a template can be edited — but a statement must not
-- change meaning afterwards. Storing it per row makes every historical report self-describing and
-- lets an audit replay a run without guessing which basis the engine used that month.

alter table round_statement
    add column if not exists cost_basis varchar(12) not null default 'REPLACEMENT';

-- Historical rows: the basis is whatever the template they were computed from says. Frozen package
-- templates cannot have changed since, so this is exact for every published class; a run started
-- straight from a template that was edited afterwards keeps the default rather than a guess.
update round_statement s
set cost_basis = coalesce((
        select upper(coalesce(sc.definition ->> 'costBasis', 'REPLACEMENT'))
        from round rd
        join run r on r.id = rd.run_id
        join scenario sc on sc.key = r.scenario_key
        where rd.id = s.round_id), 'REPLACEMENT')
where s.cost_basis = 'REPLACEMENT';
