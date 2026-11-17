-- D100: the three Porter presets, shipped as data.
--
-- Each preset is a full copy of `standard-v1` with a different economy, so a trainer can run the same
-- class three times and let the position decide who wins. Nothing here is a new code path: the engine
-- reads the same tables it always did, and `PresetCalibrationTest` proves each preset rewards the
-- strategy it is named for (two plans, six rounds, winner by cumulative profit).
--
-- The copy is written generically: every row is taken as jsonb, its scenario_key replaced, and cast
-- back to the table's own type. That way a later migration adding a column to any cloned table does
-- not silently drop that column from a preset — the failure mode that makes a copy play a different
-- game from its original (see D87's clone bug).
do $$
declare
    clone_tables text[] := array[
        'product', 'product_text', 'channel', 'rnd_project', 'scenario_round',
        'environment_event', 'environment_event_effect', 'customer_segment'];
    preset       record;
    tbl          text;
begin
    for preset in
        select * from (values
            ('preset-cost-v1',  'Cost leadership — a cheaper mass product and scale'),
            ('preset-diff-v1',  'Differentiation — quality and brand carry the premium'),
            ('preset-focus-v1', 'Focus — one customer class, no cross-class business')
        ) as t(key, name)
    loop
        -- The definition is the shipped one; only the named knobs move, so a preset cannot drift
        -- from the economy the calibration was run on.
        insert into scenario (key, name, definition, version, status)
        select preset.key, preset.name, s.definition, 1, 'PUBLISHED'
        from scenario s where s.key = 'standard-v1';

        foreach tbl in array clone_tables loop
            execute format(
                'insert into %1$I select (jsonb_populate_record(null::%1$I,
                     to_jsonb(t) || jsonb_build_object(''scenario_key'', %2$L))).*
                   from %1$I t where t.scenario_key = %3$L',
                tbl, preset.key, 'standard-v1');
        end loop;
    end loop;
end $$;

-- ---------------------------------------------------------------- cost leadership
-- Price moves demand hard; quality, brand and marketing hardly at all, because these customers are
-- buying a commodity. The mass product is cheaper to make (590k -> 430k) and faster to build
-- (40 -> 48 units per worker): that is the cost advantage which lets a discount earn money instead of
-- buying demand the factory cannot serve. The market is three times the shipped size, so the volume
-- plan can actually reach its capacity, and the premium plan cannot absorb it all.
update scenario set definition = definition
    || jsonb_build_object(
        'elasticity', jsonb_build_object('price', 2.20, 'quality', 0.30, 'brand', 0.25,
                                         'marketing', 0.35),
        'fixedCostPerMonth', 40000000,
        'inertia', 0.5)
where key = 'preset-cost-v1';

update product set base_unit_cost = 430110, units_per_worker = 48
where scenario_key = 'preset-cost-v1' and code = 'P1';

update customer_segment set base_size = base_size * 3
where scenario_key = 'preset-cost-v1';

-- ---------------------------------------------------------------- differentiation
-- Customers pay for better, not for cheaper: quality and brand elasticities are high, price is almost
-- ignored, and the position costs money to hold (fixed cost 80M). The market is the shipped one —
-- a differentiated player does not need volume to win.
update scenario set definition = definition
    || jsonb_build_object(
        'elasticity', jsonb_build_object('price', 1.10, 'quality', 1.40, 'brand', 0.90,
                                         'marketing', 0.90),
        'fixedCostPerMonth', 80000000,
        'inertia', 0.5)
where key = 'preset-diff-v1';

-- ---------------------------------------------------------------- focus
-- One class only: every product stops competing for the other classes' business, so the niche is the
-- whole market and the line has to carry the company. The niche is three times the shipped size so a
-- focused company can fill a factory with it; overhead is the lowest of the three (35M).
update scenario set definition = definition
    || jsonb_build_object(
        'elasticity', jsonb_build_object('price', 1.30, 'quality', 1.00, 'brand', 0.50,
                                         'marketing', 0.60),
        'fixedCostPerMonth', 35000000,
        'inertia', 0.5)
where key = 'preset-focus-v1';

update product set cross_segment_pct = 0
where scenario_key = 'preset-focus-v1';

update customer_segment set base_size = base_size * 3
where scenario_key = 'preset-focus-v1';
