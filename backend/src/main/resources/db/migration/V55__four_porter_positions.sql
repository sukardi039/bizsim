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

-- 1. Widen the constraint to the four positions. Dropped and re-added because a check cannot be altered in place.
--
-- The column was varchar(20), which fits COST and DIFFERENTIATION but not DIFFERENTIATION_FOCUS (22) — the
-- insert failed with "value too long for type character varying(20)", caught by the suite rather than by a
-- trainer meeting an empty library. Widened first, so the constraint has room to describe what it allows.
alter table bot_profile alter column strategy type varchar(32);

alter table bot_profile drop constraint if exists bot_profile_strategy_check;
alter table bot_profile add constraint bot_profile_strategy_check
    check (strategy in ('COST', 'DIFFERENTIATION', 'COST_FOCUS', 'DIFFERENTIATION_FOCUS'));

comment on column bot_profile.strategy is
    'D135: which cell of Porter''s grid this company plays — (broad|narrow) x (cost|differentiation). Level stays '
    'the quality of its execution.';

-- 2. The retired FOCUS position maps to COST_FOCUS: it spent nothing on quality and bought no capacity, which is
--    nearer the cost variant than the differentiation one. Recorded, not guessed silently.
update bot_profile set strategy = 'COST_FOCUS' where strategy = 'FOCUS';

-- 3. Personality is stated rather than defaulted. D130 stopped writing it, so rows carried whatever the column
--    default was — a value that looked meaningful and was not. BALANCED says exactly what is true: this profile
--    takes no stance on the retired axis. Nothing reads it (the decision trace stores the params snapshot, not a
--    personality), so this is a marker, not a behaviour.
update bot_profile set personality = 'BALANCED' where personality is null or personality <> 'BALANCED';

comment on column bot_profile.personality is
    'RETIRED (D130) and stated as BALANCED from D135: a marker only. Never read by the policy and never written '
    'with any other value; the decision trace records the params snapshot, not a personality.';

-- 4. Retire the seeding of the old nine. The twelve are created per tenant by InitDataService.ensureBotLibrary,
--    driven by limits.json, for any tenant whose active library lacks a position.
update bot_profile p
set active = false, updated_at = now()
where p.active
  and p.key ~ '^[a-z_]+-(high|mid|low)$'
  and not exists (select 1 from player pl where pl.bot_profile_id = p.id)
  and not exists (select 1 from app_user u where u.bot_profile_id = p.id and u.role <> 'AUTO_WORKER');
