-- V50: a bot profile says which competitive strategy it is playing (D128).
--
-- The matrix was level × personality: level (LOW/MID/HIGH) is how well the company plays, personality
-- (CAUTIOUS/BALANCED/AGGRESSIVE) is how hard it pushes on price and share. Neither axis is Porter. The owner's
-- point, and it is right: a class learns from opponents whose *strategies* differ, not whose tempers do.
--
-- `strategy` is the second axis now, and `level` keeps its proper job — competence. Three shipped strategies:
--
--   COST            compete on price with the cost structure to justify it: volume, capacity, little quality
--   DIFFERENTIATION charge a premium and earn it: quality and R&D, narrower volume
--   FOCUS           compete in one customer class: the product aimed at it, the channel that reaches it
--
-- Backfill is behaviour-preserving on purpose: every existing profile is marked BALANCED-equivalent until the
-- policy reads the column, so a live class does not change its opponents mid-course. A trainer who wants the
-- new axis sets it on the profile; the seeded demo rows are converted to a spread in the same migration only
-- where they are still the shipped nine keys.

alter table bot_profile add column strategy varchar(20) not null default 'COST'
    check (strategy in ('COST', 'DIFFERENTIATION', 'FOCUS'));

comment on column bot_profile.strategy is
    'D128: which Porter strategy this company plays; level stays the quality of its execution.';

-- The seeded demo companies (the shipped nine key shapes) become a spread across the three strategies, so a
-- new tenant meets one of each. Only rows still carrying a shipped key shape are touched.
update bot_profile set strategy = case
    when key like '%aggressive%' then 'COST'
    when key like '%cautious%' then 'DIFFERENTIATION'
    else 'FOCUS'
end
where key in ('high_aggressive', 'mid_aggressive', 'low_aggressive',
              'high_cautious', 'mid_cautious', 'low_cautious',
              'high_balanced', 'mid_balanced', 'low_balanced');
