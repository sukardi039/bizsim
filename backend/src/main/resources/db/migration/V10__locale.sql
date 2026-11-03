-- Language is a property of a person and of a tenant, not of a screen (i18n foundation, 3 Nov 2026).
--
-- Resolution order at the edge: the signed-in user's choice, then the tenant's default, then the
-- browser's Accept-Language. The server never stores translated text: it returns codes and the client
-- owns the words, so adding a language is a catalog file, not a migration.
--
-- The check constraint is deliberately explicit rather than a lookup table: a locale is a fixed
-- product decision (which languages we actually support), and a typo should fail at write time.

alter table tenant add column locale varchar(8) not null default 'en'
    check (locale in ('en', 'zh'));

alter table app_user add column locale varchar(8)
    check (locale in ('en', 'zh'));

comment on column tenant.locale is 'default language for everyone in this tenant';
comment on column app_user.locale is
    'the person''s own choice; null = follow the tenant default';
