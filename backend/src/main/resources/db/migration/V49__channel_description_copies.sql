-- V49: the shipped channel descriptions reach the packages frozen before them (D127).
--
-- V48 wrote the three descriptions onto the shipped templates. A package published earlier is a *frozen
-- copy* of its template (D33), so its channel rows were copied before there was a description to copy — and
-- a class in that package reads no channel paragraph at all. This is the same gap V43 closed for the channel
-- names when V42 introduced them, so it uses the same walk: a recursive `based_on` lineage from every shipped
-- template, because one level would miss the path the product actually uses (draft → package → part).
--
-- The guard is V43's too: only rows still carrying the shipped name are backfilled. A trainer who renamed a
-- channel keeps the trainer's words, and no description is invented for a channel that has been repurposed.

with recursive lineage as (
    select key from scenario
    where key in ('standard-v1', 'preset-cost-v1', 'preset-diff-v1', 'preset-focus-v1')
    union
    select child.key
    from scenario child
    join lineage parent on child.based_on = parent.key
)
update channel_text frozen
set description = shipped.description
from channel_text shipped
where frozen.description is null
  and shipped.scenario_key = 'standard-v1'
  and shipped.channel_code = frozen.channel_code
  and shipped.lang = frozen.lang
  and shipped.description is not null
  and frozen.scenario_key in (select key from lineage)
  -- Only where the row still carries the shipped name: somebody's own wording is not ours to describe.
  and frozen.name = shipped.name;
