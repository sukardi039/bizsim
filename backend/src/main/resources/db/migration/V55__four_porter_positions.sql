-- V55: Porter's grid is two by two — four positions, twelve profiles (D135).
--
-- D130 retired personality and left three strategies: COST, DIFFERENTIATION and FOCUS. Porter's own taxonomy
-- splits the third by the same advantage axis as the other two:
--
--                     cost advantage      differentiation advantage
--   broad scope       COST                DIFFERENTIATION
--   narrow scope      COST_FOCUS          DIFFERENTIATION_FOCUS
--
-- So focus is not a third advantage, it is a narrow *scope* applied to one of the other two. The single FOCUS
-- position we shipped had the scope but no advantage: it narrowed the line and then spent nothing, and the
-- calibration showed it winning by restraint rather than by competing. That is not focus, and it made the
-- economics unteachable. This migration replaces it with the two positions Porter actually describes.
--
-- Consequence, recorded deliberately: the library is now twelve (four positions x three levels), superseding the
-- nine of D130.
--
-- ---------------------------------------------------------------------------------------------------------------
-- ORDER MATTERS, and getting it wrong took the site down once.
--
-- The first version of this file added the new check constraint at the top and mapped the retired FOCUS rows
-- further down. On an empty test database that passes — there are no FOCUS rows to violate anything. On the live
-- database, whose nine profiles were all COST but which also carried rows on the old value, the constraint was
-- rejected the instant it was created:
--
--   ERROR: check constraint "bot_profile_strategy_check" of relation "bot_profile" is violated by some row
--
-- Flyway then marked V55 failed, the application refused to boot, and the api container restart-looped for 50
-- minutes. The rule this file now follows: **make the data legal first, and let the constraint be the last thing
-- that happens.** A check added before its own data migration can only pass on a database that never had the
-- problem it exists to fix.
-- ---------------------------------------------------------------------------------------------------------------

-- 1. Widen the column first. varchar(20) fits COST and DIFFERENTIATION but not DIFFERENTIATION_FOCUS (22).
alter table bot_profile alter column strategy type varchar(32);

-- 2. **Drop the old constraint before touching the data.** The pre-V55 check only allows COST, DIFFERENTIATION
--    and FOCUS, so it rejects the very update that retires FOCUS — the migration cannot fix the rows while the
--    old rule is still standing. This was the second half of the same mistake: the first version added a new
--    constraint too early, and a version that updated first would still have been blocked by the old one.
alter table bot_profile drop constraint if exists bot_profile_strategy_check;

-- 3. Make every existing row legal while no constraint is judging it.
--
--    The retired FOCUS position maps to COST_FOCUS: it spent nothing on quality and bought no capacity, which is
--    nearer the cost variant than the differentiation one. Recorded, not guessed silently.
update bot_profile set strategy = 'COST_FOCUS' where strategy = 'FOCUS';

--    Personality is stated rather than defaulted. D130 stopped writing it, so rows carried whatever the column
--    default was — a value that looked meaningful and was not. BALANCED says exactly what is true: this profile
--    takes no stance on the retired axis. Nothing reads it (the decision trace stores the params snapshot, not a
--    personality), so this is a marker, not a behaviour.
update bot_profile set personality = 'BALANCED' where personality is null or personality <> 'BALANCED';

-- 4. Retire the old nine. The twelve are created per tenant by InitDataService.ensureBotLibrary, driven by
--    limits.json, for any tenant whose active library lacks a position.
update bot_profile p
set active = false, updated_at = now()
where p.active
  and p.key ~ '^[a-z_]+-(high|mid|low)$'
  and not exists (select 1 from player pl where pl.bot_profile_id = p.id)
  and not exists (select 1 from app_user u where u.bot_profile_id = p.id and u.role <> 'AUTO_WORKER');

-- 5. Only now, with every row already legal and no old rule in the way, state the new constraint. Any row still
--    on an unknown value aborts the migration here — the correct place to find out, not after the app started.
alter table bot_profile add constraint bot_profile_strategy_check
    check (strategy in ('COST', 'DIFFERENTIATION', 'COST_FOCUS', 'DIFFERENTIATION_FOCUS'));

comment on column bot_profile.strategy is
    'D135: which cell of Porter''s grid this company plays — (broad|narrow) x (cost|differentiation). Level stays '
    'the quality of its execution.';

comment on column bot_profile.personality is
    'RETIRED (D130) and stated as BALANCED from D135: a marker only. Never read by the policy and never written '
    'with any other value; the decision trace records the params snapshot, not a personality.';
