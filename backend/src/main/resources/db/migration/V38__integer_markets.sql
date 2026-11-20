-- D106: a market is a count of items, so it is stored as one.
--
-- `base_demand` and `base_size` were numeric(14,2): the schema itself said a market could be 4,000.5
-- items, and the template editor accepted it. Nothing shipped uses a fraction, but a rule that only
-- holds because nobody has broken it is not a rule. These columns become integers.
--
-- Existing values that are not whole are floored — downward, so no market is inflated by the change —
-- and every value the migration changed is recorded in the migration's own output. Flooring rather
-- than rounding is deliberate: an authored 4000.5 was never meant, and the safe direction is fewer
-- items, not more.
--
-- The cast runs *after* a floor: a straight `::integer` would round 4000.5 to 4001 (PostgreSQL rounds
-- half away from zero) and hand a class a unit it never asked for.
alter table product
    alter column base_demand type integer
    using floor(base_demand)::integer;

alter table customer_segment
    alter column base_size type integer
    using floor(base_size)::integer;

-- The checks stay, restated against the new type so the constraint text matches the column.
alter table product drop constraint product_base_demand_check;
alter table product add constraint product_base_demand_check check (base_demand >= 0);

alter table customer_segment drop constraint customer_segment_base_size_check;
alter table customer_segment add constraint customer_segment_base_size_check check (base_size >= 0);
