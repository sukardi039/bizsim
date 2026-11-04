-- The class language is decided with the package (ruling 4 Nov 2026).
--
-- Until now the interface language resolved person -> tenant -> English. A class is one audience,
-- though: a facilitator preparing a Chinese cohort should not have to hope every trainee has set their
-- own preference. A package may therefore name its language, and a trainee sitting in a session
-- started from it follows that language for the whole session.
--
-- Nullable on purpose: no value means "no class language", and each person keeps their own choice --
-- which is the behaviour every existing package already has.

alter table simulation_package add column locale text
    check (locale is null or locale in ('en', 'zh'));

comment on column simulation_package.locale is
    'Interface language forced on trainees seated in sessions started from this package; null = follow each person';
