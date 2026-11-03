-- Templates become tenant-extensible (ruling R2, 3 Nov 2026).
--
-- The shipped template stays exactly what it is: one global row, `standard-v1`, with no tenant. A
-- tenant that needs a different economy copies it and edits the copy, so the global template can
-- never be broken by a class and every tenant's variant is still one row in the same table.
--
-- `key` stays the primary key rather than becoming (tenant_id, key): every catalogue table
-- (product, channel, rnd_project, scenario_round, environment_event) references scenario (key), and
-- widening the key would rewrite five foreign keys for no gain. A tenant copy instead gets its own
-- unique key, namespaced by tenant code, with `based_on` recording where it came from.

alter table scenario add column tenant_id bigint references tenant (id) on delete cascade;
alter table scenario add column status varchar(20) not null default 'PUBLISHED'
    check (status in ('DRAFT', 'PUBLISHED'));
alter table scenario add column based_on varchar(60) references scenario (key);

create index scenario_tenant_idx on scenario (tenant_id);

comment on column scenario.tenant_id is
    'null = the shipped global template; set = a template owned by that tenant';
comment on column scenario.based_on is
    'the template this one was copied from, for provenance';

-- The shipped template is global and published; nothing to backfill.
update scenario set tenant_id = null, status = 'PUBLISHED' where key = 'standard-v1';
