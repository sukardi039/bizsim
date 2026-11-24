-- V56: a package is created for one facilitator, and only that facilitator runs it (D140).
--
-- The need, in the owner's words: a tenant has several facilitators, an administrator/builder creates packages
-- for a specific facilitator, and when that facilitator signs in they see **only their own** packages — so a class
-- is never run by the wrong person. Today `simulation_package` has no owner: every facilitator in the tenant sees
-- and can start every package.
--
-- Two guards, deliberately both on the server:
--   * the list is filtered to the caller's packages (a facilitator never sees another's);
--   * starting a session is **refused** (403) when the package is not the caller's, because a hidden row is not a
--     protected row — a stray request or a copied id must not run someone else's class.
--
-- Order matters, and V55 is the reason this file is written this way: the column is added nullable, filled for
-- every existing row, and **only then** made not null. A constraint added before its own data is legal can only
-- pass on a database that never had the problem it exists to fix — which is how V55 took the site down.

-- 1. The owner column, nullable for now.
alter table simulation_package
    add column if not exists facilitator_id bigint references app_user (id) on delete restrict;

comment on column simulation_package.facilitator_id is
    'D140: the facilitator this package was prepared for. Only this account sees it and starts sessions from it; '
    'ADMIN and BUILDER see every package in the tenant so they can manage the assignments.';

-- 2. Fill every existing package before anything demands the value.
--
--    Ruling: an existing package belongs to the tenant's current facilitator, so nothing silently becomes nobody's
--    and no class is orphaned. "Current" is the **oldest active FACILITATOR** in the tenant — the account that has
--    been running these classes. Only if a tenant has no active facilitator is an active ADMIN used, because an
--    admin is not the person who teaches.
update simulation_package p
set facilitator_id = (
        select u.id from app_user u
        where u.tenant_id = p.tenant_id and u.role = 'FACILITATOR' and u.active
        order by u.id
        limit 1
    )
where p.facilitator_id is null;

update simulation_package p
set facilitator_id = (
        select u.id from app_user u
        where u.tenant_id = p.tenant_id and u.role = 'ADMIN' and u.active
        order by u.id
        limit 1
    )
where p.facilitator_id is null;

-- 3. A package still unowned belongs to a tenant with no active facilitator and no active admin. That should not
--    exist, but a not-null column must not be added on hope — say so plainly rather than invent a user id.
do $$
declare
    orphans integer;
begin
    select count(*) into orphans from simulation_package where facilitator_id is null;
    if orphans > 0 then
        raise exception 'V56: % package(s) belong to a tenant with no active facilitator or admin; '
            'assign an owner before this migration can complete', orphans;
    end if;
end $$;

-- 4. Only now, with every row legal, state the rule.
alter table simulation_package
    alter column facilitator_id set not null;

create index if not exists simulation_package_facilitator_idx
    on simulation_package (facilitator_id, tenant_id);
