-- V34: a product's name is per language, its identity is not (D87).
--
-- D86 gave every language its own cards and cycle, because a card is a choice/fact the class reads and
-- nothing else keys on it. A product is different: its code is a foreign key in the economy — decisions
-- name it, `company_product` holds stock against it, a company profile lists it, an R&D project targets
-- it. Duplicating products into `P1-ZH` would fork that identity and every decision would have to know
-- which language's code it means.
--
-- So a product follows the customer-class pattern instead: one mechanic (code, price, cost, quality,
-- demand, units per worker, class, cross-segment share) and a name per language beside it. A missing
-- translation falls back to the name the author wrote, so a new language is usable immediately.

create table product_text (
    scenario_key varchar(60) not null references scenario (key) on delete cascade,
    product_code varchar(20) not null,
    lang         varchar(2)  not null,
    name         varchar(160) not null,
    description  varchar(600),
    primary key (scenario_key, product_code, lang),
    foreign key (scenario_key, product_code)
        references product (scenario_key, code) on delete cascade,
    check (lang ~ '^[a-z]{2}$')
);

comment on table product_text is
    'D87: a product name and short description in one language; the product row itself stays single';

-- The shipped three, in Chinese, for every template that carries them (copies included).
insert into product_text (scenario_key, product_code, lang, name, description)
select p.scenario_key, p.code, 'zh',
       case p.code
           when 'P1' then '基础款'
           when 'P2' then '标准款'
           when 'P3' then '高端款'
           else p.name
       end,
       case p.code
           when 'P1' then '面向大众市场的入门产品，以价格和可得性取胜。'
           when 'P2' then '面向中端市场的主力产品，兼顾品质与价格。'
           when 'P3' then '面向高端客户的产品，以品质和品牌取胜。'
           else null
       end
from product p
where p.code in ('P1', 'P2', 'P3')
on conflict (scenario_key, product_code, lang) do nothing;

-- Customer classes were already one mechanic with per-language words (D83/D86); nothing to move here,
-- but the same table now serves both, so say it once.
comment on table environment_text is
    'D83/D87: per-language names and descriptions beside a single mechanic (customer classes, and any object that keeps one row)';
