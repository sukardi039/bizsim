-- V51: re-spread the live auto-player library across the three strategies (D128 follow-up).
--
-- V50 added `strategy` with a COST default and only re-spread the nine *shipped* key shapes (`high_aggressive`
-- etc.). A tenant whose library was created through the builder carries keys of the form
-- `autowork-<level>-<letter>-<level>`, so every one of its rows kept the default and a demo class ended up with
-- nine cost leaders instead of a spread. That is the state the live tenant reported: nine profiles, all COST.
--
-- This migration re-spreads those rows at each level, matching the strategy to the personality the row already
-- had, so the class meets one of each:
--
--   A = AGGRESSIVE  → COST            (it already pushed price and share hardest)
--   B = BALANCED    → FOCUS           (served the middle ground; now it narrows to a class)
--   C = CAUTIOUS    → DIFFERENTIATION (it already charged a premium)
--
-- Guarded twice: only rows that are still COST (so a trainer's own edit is never overwritten), and only keys
-- of the builder shape. A profile a human re-labelled is left exactly as they left it.

update bot_profile
set strategy = case
        when key like '%-a-%' then 'COST'
        when key like '%-b-%' then 'FOCUS'
        when key like '%-c-%' then 'DIFFERENTIATION'
        else strategy
    end,
    version = version + 1,
    updated_at = now()
where strategy = 'COST'
  and key like 'autowork-%-%-%'
  and key ~ '^autowork-[a-z]+-[abc]-[a-z]+$';

comment on column bot_profile.strategy is
    'D128: which Porter strategy this company plays; level stays the quality of its execution. '
    'V51 re-spread the builder-created library so a class meets all three.';
