-- V43: the shipped channels' Chinese names reach the packages already frozen (D114).
--
-- V42 seeded `channel_text` for the four shipped templates only. A package published *before* it froze
-- its own copy of `standard-v1` without those rows, so a Chinese class played from such a package still
-- read the customer classes in Chinese (大众市场 / 中端市场 / 高端客户) and the three channels in English
-- ("Trade" / "Digital" / "Field sales") in the same marketing card — the very defect D114 closes, left
-- standing on every package published earlier. V34 (product names) had the same shape and seeded copies
-- too; V42 did not, and this migration completes it.
--
-- **Only a channel whose authored name is still the shipped one is translated.** A trainer who renamed a
-- channel in their own template chose those words, and giving that channel a Chinese name it never had
-- would be a translation nobody chose — D111's rule for the market weights, applied to the words. A
-- renamed channel keeps its own name in every language, which is what the fallback in `ChannelSpec.nameIn`
-- already does.
--
-- **Which templates.** Any template in the lineage of a shipped one: a package published from
-- `standard-v1`, a package published from a tenant draft that was itself copied from it, and so on. The
-- recursive walk is deliberate — one level of `based_on` would miss exactly the hand-over path the
-- product actually uses (draft → package).

with recursive lineage as (
    select key from scenario
    where key in ('standard-v1', 'preset-cost-v1', 'preset-diff-v1', 'preset-focus-v1')
    union
    select child.key
    from scenario child
    join lineage parent on child.based_on = parent.key
)
insert into channel_text (scenario_key, channel_code, lang, name)
select c.scenario_key, c.code, 'zh',
       case c.code
           when 'DIGITAL' then '数字营销'
           when 'TRADE'   then '经销商渠道'
           when 'FIELD'   then '直销团队'
       end
from channel c
join lineage l on l.key = c.scenario_key
where c.code in ('DIGITAL', 'TRADE', 'FIELD')
  and c.name = case c.code
                   when 'DIGITAL' then 'Digital'
                   when 'TRADE'   then 'Trade'
                   when 'FIELD'   then 'Field sales'
               end
on conflict (scenario_key, channel_code, lang) do nothing;
