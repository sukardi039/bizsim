-- V52: personality is retired; the library is the nine strategy × level cells (D130).
--
-- The owner's ruling after reviewing the matrix: 3 strategies × 3 levels × 3 personalities is 27 profiles, and
-- personality pushed price and share *on top of* the strategy — so some cells contradicted their own strategy
-- (COST + AGGRESSIVE double-undercut, DIFFERENTIATION + AGGRESSIVE resisted its own premium). The strategy now
-- owns price and share outright, and the library is nine.
--
-- **The column stays, and stays nullable.** It is written on every historical `bot_decision` trace — the
-- "why did this company do that" record a class can be shown — and dropping it would lose the record of which
-- profile produced decisions already played. It is a legacy marker now: never written, never read by the policy.
--
-- No row is deleted. A profile a lease or a history row points at must keep existing (D47: decisions point at
-- versions). Existing profiles keep their personality value; the library's duplicates are retired, not removed.

alter table bot_profile alter column personality drop not null;

comment on column bot_profile.personality is
    'D130: RETIRED — a legacy marker for profiles and decision traces created before the strategy axis replaced '
    'it. Never written for a new profile, never read by the policy. Kept so history stays readable.';

-- The nine cells are seeded by the application, not here: tenants are created by InitDataService *after*
-- migrations run, so a cross-join against `tenant` matches nothing at this point (it did, and silently inserted
-- zero rows). The library of nine is created for each tenant in InitDataService.ensureBotLibrary.

-- The pre-D130 library carried one profile per personality slot at each level (keys shaped
-- autowork-<level>-<letter>-<level>). Those are now duplicates of the nine cells: three rows that differ only by
-- a retired axis. Retire the ones nobody is seated on, keeping any still in use, so nothing a class depends on
-- disappears.
update bot_profile p
set active = false, updated_at = now()
where p.active
  and p.personality is not null
  and p.key ~ '^autowork-[a-z]+-[abc]-[a-z]+$'
  and not exists (select 1 from player pl where pl.bot_profile_id = p.id)
  and not exists (select 1 from app_user u where u.bot_profile_id = p.id and u.role <> 'AUTO_WORKER');
