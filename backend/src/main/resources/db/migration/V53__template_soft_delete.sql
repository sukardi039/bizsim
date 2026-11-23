-- V53: a tenant may remove a template it owns, softly (D131).
--
-- Until now there was no way to remove a template at all: TemplateController exposes GET, POST /copy and seven
-- PUTs, and no DELETE. A tenant that copied the shipped template three times kept all three in its picker for
-- ever. The owner asked for the ability, ruled **soft** (history must stay readable), and ruled that the seeded
-- templates stay permanently undeletable.
--
-- `deleted_at` is the whole mechanism. A deleted template:
--   * disappears from the template library (`listVisible`) and from the package picker,
--   * is still resolvable by key, so a run that played it can still name it,
--   * is never touched by the loader's normal path, which resolves by key.
--
-- Nothing is dropped or cascade-deleted. The catalogue rows (products, segments, rounds, texts) stay where they
-- are: they are what the historical run reads when it renders a statement.

alter table scenario add column deleted_at timestamptz;

comment on column scenario.deleted_at is
    'D131: a tenant-owned template removed from the library. Still resolvable by key so played runs keep naming '
    'it. Null = live. Never set on a shipped template (tenant_id is null).';

create index scenario_deleted_idx on scenario (tenant_id, deleted_at);

-- A deleted template must be a tenant's own: a shipped one is a shared reference and cannot be removed by
-- anyone. The constraint says so in the schema, not only in the service (D119: one rule, enforced where it can
-- be, and agreed everywhere).
alter table scenario add constraint scenario_deleted_is_tenant_owned
    check (deleted_at is null or tenant_id is not null);
