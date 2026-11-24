-- V54: `quality-led-v1`, the preset that makes quality a real strategy (D133).
--
-- D132 measured the shipped economy and found a defect in the *economics*, not in the bots: one quality point
-- costs 60m a month and lifts attractiveness by (51/50)^0.8 = 1.6 %, while a 6 % price cut — which costs nothing
-- to hold — lifts it by 1.06^1.5 = 9 %. Quality therefore could not pay back, which is why the differentiator
-- lost money until its spending was rationed, and why FOCUS (spend nothing) out-earned both.
--
-- The owner asked for a variant rather than an edit: `standard-v1` is what every historical run played, so its
-- numbers must not change. This preset is a full copy with exactly two knobs moved:
--
--   qualityElasticity       0.80 -> 1.30   quality becomes a genuine differentiator
--   qualityCostPerPoint     60m  -> 40m    still a real commitment (~0.8 months of fixed cost per point)
--
-- Nothing else moves. Compared with the other presets, this one keeps the shipped market size, the shipped unit
-- costs and the shipped price elasticity, so the ONLY difference a class experiences is what quality is worth.
-- That is what makes it a usable A/B against `standard-v1` rather than another themed economy.
--
-- The copy is written generically, exactly as V35 does: every row is taken as jsonb, its scenario_key replaced,
-- and cast back to the table's own type, so a later column addition cannot silently drop data from the copy.

do $$
declare
    clone_tables text[] := array[
        'product', 'product_text', 'channel', 'rnd_project', 'scenario_round',
        'environment_event', 'environment_event_effect', 'customer_segment'];
    tbl          text;
begin
    insert into scenario (key, name, definition, version, status)
    select 'quality-led-v1',
           'Quality led — better products earn their premium',
           s.definition, 1, 'PUBLISHED'
    from scenario s where s.key = 'standard-v1';

    foreach tbl in array clone_tables loop
        execute format(
            'insert into %1$I select (jsonb_populate_record(null::%1$I,
                 to_jsonb(t) || jsonb_build_object(''scenario_key'', %2$L))).*
               from %1$I t where t.scenario_key = %3$L',
            tbl, 'quality-led-v1', 'standard-v1');
    end loop;
end $$;

-- The two knobs, and nothing else.
update scenario
set definition = definition
    || jsonb_build_object('elasticity', coalesce(definition -> 'elasticity', '{}'::jsonb)
                                            || jsonb_build_object('quality', 1.30),
                          'qualityCostPerPoint', 40000000)
where key = 'quality-led-v1';
