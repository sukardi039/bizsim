-- V57: retire the bot accounts whose profile no longer exists (D142).
--
-- V55 replaced the nine personality-era bot profiles with twelve Porter positions, but it retired the *profiles*
-- and left their accounts active. Those nine accounts cannot be seated — no profile means no policy to play — yet
-- they still appeared in every people picker. D142 hides a disabled auto worker from the roster, so this is the
-- data half: an auto worker whose profile is gone, or inactive, has no reason to be an active account.
--
-- A guard for the future as well as a clean-up for the past: any account orphaned the same way by a later
-- migration is retired by the same rule, so this class of clutter cannot silently accumulate again.
--
-- Only AUTO_WORKER accounts are touched. A person's account is never disabled by a migration — that is a decision
-- for an administrator, and disabling a human because a foreign key went quiet would be exactly the kind of
-- silent, unrecoverable change a migration must not make.
update app_user u
set active = false
where u.role = 'AUTO_WORKER'
  and u.active
  and (
      -- the profile was removed, and the foreign key left the pointer null
      u.bot_profile_id is null
      -- or the profile exists but is no longer active
      or not exists (
          select 1 from bot_profile b
          where b.id = u.bot_profile_id and b.active
      )
  );

comment on column app_user.active is
    'D142: a disabled auto worker is hidden from the people list; a disabled person is still listed so an '
    'administrator can re-enable them. An auto worker with no active profile is retired here rather than left '
    'looking seatable.';
