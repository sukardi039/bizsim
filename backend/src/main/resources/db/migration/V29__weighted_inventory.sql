-- D79: stock is charged at what it cost, not at what it would cost today.
--
-- Until now the engine charged COGS at the *current* month's unit cost, while the cash for those
-- units left the bank at an earlier month's cost. When the cost index rose, every unit sold out of
-- stock was expensed at a price the company never paid — so a term that started and ended with no
-- stock still showed cash and profit drifting apart (measured: 172 units carried across a 590,000 ->
-- 680,860 cost rise produced a 15,627,920 gap). Weighted-average costing removes the drift: the
-- stock carries the money actually spent on it, and COGS spends that value.

alter table company_product add column if not exists stock_value numeric(16, 2) not null default 0;

-- Existing companies hold stock that the old basis valued at the current cost — that is the only
-- basis stored anywhere, so it is the defensible starting value. Templates still on REPLACEMENT
-- costing ignore this column entirely, so this changes no published class's numbers.
update company_product
set stock_value = stock * unit_cost
where stock > 0 and stock_value = 0;

-- A tenant draft that has not chosen a basis yet is copied from the shipped template, so stating it
-- there makes every new copy weighted without a code path that rewrites a builder's definition.
update scenario
set definition = jsonb_set(definition, '{costBasis}', '"WEIGHTED"')
where key = 'standard-v1' and tenant_id is null and not jsonb_exists(definition, 'costBasis');
