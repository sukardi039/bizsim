-- V42: a channel's name is per language, its identity is not (D114).
--
-- D87 gave products a name per language beside a single mechanic. Channels were left out: one English
-- `name` column while everything around it translated -- the customer classes, the products, the whole
-- interface. A trainer running a Chinese class saw the classes as 大众市场 / 中端市场 / 高端客户 and the
-- three marketing channels as "Trade" / "Digital" / "Field sales" in the same card, so the one screen
-- that explains where the money goes mixed two languages.
--
-- Which shape: the product pattern, not the D86 card pattern. A channel's code is a foreign key in the
-- economy -- a submitted decision's marketing lines name it, and the market-specific reach columns key
-- on it -- so duplicating channels into TRADE-ZH would fork that identity and every decision would have
-- to know which language's code it meant. So: one mechanic (code, reach_weight, brand_alpha, the three
-- market columns) and a name per language beside it. A language with no row falls back to the name the
-- author wrote, which is what makes a new language usable immediately.

create table channel_text (
    scenario_key varchar(60) not null references scenario (key) on delete cascade,
    channel_code varchar(20) not null,
    lang         varchar(2)  not null,
    name         varchar(160) not null,
    primary key (scenario_key, channel_code, lang),
    foreign key (scenario_key, channel_code)
        references channel (scenario_key, code) on delete cascade,
    check (lang ~ '^[a-z]{2}$')
);

comment on table channel_text is
    'D114: a channel name in one language; the channel row itself stays single';

-- The shipped three, in Chinese, for the shipped templates only.
--
-- Scope is deliberately narrow, the same rule D111 used for the market weights: `standard-v1` and the
-- three presets cloned from it. A tenant's own channel is somebody's own wording -- naming it 数字营销
-- when they call it something else would be a translation nobody chose, and the editor shows a missing
-- translation as a fallback rather than a blank (so nothing looks unfinished). A package published from
-- a shipped template carries these rows through `cloneInto`, so a class played from the package reads
-- the same words.
--
-- The words follow what the channel is (see V41 and the guide's §3.1c table): DIGITAL is paid digital
-- media, TRADE is the wholesale/dealer counter, FIELD is a person selling face to face.

insert into channel_text (scenario_key, channel_code, lang, name)
select c.scenario_key, c.code, 'zh',
       case c.code
           when 'DIGITAL' then '数字营销'
           when 'TRADE'   then '经销商渠道'
           when 'FIELD'   then '直销团队'
       end
from channel c
where c.scenario_key in ('standard-v1', 'preset-cost-v1', 'preset-diff-v1', 'preset-focus-v1')
  and c.code in ('DIGITAL', 'TRADE', 'FIELD')
on conflict (scenario_key, channel_code, lang) do nothing;
